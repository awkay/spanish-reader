package main

import (
	"os"
	"testing"
)

func TestLoginNames(t *testing.T) {
	_, ts := testServer(t, "")
	resp, out := do(t, ts, "POST", "/api/login", "", map[string]string{"code": "482913", "username": " Tony "})
	if resp.StatusCode != 200 || out["user"] != "tony" {
		t.Fatalf("name is trimmed and lowercased: %d %v", resp.StatusCode, out)
	}
	_, sess := do(t, ts, "GET", "/api/session", out["token"].(string), nil)
	if sess["user"] != "tony" {
		t.Fatalf("session names the account: %v", sess)
	}
	for _, bad := range []string{"two words", "tony2", "josé", "a-b"} {
		if resp, _ := do(t, ts, "POST", "/api/login", "", map[string]string{"code": "482913", "username": bad}); resp.StatusCode != 400 {
			t.Errorf("%q: got %d, want 400", bad, resp.StatusCode)
		}
	}
}

func snap(word string) map[string]any {
	return map[string]any{"version": 1, "exportedAtMillis": 1, "lessons": []any{map[string]any{"title": "T", "text": word}},
		"vocab": []any{map[string]any{"form": word, "status": 1}}}
}

func TestSync(t *testing.T) {
	s, ts := testServer(t, "")
	named := func(user string) string {
		_, out := do(t, ts, "POST", "/api/login", "", map[string]string{"code": "482913", "username": user})
		return out["token"].(string)
	}
	tony, sabina := named("tony"), named("sabina")

	if resp, _ := do(t, ts, "GET", "/api/sync/meta", login(t, ts), nil); resp.StatusCode != 403 {
		t.Fatalf("a session without a name can't sync: %d", resp.StatusCode)
	}
	if _, out := do(t, ts, "GET", "/api/sync/meta", tony, nil); out["revision"] != 0.0 {
		t.Fatalf("nothing pushed yet: %v", out)
	}
	if resp, _ := do(t, ts, "PUT", "/api/sync", tony, map[string]any{"baseRevision": 0, "snapshot": map[string]any{"version": 2}}); resp.StatusCode != 400 {
		t.Fatalf("unknown snapshot version: %d", resp.StatusCode)
	}
	resp, out := do(t, ts, "PUT", "/api/sync", tony, map[string]any{"baseRevision": 0, "device": "Android", "snapshot": snap("hola")})
	if resp.StatusCode != 200 || out["revision"] != 1.0 || out["device"] != "Android" {
		t.Fatalf("first push: %d %v", resp.StatusCode, out)
	}
	// Another device that never pulled is still at revision 0: refused, with the current meta.
	resp, out = do(t, ts, "PUT", "/api/sync", tony, map[string]any{"baseRevision": 0, "device": "Web", "snapshot": snap("adiós")})
	if cur, _ := out["current"].(map[string]any); resp.StatusCode != 409 || cur["revision"] != 1.0 || cur["device"] != "Android" {
		t.Fatalf("stale push: %d %v", resp.StatusCode, out)
	}
	resp, out = do(t, ts, "PUT", "/api/sync", tony, map[string]any{"baseRevision": 0, "force": true, "device": "Web", "snapshot": snap("adiós")})
	if resp.StatusCode != 200 || out["revision"] != 2.0 {
		t.Fatalf("forced push: %d %v", resp.StatusCode, out)
	}
	_, out = do(t, ts, "GET", "/api/sync", tony, nil)
	if vocab := out["snapshot"].(map[string]any)["vocab"].([]any); out["revision"] != 2.0 || vocab[0].(map[string]any)["form"] != "adiós" {
		t.Fatalf("pull: %v", out)
	}
	if _, out := do(t, ts, "GET", "/api/sync", sabina, nil); out["revision"] != 0.0 || out["snapshot"] != nil {
		t.Fatalf("accounts are separate: %v", out)
	}
	// Older revisions are kept, up to syncKeep.
	for i := 2; i < 10; i++ {
		if resp, out := do(t, ts, "PUT", "/api/sync", tony, map[string]any{"baseRevision": i, "snapshot": snap("x")}); resp.StatusCode != 200 {
			t.Fatalf("push %d: %d %v", i, resp.StatusCode, out)
		}
	}
	for rev := 1; rev <= 9; rev++ {
		_, err := os.Stat(s.store.syncPath("tony", rev))
		if want := rev > 9-syncKeep; (err == nil) != want {
			t.Errorf("revision %d kept = %v, want %v", rev, err == nil, want)
		}
	}
}

func TestDrillCards(t *testing.T) {
	_, ts := testServer(t, "")
	tok := login(t, ts)
	if resp, _ := do(t, ts, "GET", "/api/drill/ir", tok, nil); resp.StatusCode != 404 {
		t.Fatalf("no cards yet: %d", resp.StatusCode)
	}
	first := map[string]any{"cards": []any{map[string]any{"id": "ir#1", "answer": "fuimos"}}}
	resp, out := do(t, ts, "PUT", "/api/drill/ir", tok, first)
	if resp.StatusCode != 200 || out["verb"] != "ir" {
		t.Fatalf("put: %d %v", resp.StatusCode, out)
	}
	second := map[string]any{"cards": []any{map[string]any{"id": "ir#1", "answer": "vamos"}}}
	_, out = do(t, ts, "PUT", "/api/drill/ir", tok, second)
	if c := out["cards"].([]any)[0].(map[string]any); c["answer"] != "fuimos" {
		t.Fatalf("the first set wins: %v", out)
	}
	_, out = do(t, ts, "GET", "/api/drill/ir", tok, nil)
	if c := out["cards"].([]any)[0].(map[string]any); c["answer"] != "fuimos" {
		t.Fatalf("get: %v", out)
	}
	for _, bad := range []string{"/api/drill/..%2Fx", "/api/drill/IR", "/api/drill/a"} {
		if resp, _ := do(t, ts, "PUT", bad, tok, first); resp.StatusCode != 404 {
			t.Errorf("%s: %d", bad, resp.StatusCode)
		}
	}
	if resp, _ := do(t, ts, "PUT", "/api/drill/oír", tok, map[string]any{"cards": []any{map[string]any{"id": "x"}}}); resp.StatusCode != 400 {
		t.Errorf("a card without an answer is refused: %d", resp.StatusCode)
	}
}
