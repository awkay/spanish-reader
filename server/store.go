package main

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"sync"
	"time"
	"unicode"
)

// Store keeps shared content as plain JSON files (two users; no database needed):
//
//	lessons/<id>.json             a shared lesson's title and text
//	sentences/<hh>/<hash>.json    per-sentence AI results: translation, idioms, glosses by word form
//
// Sentence hashes are computed by the clients (SHA-256 of the NFC-normalized, whitespace-collapsed sentence, the
// same on Android and web), so the server treats them as opaque keys. Nothing personal is stored.
type Store struct {
	dir string
	mu  sync.Mutex
}

type Lesson struct {
	ID        string `json:"id"`
	Title     string `json:"title"`
	Text      string `json:"text"`
	CreatedAt int64  `json:"createdAt"`
	SharedBy  string `json:"sharedBy,omitempty"`
}

type LessonSummary struct {
	ID        string `json:"id"`
	Title     string `json:"title"`
	CreatedAt int64  `json:"createdAt"`
	Words     int    `json:"words"`
	SharedBy  string `json:"sharedBy,omitempty"`
}

// countWords counts runs of letters (close to the reader's word count; good enough for the library list).
func countWords(text string) int {
	n, inWord := 0, false
	for _, r := range text {
		letter := unicode.IsLetter(r) || unicode.Is(unicode.Mn, r)
		if letter && !inWord {
			n++
		}
		inWord = letter || (inWord && (r == '\'' || r == '’' || r == '-'))
	}
	return n
}

func summarize(l Lesson) LessonSummary {
	return LessonSummary{l.ID, l.Title, l.CreatedAt, countWords(l.Text), l.SharedBy}
}

type Phrase struct {
	Phrase  string `json:"phrase"`
	Meaning string `json:"meaning"`
}

// SentenceData is what's known about one sentence. Glosses are kept as raw JSON (the client's Gloss shape).
type SentenceData struct {
	Hash        string                     `json:"hash"`
	Translation string                     `json:"translation,omitempty"`
	Phrases     []Phrase                   `json:"phrases,omitempty"`
	Scanned     bool                       `json:"scanned,omitempty"` // idioms were looked for (an empty list is meaningful)
	Glosses     map[string]json.RawMessage `json:"glosses,omitempty"`
}

var (
	hashRe = regexp.MustCompile(`^[0-9a-f]{64}$`)
	idRe   = regexp.MustCompile(`^[0-9a-f]{16}$`)
)

func newStore(dir string) (*Store, error) {
	for _, d := range []string{"lessons", "sentences"} {
		if err := os.MkdirAll(filepath.Join(dir, d), 0o700); err != nil {
			return nil, err
		}
	}
	return &Store{dir: dir}, nil
}

func (st *Store) sentencePath(hash string) string {
	return filepath.Join(st.dir, "sentences", hash[:2], hash+".json")
}

func (st *Store) GetSentences(hashes []string) map[string]SentenceData {
	out := map[string]SentenceData{}
	for _, h := range hashes {
		if !hashRe.MatchString(h) {
			continue
		}
		raw, err := os.ReadFile(st.sentencePath(h))
		if err != nil {
			continue
		}
		var d SentenceData
		if json.Unmarshal(raw, &d) == nil {
			out[h] = d
		}
	}
	return out
}

// PutSentences merges: a non-empty translation replaces the old one, a scanned phrase list replaces the old list,
// and glosses are merged by word form (a newer gloss, e.g. from "Improve", replaces the older one).
func (st *Store) PutSentences(items []SentenceData) error {
	st.mu.Lock()
	defer st.mu.Unlock()
	for _, in := range items {
		if !hashRe.MatchString(in.Hash) {
			continue
		}
		path := st.sentencePath(in.Hash)
		var cur SentenceData
		if raw, err := os.ReadFile(path); err == nil {
			_ = json.Unmarshal(raw, &cur)
		}
		cur.Hash = in.Hash
		if strings.TrimSpace(in.Translation) != "" {
			cur.Translation = strings.TrimSpace(in.Translation)
		}
		if in.Scanned {
			cur.Phrases, cur.Scanned = in.Phrases, true
		} else if len(in.Phrases) > 0 {
			for _, p := range in.Phrases {
				if !containsPhrase(cur.Phrases, p.Phrase) {
					cur.Phrases = append(cur.Phrases, p)
				}
			}
		}
		for form, g := range in.Glosses {
			if form == "" || len(form) > 100 || !json.Valid(g) {
				continue
			}
			if cur.Glosses == nil {
				cur.Glosses = map[string]json.RawMessage{}
			}
			cur.Glosses[form] = g
		}
		if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
			return err
		}
		raw, _ := json.Marshal(cur)
		if err := writeAtomic(path, raw); err != nil {
			return err
		}
	}
	return nil
}

