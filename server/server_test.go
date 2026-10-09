package main

import (
	"bytes"
	"encoding/binary"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

// The test binary doubles as fake piper / fake lame, selected by environment variables.
func TestMain(m *testing.M) {
	switch os.Getenv("SR_FAKE") {
	case "piper":
		fakePiper()
		return
	case "lame":
		fakeLame()
		return
	case "yt-dlp":
		fakeYtDlp()
		return
	case "ffmpeg":
		fakeFfmpeg()
		return
	}
	os.Exit(m.Run())
}

// fakePiper writes a 22050 Hz mono WAV per JSON line whose length is 10 ms per character of text.
func fakePiper() {
	dec := json.NewDecoder(os.Stdin)
	for {
		var line struct {
			Text       string `json:"text"`
			OutputFile string `json:"output_file"`
		}
		if dec.Decode(&line) != nil {
			return
		}
		samples := len([]rune(line.Text)) * 220
		_ = os.WriteFile(line.OutputFile, wav(22050, make([]byte, samples*2)), 0o600)
	}
}

// fakeLame copies stdin (raw PCM) to the output file named by the last argument.
func fakeLame() {
	data, _ := io.ReadAll(os.Stdin)
	_ = os.WriteFile(os.Args[len(os.Args)-1], data, 0o600)
}

func wav(rate int, pcm []byte) []byte {
	var b bytes.Buffer
	b.WriteString("RIFF")
	_ = binary.Write(&b, binary.LittleEndian, uint32(36+len(pcm)))
	b.WriteString("WAVEfmt ")
	for _, v := range []any{uint32(16), uint16(1), uint16(1), uint32(rate), uint32(rate * 2), uint16(2), uint16(16)} {
		_ = binary.Write(&b, binary.LittleEndian, v)
	}
	b.WriteString("data")
	_ = binary.Write(&b, binary.LittleEndian, uint32(len(pcm)))
	b.Write(pcm)
	return b.Bytes()
}

func fakeTool(t *testing.T, name string) string {
	t.Helper()
	exe, _ := os.Executable()
	script := filepath.Join(t.TempDir(), name)
	content := "#!/bin/sh\nSR_FAKE=" + name + " exec " + exe + " \"$@\"\n"
	if err := os.WriteFile(script, []byte(content), 0o755); err != nil {
		t.Fatal(err)
	}
	return script
}

func testServer(t *testing.T, aiURL string) (*Server, *httptest.Server) {
	t.Helper()
	dir := t.TempDir()
	cfg := &Config{
		DataDir: dir, AccessCode: "482913", Secret: []byte("test-secret"),
		AIProtocol: "responses", AIBaseURL: aiURL, AIKey: "k", AIModel: "m", AIImproveModel: "big",
		PiperBin: fakeTool(t, "piper"), PiperModel: "/voices/es_MX-test.onnx", LameBin: fakeTool(t, "lame"),
	}
	s, err := newServer(cfg)
	if err != nil {
		t.Fatal(err)
	}
	s.gate.sleep = func(time.Duration) {}
	s.ai.sleep = func(time.Duration) {}
	ts := httptest.NewServer(s.routes())
	t.Cleanup(ts.Close)
	return s, ts
}

func do(t *testing.T, ts *httptest.Server, method, path, token string, body any) (*http.Response, map[string]any) {
	t.Helper()
	var r io.Reader
	if body != nil {
		raw, _ := json.Marshal(body)
		r = bytes.NewReader(raw)
	}
	req, _ := http.NewRequest(method, ts.URL+path, r)
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	var out map[string]any
	_ = json.Unmarshal(raw, &out)
	return resp, out
}

func login(t *testing.T, ts *httptest.Server) string {
	t.Helper()
	resp, out := do(t, ts, "POST", "/api/login", "", map[string]string{"code": "482913"})
	if resp.StatusCode != 200 {
		t.Fatalf("login failed: %d %v", resp.StatusCode, out)
	}
	return out["token"].(string)
}

func TestGateDelaysSerializesAndLocksOut(t *testing.T) {
	g := newGate("123456", []byte("s"))
	var mu sync.Mutex
	var slept []time.Duration
	inside := 0
	g.sleep = func(d time.Duration) {
		mu.Lock()
		inside++
		if inside > 1 {
			t.Error("two attempts were checked at the same time")
		}
		slept = append(slept, d)
		mu.Unlock()
		time.Sleep(time.Millisecond)
		mu.Lock()
		inside--
		mu.Unlock()
	}
	var wg sync.WaitGroup
	for i := 0; i < 5; i++ {
		wg.Add(1)
		go func() { defer wg.Done(); g.Check("000000") }()
	}
	wg.Wait()
	if len(slept) != 5 || slept[0] != 2*time.Second {
		t.Fatalf("each wrong code should cost 2s: %v", slept)
	}
	if _, ok, _ := g.Check(" 123456 "); !ok {
		t.Fatal("right code (with stray spaces) should pass")
	}
	for i := 0; i < lockoutFailures; i++ {
		g.Check("999999")
	}
	if _, ok, locked := g.Check("123456"); ok || !locked {
		t.Fatal("after too many failures even the right code is refused")
	}
	// The window passes.
	g.now = func() time.Time { return time.Now().Add(2 * lockoutWindow) }
	if _, ok, _ := g.Check("123456"); !ok {
		t.Fatal("lockout should expire")
	}
}

func TestTokens(t *testing.T) {
	g := newGate("123456", []byte("s"))
	tok, ok, _ := g.Check("123456")
	if !ok || !g.Valid(tok) {
		t.Fatal("issued token should be valid")
	}
	if g.Valid(tok+"x") || g.Valid("garbage") || g.Valid("") {
		t.Fatal("tampered tokens must be rejected")
	}
	if newGate("654321", []byte("s")).Valid(tok) {
		t.Fatal("changing the access code invalidates sessions")
	}
	g.now = func() time.Time { return time.Now().Add(sessionLifetime + time.Hour) }
	if g.Valid(tok) {
		t.Fatal("expired token must be rejected")
	}
}

func TestEverythingButLoginNeedsAuth(t *testing.T) {
	_, ts := testServer(t, "")
	for _, p := range [][2]string{{"GET", "/api/session"}, {"POST", "/api/ai"}, {"POST", "/api/tts"}, {"GET", "/api/lessons"},
		{"POST", "/api/lessons"}, {"POST", "/api/cache/get"}, {"POST", "/api/cache/put"}, {"GET", "/api/audio/0123456789abcdef0123456789abcdef.mp3"}} {
		resp, _ := do(t, ts, p[0], p[1], "", nil)
		if resp.StatusCode != 401 {
			t.Errorf("%s %s: got %d, want 401", p[0], p[1], resp.StatusCode)
		}
	}
	resp, _ := do(t, ts, "POST", "/api/login", "", map[string]string{"code": "111111"})
	if resp.StatusCode != 401 {
		t.Fatalf("wrong code: %d", resp.StatusCode)
	}
	resp, _ = do(t, ts, "POST", "/api/login", "", map[string]string{"code": "482913"})
	if c := resp.Cookies(); len(c) != 1 || c[0].Name != sessionCookie || !c[0].HttpOnly || !c[0].Secure {
		t.Fatalf("login should set a secure HttpOnly cookie: %v", c)
	}
	resp, _ = do(t, ts, "POST", "/api/logout", "", nil)
	if c := resp.Cookies(); len(c) != 1 || c[0].MaxAge >= 0 {
		t.Fatalf("logout should expire the cookie: %v", c)
	}
	resp, out := do(t, ts, "GET", "/api/session", login(t, ts), nil)
	if resp.StatusCode != 200 || out["voice"] != "es_MX-test" {
		t.Fatalf("session: %d %v", resp.StatusCode, out)
	}
}

func TestAIProxyProtocols(t *testing.T) {
	var got []map[string]any
	var paths, auths []string
	fail := 1
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if fail > 0 {
			fail--
			w.WriteHeader(503)
			return
		}
		var body map[string]any
		_ = json.NewDecoder(r.Body).Decode(&body)
		got = append(got, body)
		paths = append(paths, r.URL.Path)
		auths = append(auths, r.Header.Get("Authorization")+r.Header.Get("x-api-key"))
		switch {
		case strings.HasSuffix(r.URL.Path, "/responses"):
			io.WriteString(w, `{"output":[{"type":"reasoning","content":[{"type":"reasoning_text","text":"hmm"}]},{"type":"message","content":[{"type":"output_text","text":"{\"a\":1}"}]}]}`)
		case strings.HasSuffix(r.URL.Path, "/chat/completions"):
			io.WriteString(w, `{"choices":[{"message":{"content":"{\"b\":2}"}}]}`)
		default:
			io.WriteString(w, `{"content":[{"type":"thinking","thinking":"x"},{"type":"text","text":"{\"c\":3}"}]}`)
		}
	}))
	defer upstream.Close()
	s, ts := testServer(t, upstream.URL+"/api/v1")
	tok := login(t, ts)

	resp, out := do(t, ts, "POST", "/api/ai", tok, map[string]any{"system": "S", "user": "U", "improve": true})
	if resp.StatusCode != 200 || out["text"] != `{"a":1}` {
		t.Fatalf("responses: %d %v", resp.StatusCode, out)
	}
	if paths[0] != "/api/v1/responses" || got[0]["model"] != "big" || got[0]["instructions"] != "S" || auths[0] != "Bearer k" {
		t.Fatalf("responses request: %s %v %s", paths[0], got[0], auths[0])
	}

	s.cfg.AIProtocol = "chat"
	_, out = do(t, ts, "POST", "/api/ai", tok, map[string]any{"system": "S", "user": "U"})
	if out["text"] != `{"b":2}` || got[1]["model"] != "m" || paths[1] != "/api/v1/chat/completions" {
		t.Fatalf("chat: %v %v", out, got[1])
	}

	s.cfg.AIProtocol = "anthropic"
	_, out = do(t, ts, "POST", "/api/ai", tok, map[string]any{"system": "S", "user": "U", "items": 10})
	if out["text"] != `{"c":3}` || paths[2] != "/api/v1/v1/messages" || auths[2] != "k" || got[2]["max_tokens"].(float64) != 6000 {
		t.Fatalf("anthropic: %v %v %s", out, got[2], paths[2])
	}
}

