package main

import (
	"fmt"
	"testing"
)

func phraseCards(deck string, from, n int) []map[string]string {
	out := []map[string]string{}
	for i := from; i < from+n; i++ {
		out = append(out, map[string]string{"id": fmt.Sprintf("%s~%d", deck, i), "english": "E", "spanish": "¿___?", "answer": "Qué"})
	}
	return out
}

func TestPhrasesAppendInOrder(t *testing.T) {
	_, ts := testServer(t, "")
	token := login(t, ts)
	resp, out := do(t, ts, "GET", "/api/phrases/questions", token, nil)
	if resp.StatusCode != 200 || len(out["cards"].([]any)) != 0 {
		t.Fatalf("empty deck: %d %v", resp.StatusCode, out)
	}
	resp, out = do(t, ts, "PUT", "/api/phrases/questions", token, map[string]any{"after": 0, "cards": phraseCards("questions", 1, 3)})
	if resp.StatusCode != 200 || len(out["cards"].([]any)) != 3 {
		t.Fatalf("first batch: %d %v", resp.StatusCode, out)
	}
	// Another device also saw 0 cards and wrote its own batch: dropped, the stored list comes back.
	_, out = do(t, ts, "PUT", "/api/phrases/questions", token, map[string]any{"after": 0, "cards": phraseCards("questions", 1, 2)})
	if len(out["cards"].([]any)) != 3 {
		t.Fatalf("stale batch was stored: %v", out)
	}
	_, out = do(t, ts, "PUT", "/api/phrases/questions", token, map[string]any{"after": 3, "cards": phraseCards("questions", 4, 2)})
	cards := out["cards"].([]any)
	if len(cards) != 5 || cards[4].(map[string]any)["id"] != "questions~5" {
		t.Fatalf("second batch: %v", out)
	}
	_, out = do(t, ts, "GET", "/api/phrases/questions", token, nil)
	if len(out["cards"].([]any)) != 5 {
		t.Fatalf("get: %v", out)
	}
	_, out = do(t, ts, "GET", "/api/phrases/verbs", token, nil)
	if len(out["cards"].([]any)) != 0 {
		t.Fatalf("decks are separate: %v", out)
	}
}

func TestPhrasesRejectBadInput(t *testing.T) {
	_, ts := testServer(t, "")
	token := login(t, ts)
	for name, body := range map[string]map[string]any{
		"wrong ids":   {"after": 0, "cards": phraseCards("questions", 2, 1)},
		"other deck":  {"after": 0, "cards": phraseCards("verbs", 1, 1)},
		"empty":       {"after": 0, "cards": []any{}},
		"too many":    {"after": 0, "cards": phraseCards("questions", 1, phraseBatchMax+1)},
		"no answer":   {"after": 0, "cards": []any{map[string]string{"id": "questions~1", "english": "E", "spanish": "S"}}},
		"negative at": {"after": -1, "cards": phraseCards("questions", 0, 1)},
	} {
		if resp, _ := do(t, ts, "PUT", "/api/phrases/questions", token, body); resp.StatusCode != 400 {
			t.Errorf("%s: got %d", name, resp.StatusCode)
		}
	}
	if resp, _ := do(t, ts, "GET", "/api/phrases/nope", token, nil); resp.StatusCode != 404 {
		t.Errorf("unknown deck: %d", resp.StatusCode)
	}
	if resp, _ := do(t, ts, "GET", "/api/phrases/questions", "", nil); resp.StatusCode != 401 {
		t.Errorf("no token: %d", resp.StatusCode)
	}
}
