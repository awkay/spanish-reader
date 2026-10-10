package main

import (
	"cmp"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"strings"
	"sync"
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

Read carefully: scratches, cracks and glare are not letters, spaces or accent marks. Where a word is hard to read (damage, glare, a crack, an odd font), choose the reading that makes sense in its sentence and in the text as a whole; but never change, correct or modernize text you can read clearly, even if it looks wrong. The text starts with the heading, if there is one.

Reply with JSON only: {"found": true, "title": "a short title (the heading, if there is one)", "text": "the transcription"}. If there is no readable Spanish text, reply {"found": false}.`

// photoReconcilePrompt merges independent transcriptions that disagree into one.
const photoReconcilePrompt = `You check transcriptions of Spanish text in photos for a language learner's reading lesson. You get the photo (several photos are consecutive pages, in order) and several independent transcriptions of its main Spanish text; they differ where the photo is hard to read.

Write the one correct transcription. Where the transcriptions disagree, look at that spot in the photo again and choose the reading that is actually visible there and that makes sense in its sentence and in the whole text (a real Spanish word that fits the grammar and meaning). Scratches, cracks and glare are not letters, spaces or accent marks. Never change, correct or modernize text that is clearly legible, and don't add anything that isn't in the photo. Keep the layout of the transcriptions: the heading on its own line, a blank line between paragraphs, line breaks kept for verse, lists and menus.

Reply with JSON only: {"found": true, "title": "a short title (the heading, if there is one)", "text": "the transcription"}, or {"found": false} if there is no readable Spanish text.`

// photoCandidates independent readings are made of every upload. Single readings of a damaged sign vary (on a real
// one: "relict o", "relictó", a Cyrillic "relictо"); when they disagree, a reconciling pass picks, word by word, what
// is visible and makes sense in context, which got it right even when no single reading had.
const photoCandidates = 3

// errNoSpanish means the photos have no readable Spanish text.
var errNoSpanish = errors.New("no Spanish text found in the photo")

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

	res, err := s.readPhotos(r.Context(), images)
	if errors.Is(err, errNoSpanish) {
		writeError(w, http.StatusUnprocessableEntity, err.Error())
		return
	}
	if err != nil {
		writeError(w, http.StatusBadGateway, err.Error())
		return
	}
	title := clip(fields["title"], 200)
	if title == "" {
		title = clip(res.Title, 200)
	}
	if title == "" {
		title = openingWords(res.Text, 50)
	}
	l, err := s.store.PutLesson(Lesson{Title: title, Text: res.Text, SharedBy: clip(fields["sharedBy"], 100), Source: "photo"})
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, summarize(l))
}

// readPhotos transcribes the photos [photoCandidates] times in parallel and, unless the readings agree, has the
// model reconcile them against the photos.
func (s *Server) readPhotos(ctx context.Context, images []aiImage) (photoResult, error) {
	// Low effort misreads more (a crack read as a space, a Cyrillic "о", a dropped heading); high takes ~12 s.
	ask := func(system, user string) (photoResult, error) {
		reply, err := s.ai.Complete(ctx, aiRequest{
			System: system, User: user, Model: s.cfg.VisionModel, Images: images, Items: 4*len(images) + 2, Effort: "high",
		})
		if err != nil {
			return photoResult{}, err
		}
		res, err := parsePhotoResult(reply)
		if err != nil {
			log.Printf("photo lesson: %v: %s", err, truncate(reply, 300))
			return res, errors.New("the AI's answer couldn't be read; try again")
		}
		return res, nil
	}
	user := "Transcribe the Spanish text in this photo."
	if len(images) > 1 {
		user = fmt.Sprintf("Transcribe the Spanish text in these %d photos, in order, as one text.", len(images))
	}
	results := make([]photoResult, photoCandidates)
	errs := make([]error, photoCandidates)
	var wg sync.WaitGroup
	for i := range photoCandidates {
		wg.Add(1)
		go func() {
			defer wg.Done()
			results[i], errs[i] = ask(photoSystemPrompt, user)
		}()
	}
	wg.Wait()
	var found []photoResult
	answered := 0
	var firstErr error
	for i, res := range results {
		if errs[i] != nil {
			log.Printf("photo lesson: reading %d: %v", i+1, errs[i])
			firstErr = cmp.Or(firstErr, errs[i])
			continue
		}
		answered++
		if res.Found {
			found = append(found, res)
		}
	}
	switch {
	case answered == 0:
		return photoResult{}, firstErr
	case len(found)*2 <= answered: // most readings found nothing
		return photoResult{}, errNoSpanish
	case len(found) == 1 || agree(found):
		return found[0], nil
	}
	where := "this photo"
	if len(images) > 1 {
		where = "these photos"
	}
	var b strings.Builder
	fmt.Fprintf(&b, "Here are %d independent transcriptions of the main Spanish text in %s:\n", len(found), where)
	for i, res := range found {
		fmt.Fprintf(&b, "\n--- Transcription %d ---\n%s\n", i+1, res.Text)
	}
	b.WriteString("\nWrite the correct transcription.")
	merged, err := ask(photoReconcilePrompt, b.String())
	if err != nil || !merged.Found {
		// The readings themselves are still good text; better one of them than nothing.
		log.Printf("photo lesson: reconciling failed (%v, found=%v); using the first reading", err, merged.Found)
		return found[0], nil
	}
	if merged.Title == "" {
		merged.Title = found[0].Title
	}
	return merged, nil
}

// agree reports whether all readings have the same text, ignoring how whitespace and lines are laid out.
func agree(results []photoResult) bool {
	first := strings.Join(strings.Fields(results[0].Text), " ")
	for _, r := range results[1:] {
		if strings.Join(strings.Fields(r.Text), " ") != first {
			return false
		}
	}
	return true
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

// openingWords is a title for text without a heading: its first line, cut at a word boundary to about n characters.
func openingWords(text string, n int) string {
	line, _, _ := strings.Cut(strings.TrimSpace(text), "\n")
	words := strings.Fields(line)
	out := ""
	for i, w := range words {
		next := strings.TrimSpace(out + " " + w)
		if i > 0 && len([]rune(next)) > n {
			return strings.TrimRight(out, ",;:") + "…"
		}
		out = next
	}
	return out
}

// clip trims s and cuts it to at most n characters.
func clip(s string, n int) string {
	r := []rune(strings.TrimSpace(s))
	if len(r) > n {
		r = r[:n]
	}
	return strings.TrimSpace(string(r))
}
