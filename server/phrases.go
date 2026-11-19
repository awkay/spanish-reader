package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
)

// Say-it / listen sentences written by the AI, shared by everyone: one growing list per deck
// (<SR_DATA_DIR>/store/phrases/<deck>.json). A device that needs more writes a batch and appends it with the number
// of sentences it saw ("after"); if someone else appended first, the batch is dropped and the stored list returned,
// so ids (`<deck>~<n>`, numbered from 1) stay stable for everyone's scores. Cards are opaque JSON to the server.

var phraseDecks = map[string]bool{"questions": true, "verbs": true}

const (
	phraseBatchMax = 30
	phraseDeckMax  = 5000
)

func (st *Store) phrasesPath(deck string) string {
	return filepath.Join(st.dir, "phrases", deck+".json")
}

type phraseList struct {
	Deck  string            `json:"deck"`
	Cards []json.RawMessage `json:"cards"`
}

func (st *Store) loadPhrases(deck string) (phraseList, error) {
	out := phraseList{Deck: deck, Cards: []json.RawMessage{}}
	raw, err := os.ReadFile(st.phrasesPath(deck))
	if errors.Is(err, os.ErrNotExist) {
		return out, nil
	}
	if err != nil {
		return out, err
	}
	err = json.Unmarshal(raw, &out)
	if out.Cards == nil {
		out.Cards = []json.RawMessage{}
	}
	return out, err
}

func (s *Server) handlePhrasesGet(w http.ResponseWriter, r *http.Request) {
	deck := r.PathValue("deck")
	if !phraseDecks[deck] {
		writeError(w, http.StatusNotFound, "not found")
		return
	}
	s.store.mu.Lock()
	list, err := s.store.loadPhrases(deck)
	s.store.mu.Unlock()
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, list)
}

func (s *Server) handlePhrasesPut(w http.ResponseWriter, r *http.Request) {
	deck := r.PathValue("deck")
	if !phraseDecks[deck] {
		writeError(w, http.StatusNotFound, "not found")
		return
	}
	var body struct {
		After int               `json:"after"`
		Cards []json.RawMessage `json:"cards"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 256<<10)).Decode(&body); err != nil ||
		len(body.Cards) == 0 || len(body.Cards) > phraseBatchMax || body.After < 0 {
		writeError(w, http.StatusBadRequest, "bad request")
		return
	}
	for i, c := range body.Cards {
		var card struct {
			ID      string `json:"id"`
			English string `json:"english"`
			Spanish string `json:"spanish"`
			Answer  string `json:"answer"`
		}
		if json.Unmarshal(c, &card) != nil || card.ID != fmt.Sprintf("%s~%d", deck, body.After+i+1) ||
			card.English == "" || card.Spanish == "" || card.Answer == "" {
			writeError(w, http.StatusBadRequest, "bad card")
			return
		}
	}
	s.store.mu.Lock()
	defer s.store.mu.Unlock()
	list, err := s.store.loadPhrases(deck)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	if len(list.Cards) == body.After && len(list.Cards)+len(body.Cards) <= phraseDeckMax {
		list.Cards = append(list.Cards, body.Cards...)
		raw, _ := json.Marshal(list)
		path := s.store.phrasesPath(deck)
		if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
			writeError(w, http.StatusInternalServerError, err.Error())
			return
		}
		if err := writeAtomic(path, raw); err != nil {
			writeError(w, http.StatusInternalServerError, err.Error())
			return
		}
	}
	writeJSON(w, list)
}
