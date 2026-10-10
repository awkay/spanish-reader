package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"strings"
	"time"
	"unicode"
)

// Photo lessons: the Android app uploads one or more photos (a sign, a menu, pages of a book), a vision-capable
// model transcribes the Spanish in them, and the result becomes a shared lesson like any other. The photos are only
// held in memory for the request; nothing but the text is kept.

const (
	maxPhotos     = 6
	maxPhotoBytes = 8 << 20
	// The phone shrinks photos before uploading (~0.5 MB each); this only stops abuse.
	maxPhotoUpload = maxPhotos*maxPhotoBytes + 1<<20
	maxPhotoText   = 60000
)

const photoSystemPrompt = `You transcribe Spanish text from photos for a language learner's reading lesson. A photo may show a sign in a park or on a street, a plaque, a menu, a poster, or a page of a book; several photos are consecutive pages, in order.

Transcribe only the main Spanish text, exactly as written: keep accents, ñ, ¿ and ¡, capitalization and punctuation; don't correct, translate, summarize or complete anything.

Leave out everything that isn't part of that main text: separate signs or labels elsewhere in the photo (a stop sign, a shop name in the background), text in other languages, graffiti, stickers, logos and brand names, license plates, page numbers, running headers and footers, and words cut off at the edge of the photo that can't be read.

Layout: put a heading on its own line, separate paragraphs with a blank line, and join lines that were only broken by the layout (rejoin words hyphenated across a line break). Keep line breaks for verse, lists and menus.

Read carefully: scratches, cracks and glare are not letters, spaces or accent marks. The text starts with the heading, if there is one.

Reply with JSON only: {"found": true, "title": "a short title (the heading, if there is one)", "text": "the transcription"}. If there is no readable Spanish text, reply {"found": false}.`

type photoResult struct {
	Found bool   `json:"found"`
	Title string `json:"title"`
	Text  string `json:"text"`
}

func (s *Server) handleImageLesson(w http.ResponseWriter, r *http.Request) {
	r.Body = http.MaxBytesReader(w, r.Body, maxPhotoUpload)
	mr, err := r.MultipartReader()
	if err != nil {
		writeError(w, http.StatusBadRequest, "bad request")
		return
	}
	fields := map[string]string{}
	var images []aiImage
	for {
		part, err := mr.NextPart()
		if errors.Is(err, io.EOF) {
			break
		}
		var tooBig *http.MaxBytesError
		if errors.As(err, &tooBig) {
			writeError(w, http.StatusRequestEntityTooLarge, "the photos are too large")
			return
		}
		if err != nil {
			writeError(w, http.StatusBadRequest, "bad request")
			return
		}
		if part.FormName() != "image" {
			raw, _ := io.ReadAll(io.LimitReader(part, 2048))
			fields[part.FormName()] = strings.TrimSpace(string(raw))
			continue
		}
		if len(images) == maxPhotos {
			writeError(w, http.StatusBadRequest, fmt.Sprintf("at most %d photos at a time", maxPhotos))
			return
		}
		data, err := io.ReadAll(io.LimitReader(part, maxPhotoBytes+1))
		if errors.As(err, &tooBig) || len(data) > maxPhotoBytes {
			writeError(w, http.StatusRequestEntityTooLarge, "a photo is too large")
			return
		}
		if err != nil {
			writeError(w, http.StatusBadRequest, "the upload failed")
			return
		}
		mediaType := imageType(data)
		if mediaType == "" {
			writeError(w, http.StatusBadRequest, "not a JPEG, PNG or WebP image")
			return
		}
		images = append(images, aiImage{MediaType: mediaType, Data: data})
	}
	if len(images) == 0 {
		writeError(w, http.StatusBadRequest, "no photo in the upload")
		return
	}

	user := "Transcribe the Spanish text in this photo."
	if len(images) > 1 {
		user = fmt.Sprintf("Transcribe the Spanish text in these %d photos, in order, as one text.", len(images))
	}
	reply, err := s.ai.Complete(r.Context(), aiRequest{
		// Low effort misreads more (a crack read as a space, a Cyrillic "о"); high costs a few seconds per photo.
		System: photoSystemPrompt, User: user, Model: s.cfg.VisionModel, Images: images, Items: 4*len(images) + 2, Effort: "high",
	})
	if err != nil {
		log.Printf("photo lesson: %v", err)
		writeError(w, http.StatusBadGateway, err.Error())
		return
	}
	res, err := parsePhotoResult(reply)
	if err != nil {
		log.Printf("photo lesson: %v: %s", err, truncate(reply, 300))
		writeError(w, http.StatusBadGateway, "the AI's answer couldn't be read; try again")
		return
	}
	if !res.Found {
		writeError(w, http.StatusUnprocessableEntity, "no Spanish text found in the photo")
		return
	}
	title := clip(fields["title"], 200)
	if title == "" {
		title = clip(res.Title, 200)
	}
	if title == "" {
		title = "Photo " + time.Now().Format("Jan 2, 15:04")
	}
	l, err := s.store.PutLesson(Lesson{Title: title, Text: res.Text, SharedBy: clip(fields["sharedBy"], 100), Source: "photo"})
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, summarize(l))
}

// parsePhotoResult reads the model's JSON (tolerating a code fence or text around it) and cleans the transcription.
func parsePhotoResult(reply string) (photoResult, error) {
	var res photoResult
	start, end := strings.Index(reply, "{"), strings.LastIndex(reply, "}")
	if start < 0 || end < start {
		return res, errors.New("no JSON object")
	}
	if err := json.Unmarshal([]byte(reply[start:end+1]), &res); err != nil {
		return res, err
	}
	res.Title = strings.Join(strings.Fields(res.Title), " ")
	res.Text = strings.TrimSpace(strings.ReplaceAll(res.Text, "\r\n", "\n"))
	res.Text = clip(latinLookalikes.Replace(res.Text), maxPhotoText)
	res.Title = latinLookalikes.Replace(res.Title)
	if !strings.ContainsFunc(res.Text, unicode.IsLetter) {
		res.Found = false
	}
	return res, nil
}

// latinLookalikes maps Cyrillic and Greek letters that look like Latin ones (a vision model sometimes emits them
// inside a Spanish word, which would then never match a dictionary form). Spanish text has no use for them.
var latinLookalikes = strings.NewReplacer(
	"а", "a", "е", "e", "о", "o", "р", "p", "с", "c", "у", "y", "х", "x", "і", "i", "ј", "j", "ѕ", "s",
	"А", "A", "В", "B", "Е", "E", "К", "K", "М", "M", "Н", "H", "О", "O", "Р", "P", "С", "C", "Т", "T", "Х", "X",
	"І", "I", "Ј", "J", "Ѕ", "S", "ο", "o", "Ο", "O", "α", "a", "ν", "v", "ι", "i",
)

// imageType sniffs the formats vision models accept.
func imageType(data []byte) string {
	switch t := http.DetectContentType(data); t {
	case "image/jpeg", "image/png", "image/webp":
		return t
	}
	return ""
}

// clip trims s and cuts it to at most n characters.
func clip(s string, n int) string {
	r := []rune(strings.TrimSpace(s))
	if len(r) > n {
		r = r[:n]
	}
	return strings.TrimSpace(string(r))
}
