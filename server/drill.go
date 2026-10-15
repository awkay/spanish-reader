package main

import (
	"encoding/json"
	"errors"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
)

// Verb drill cards: each common verb's set of cards is written once by the first device that needs it and then shared
// by everyone (<SR_DATA_DIR>/store/drill/<verb>.json). Cards are opaque JSON to the server; progress stays on devices.

var drillVerbRe = regexp.MustCompile(`^[a-záéíóúñü]{2,30}$`)

const drillMaxBytes = 256 << 10

func (st *Store) drillPath(verb string) string { return filepath.Join(st.dir, "drill", verb+".json") }

func (s *Server) handleDrillGet(w http.ResponseWriter, r *http.Request) {
	verb := r.PathValue("verb")
	if !drillVerbRe.MatchString(verb) {
		writeError(w, http.StatusNotFound, "not found")
		return
	}
	raw, err := os.ReadFile(s.store.drillPath(verb))
	if errors.Is(err, os.ErrNotExist) {
		writeError(w, http.StatusNotFound, "no cards yet")
		return
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	_, _ = w.Write(raw)
}

// handleDrillPut stores a verb's cards unless it already has some (the first set wins, so card ids stay stable for
// everyone's progress); either way it answers with the stored set.
func (s *Server) handleDrillPut(w http.ResponseWriter, r *http.Request) {
	verb := r.PathValue("verb")
	if !drillVerbRe.MatchString(verb) {
		writeError(w, http.StatusNotFound, "not found")
		return
	}
	var body struct {
		Cards []json.RawMessage `json:"cards"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, drillMaxBytes)).Decode(&body); err != nil || len(body.Cards) == 0 || len(body.Cards) > 50 {
		writeError(w, http.StatusBadRequest, "bad request")
		return
	}
	for _, c := range body.Cards {
		var card struct {
			ID     string `json:"id"`
			Answer string `json:"answer"`
		}
		if json.Unmarshal(c, &card) != nil || card.ID == "" || card.Answer == "" {
			writeError(w, http.StatusBadRequest, "bad card")
			return
		}
	}
	s.store.mu.Lock()
	defer s.store.mu.Unlock()
	path := s.store.drillPath(verb)
	if _, err := os.Stat(path); errors.Is(err, os.ErrNotExist) {
		raw, _ := json.Marshal(map[string]any{"verb": verb, "cards": body.Cards})
		if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
			writeError(w, http.StatusInternalServerError, err.Error())
			return
		}
		if err := writeAtomic(path, raw); err != nil {
			writeError(w, http.StatusInternalServerError, err.Error())
			return
		}
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_, _ = w.Write(raw)
}