func TestAIProxyReportsProviderErrors(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(401)
		io.WriteString(w, `{"error":"bad key"}`)
	}))
	defer upstream.Close()
	_, ts := testServer(t, upstream.URL)
	resp, out := do(t, ts, "POST", "/api/ai", login(t, ts), map[string]any{"system": "S", "user": "U"})
	if resp.StatusCode != 502 || !strings.Contains(out["error"].(string), "HTTP 401") {
		t.Fatalf("got %d %v", resp.StatusCode, out)
	}
}

func TestTTSPageTimingsAndCaching(t *testing.T) {
	s, ts := testServer(t, "")
	tok := login(t, ts)
	sentences := []string{"Hola.", "¿Cómo estás tú?"}
	resp, out := do(t, ts, "POST", "/api/tts", tok, map[string]any{"sentences": sentences})
	if resp.StatusCode != 200 {
		t.Fatalf("tts: %d %v", resp.StatusCode, out)
	}
	timings := out["timings"].([]any)
	first, second := timings[0].([]any), timings[1].([]any)
	// ~10 ms (220 samples) per character; 400 ms gap between sentences; rounded to the nearest ms.
	if first[0].(float64) != 0 || first[1].(float64) != 50 || second[0].(float64) != 450 || second[1].(float64) != 600 {
		t.Fatalf("timings: %v", timings)
	}
	audio := out["audio"].(string)
	req, _ := http.NewRequest("GET", ts.URL+audio, nil)
	req.Header.Set("Authorization", "Bearer "+tok)
	r2, _ := http.DefaultClient.Do(req)
	data, _ := io.ReadAll(r2.Body)
	r2.Body.Close()
	if r2.StatusCode != 200 || r2.Header.Get("Content-Type") != "audio/mpeg" || len(data) != (1100+8820+3300)*2 {
		t.Fatalf("audio: %d %s %d bytes", r2.StatusCode, r2.Header.Get("Content-Type"), len(data))
	}
	// A second page reusing a sentence only synthesizes the new one; the same page is served from cache.
	s.tts.piper = "/nonexistent"
	if _, out = do(t, ts, "POST", "/api/tts", tok, map[string]any{"sentences": sentences}); out["audio"] != audio {
		t.Fatalf("cached page should not need piper: %v", out)
	}
	if resp, _ = do(t, ts, "POST", "/api/tts", tok, map[string]any{"sentences": []string{"Hola.", "Nuevo."}}); resp.StatusCode != 500 {
		t.Fatalf("new sentence with a broken piper must fail, got %d", resp.StatusCode)
	}
	if resp, _ = do(t, ts, "POST", "/api/tts", tok, map[string]any{"sentences": []string{" "}}); resp.StatusCode != 400 {
		t.Fatalf("blank sentence: %d", resp.StatusCode)
	}
}