func containsPhrase(list []Phrase, phrase string) bool {
	for _, p := range list {
		if strings.EqualFold(p.Phrase, phrase) {
			return true
		}
	}
	return false
}

func (st *Store) PutLesson(l Lesson) (Lesson, error) {
	st.mu.Lock()
	defer st.mu.Unlock()
	if l.ID == "" || !idRe.MatchString(l.ID) {
		raw := make([]byte, 8)
		_, _ = rand.Read(raw)
		l.ID = hex.EncodeToString(raw)
	}
	if l.CreatedAt == 0 {
		l.CreatedAt = time.Now().UnixMilli()
	}
	raw, _ := json.Marshal(l)
	return l, writeAtomic(filepath.Join(st.dir, "lessons", l.ID+".json"), raw)
}

func (st *Store) GetLesson(id string) (Lesson, bool) {
	var l Lesson
	if !idRe.MatchString(id) {
		return l, false
	}
	raw, err := os.ReadFile(filepath.Join(st.dir, "lessons", id+".json"))
	return l, err == nil && json.Unmarshal(raw, &l) == nil
}

func (st *Store) ListLessons() []LessonSummary {
	entries, _ := os.ReadDir(filepath.Join(st.dir, "lessons"))
	out := []LessonSummary{}
	for _, e := range entries {
		id := strings.TrimSuffix(e.Name(), ".json")
		if l, ok := st.GetLesson(id); ok {
			out = append(out, summarize(l))
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].CreatedAt > out[j].CreatedAt })
	return out
}

func (st *Store) DeleteLesson(id string) bool {
	if !idRe.MatchString(id) {
		return false
	}
	st.mu.Lock()
	defer st.mu.Unlock()
	return os.Remove(filepath.Join(st.dir, "lessons", id+".json")) == nil
}

func (s *Server) handleListLessons(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, s.store.ListLessons())
}

func (s *Server) handleGetLesson(w http.ResponseWriter, r *http.Request) {
	l, ok := s.store.GetLesson(r.PathValue("id"))
	if !ok {
		writeError(w, http.StatusNotFound, "no such lesson")
		return
	}
	writeJSON(w, l)
}

func (s *Server) handleDeleteLesson(w http.ResponseWriter, r *http.Request) {
	if !s.store.DeleteLesson(r.PathValue("id")) {
		writeError(w, http.StatusNotFound, "no such lesson")
		return
	}
	writeJSON(w, map[string]bool{"ok": true})
}

// handlePutLesson stores a shared lesson plus (optionally) the AI results its sender already has for its sentences.
func (s *Server) handlePutLesson(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Lesson
		Sentences []SentenceData `json:"sentences"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 16<<20)).Decode(&body); err != nil ||
		strings.TrimSpace(body.Text) == "" {
		writeError(w, http.StatusBadRequest, "bad request")
		return
	}
	body.Title = strings.TrimSpace(body.Title)
	if body.Title == "" {
		body.Title = "Untitled"
	}
	if err := s.store.PutSentences(body.Sentences); err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	l, err := s.store.PutLesson(body.Lesson)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, summarize(l))
}

func (s *Server) handleCacheGet(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Hashes []string `json:"hashes"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20)).Decode(&body); err != nil || len(body.Hashes) > 2000 {
		writeError(w, http.StatusBadRequest, "bad request")
		return
	}
	writeJSON(w, s.store.GetSentences(body.Hashes))
}

func (s *Server) handleCachePut(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Sentences []SentenceData `json:"sentences"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 16<<20)).Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "bad request")
		return
	}
	if err := s.store.PutSentences(body.Sentences); err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, map[string]bool{"ok": true})
}