func TestCountWords(t *testing.T) {
	for text, want := range map[string]int{"Hola. Adiós.": 2, "rock'n'roll y bien-estar": 3, "¿Qué tal?  3 veces": 3, "": 0} {
		if got := countWords(text); got != want {
			t.Errorf("countWords(%q) = %d, want %d", text, got, want)
		}
	}
}

func TestLessonsAndSentenceCache(t *testing.T) {
	_, ts := testServer(t, "")
	tok := login(t, ts)
	h1 := strings.Repeat("a", 64)
	h2 := strings.Repeat("b", 64)
	resp, out := do(t, ts, "POST", "/api/lessons", tok, map[string]any{
		"title": "Cuento", "text": "Hola. Adiós.", "sharedBy": "Tony",
		"sentences": []map[string]any{{"hash": h1, "translation": "Hello.", "scanned": true, "phrases": []map[string]string{},
			"glosses": map[string]any{"hola": map[string]string{"meaningInContext": "hello"}}}},
	})
	if resp.StatusCode != 200 {
		t.Fatalf("put lesson: %d %v", resp.StatusCode, out)
	}
	id := out["id"].(string)
	req, _ := http.NewRequest("GET", ts.URL+"/api/lessons", nil)
	req.Header.Set("Authorization", "Bearer "+tok)
	r, _ := http.DefaultClient.Do(req)
	var list []LessonSummary
	_ = json.NewDecoder(r.Body).Decode(&list)
	r.Body.Close()
	if len(list) != 1 || list[0].Title != "Cuento" || list[0].Words != 2 || list[0].SharedBy != "Tony" {
		t.Fatalf("list: %v", list)
	}
	_, l := do(t, ts, "GET", "/api/lessons/"+id, tok, nil)
	if l["text"] != "Hola. Adiós." {
		t.Fatalf("get: %v", l)
	}

	// Merge: improved gloss replaces, new gloss is added, translation kept, phrases added.
	do(t, ts, "POST", "/api/cache/put", tok, map[string]any{"sentences": []map[string]any{
		{"hash": h1, "glosses": map[string]any{"hola": map[string]string{"meaningInContext": "hi (better)"}, "adiós": map[string]string{"meaningInContext": "bye"}},
			"phrases": []map[string]string{{"phrase": "a lo mejor", "meaning": "maybe"}}},
		{"hash": "not-a-hash", "translation": "ignored"},
	}})
	_, got := do(t, ts, "POST", "/api/cache/get", tok, map[string]any{"hashes": []string{h1, h2}})
	d := got[h1].(map[string]any)
	gl := d["glosses"].(map[string]any)
	if d["translation"] != "Hello." || gl["hola"].(map[string]any)["meaningInContext"] != "hi (better)" || gl["adiós"] == nil ||
		len(d["phrases"].([]any)) != 1 || got[h2] != nil {
		t.Fatalf("cache: %v", got)
	}
	if resp, _ = do(t, ts, "DELETE", "/api/lessons/"+id, tok, nil); resp.StatusCode != 200 {
		t.Fatal("delete")
	}
	if resp, _ = do(t, ts, "GET", "/api/lessons/"+id, tok, nil); resp.StatusCode != 404 {
		t.Fatal("deleted lesson should be gone")
	}
	if resp, _ = do(t, ts, "GET", "/api/lessons/..%2f..%2fsecret", tok, nil); resp.StatusCode != 404 {
		t.Fatal("path traversal must not work")
	}
}
