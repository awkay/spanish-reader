package main

import (
	"bytes"
	"encoding/json"
	"io"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
)

// Smallest valid-looking JPEG and PNG headers; http.DetectContentType only sniffs the start.
var (
	fakeJPEG = append([]byte{0xFF, 0xD8, 0xFF, 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 0}, make([]byte, 64)...)
	fakePNG  = append([]byte("\x89PNG\r\n\x1a\n"), make([]byte, 64)...)
)

func postPhotos(t *testing.T, ts *httptest.Server, token string, fields map[string]string, images ...[]byte) (*http.Response, map[string]any) {
	t.Helper()
	var buf bytes.Buffer
	mw := multipart.NewWriter(&buf)
	for k, v := range fields {
		_ = mw.WriteField(k, v)
	}
	for i, img := range images {
		fw, _ := mw.CreateFormFile("image", "p"+string(rune('0'+i))+".jpg")
		_, _ = fw.Write(img)
	}
	_ = mw.Close()
	req, _ := http.NewRequest("POST", ts.URL+"/api/image", &buf)
	req.Header.Set("Content-Type", mw.FormDataContentType())
	req.Header.Set("Authorization", "Bearer "+token)
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

func TestPhotoLesson(t *testing.T) {
	var mu sync.Mutex
	var got []map[string]any
	reply := `{"found":true,"title":"Parque  Los Colibríes","text":"Parque Los Colibríes\r\n\r\nBienvenidos al parque."}`
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body map[string]any
		_ = json.NewDecoder(r.Body).Decode(&body)
		mu.Lock()
		got = append(got, body)
		answer, _ := json.Marshal(reply)
		mu.Unlock()
		switch {
		case strings.HasSuffix(r.URL.Path, "/responses"):
			io.WriteString(w, `{"output":[{"type":"message","content":[{"type":"output_text","text":`+string(answer)+`}]}]}`)
		case strings.HasSuffix(r.URL.Path, "/chat/completions"):
			io.WriteString(w, `{"choices":[{"message":{"content":`+string(answer)+`}}]}`)
		default:
			io.WriteString(w, `{"content":[{"type":"text","text":`+string(answer)+`}]}`)
		}
	}))
	defer upstream.Close()
	s, ts := testServer(t, upstream.URL)
	s.cfg.VisionModel = "eyes"
	tok := login(t, ts)

	resp, out := postPhotos(t, ts, tok, map[string]string{"sharedBy": "Tony"}, fakeJPEG, fakePNG)
	if resp.StatusCode != 200 || out["title"] != "Parque Los Colibríes" || out["sharedBy"] != "Tony" || out["source"] != "photo" {
		t.Fatalf("photo lesson: %d %v", resp.StatusCode, out)
	}
	l, ok := s.store.GetLesson(out["id"].(string))
	if !ok || l.Text != "Parque Los Colibríes\n\nBienvenidos al parque." {
		t.Fatalf("stored lesson: %v %q", ok, l.Text)
	}
	// Three readings that agree: no reconciling call. Responses API: the vision model, high effort, the text first,
	// then each photo as a data URL, in order.
	if len(got) != photoCandidates {
		t.Fatalf("agreeing readings need %d calls, made %d", photoCandidates, len(got))
	}
	req := got[0]
	parts := req["input"].([]any)[0].(map[string]any)["content"].([]any)
	if req["model"] != "eyes" || req["reasoning"].(map[string]any)["effort"] != "high" || len(parts) != 3 || !strings.Contains(parts[0].(map[string]any)["text"].(string), "2 photos") ||
		!strings.HasPrefix(parts[1].(map[string]any)["image_url"].(string), "data:image/jpeg;base64,") ||
		!strings.HasPrefix(parts[2].(map[string]any)["image_url"].(string), "data:image/png;base64,") {
		t.Fatalf("responses request: %v", req)
	}

	// The title the user typed wins; chat and Anthropic requests carry the image too.
	s.cfg.AIProtocol = "chat"
	_, out = postPhotos(t, ts, tok, map[string]string{"title": " Mi letrero "}, fakeJPEG)
	if out["title"] != "Mi letrero" {
		t.Fatalf("typed title: %v", out)
	}
	content := got[3]["messages"].([]any)[1].(map[string]any)["content"].([]any)
	if len(content) != 2 || content[1].(map[string]any)["type"] != "image_url" {
		t.Fatalf("chat request: %v", got[3])
	}
	s.cfg.AIProtocol = "anthropic"
	postPhotos(t, ts, tok, nil, fakeJPEG)
	content = got[6]["messages"].([]any)[0].(map[string]any)["content"].([]any)
	src := content[0].(map[string]any)["source"].(map[string]any)
	if content[0].(map[string]any)["type"] != "image" || src["media_type"] != "image/jpeg" || content[1].(map[string]any)["type"] != "text" {
		t.Fatalf("anthropic request: %v", got[6])
	}

	// Nothing readable: no lesson.
	before := len(s.store.ListLessons())
	reply = `{"found":false}`
	resp, out = postPhotos(t, ts, tok, nil, fakeJPEG)
	if resp.StatusCode != 422 || !strings.Contains(out["error"].(string), "no Spanish text") || len(s.store.ListLessons()) != before {
		t.Fatalf("nothing found: %d %v", resp.StatusCode, out)
	}
	// Not an image, or no image at all.
	if resp, _ = postPhotos(t, ts, tok, nil, []byte("hello, not a picture")); resp.StatusCode != 400 {
		t.Fatalf("text upload: %d", resp.StatusCode)
	}
	if resp, _ = postPhotos(t, ts, tok, map[string]string{"title": "x"}); resp.StatusCode != 400 {
		t.Fatalf("no image: %d", resp.StatusCode)
	}
	if len(got) != 4*photoCandidates {
		t.Fatalf("bad uploads must not reach the AI: %d calls", len(got))
	}
}

// fakeReadings answers each reading with the next of readings (in arrival order) and a reconciling call with
// merged; it records the reconciling request's user text.
func fakeReadings(t *testing.T, merged string, readings ...string) (*httptest.Server, *[]string, *int) {
	t.Helper()
	var mu sync.Mutex
	var reconcile []string
	calls := 0
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body map[string]any
		_ = json.NewDecoder(r.Body).Decode(&body)
		mu.Lock()
		defer mu.Unlock()
		calls++
		answer := merged
		if strings.HasPrefix(body["instructions"].(string), "You check transcriptions") {
			parts := body["input"].([]any)[0].(map[string]any)["content"].([]any)
			reconcile = append(reconcile, parts[0].(map[string]any)["text"].(string))
		} else {
			answer, readings = readings[0], readings[1:]
		}
		if answer == "500" {
			w.WriteHeader(400)
			return
		}
		raw, _ := json.Marshal(answer)
		io.WriteString(w, `{"output":[{"type":"message","content":[{"type":"output_text","text":`+string(raw)+`}]}]}`)
	}))
	t.Cleanup(upstream.Close)
	return upstream, &reconcile, &calls
}

func TestPhotoReadingsThatDisagreeAreReconciled(t *testing.T) {
	a := `{"found":true,"title":"Corredor","text":"CORREDOR\n\nun relict o de vegetación"}`
	b := `{"found":true,"title":"Corredor","text":"un relictó de vegetación"}`
	c := `{"found":true,"title":"Corredor","text":"CORREDOR\nun relictо de vegetación"}` // Cyrillic о, folded before comparing
	upstream, reconcile, calls := fakeReadings(t, `{"found":true,"text":"CORREDOR\n\nun relicto de vegetación"}`, a, b, c)
	s, ts := testServer(t, upstream.URL)
	resp, out := postPhotos(t, ts, login(t, ts), nil, fakeJPEG)
	if resp.StatusCode != 200 || *calls != 4 || len(*reconcile) != 1 {
		t.Fatalf("reconcile: %d %v, %d calls", resp.StatusCode, out, *calls)
	}
	if l, _ := s.store.GetLesson(out["id"].(string)); l.Text != "CORREDOR\n\nun relicto de vegetación" || l.Title != "Corredor" {
		t.Fatalf("merged lesson: %q %q", l.Title, l.Text)
	}
	// The readings run in parallel, so their numbering follows arrival order.
	for _, want := range []string{"3 independent transcriptions", "--- Transcription 3 ---\n", "\nun relictó de vegetación\n", "relicto de", "relict o"} {
		if !strings.Contains((*reconcile)[0], want) {
			t.Fatalf("reconcile request lacks %q: %s", want, (*reconcile)[0])
		}
	}
}

func TestPhotoReadingsLayoutDifferencesAgreeAndFailuresDegrade(t *testing.T) {
	same1 := `{"found":true,"title":"T","text":"Hola\n\nmundo."}`
	same2 := `{"found":true,"title":"T","text":"Hola mundo."}`
	upstream, reconcile, calls := fakeReadings(t, "", same1, same2, "500")
	_, ts := testServer(t, upstream.URL)
	tok := login(t, ts)
	if resp, out := postPhotos(t, ts, tok, nil, fakeJPEG); resp.StatusCode != 200 || len(*reconcile) != 0 || *calls != 3 {
		t.Fatalf("layout-only differences and a failed reading: %d %v, %d calls", resp.StatusCode, out, *calls)
	}

	// Most readings found nothing: no lesson, even if one imagined some text.
	none := `{"found":false}`
	upstream2, _, _ := fakeReadings(t, "", none, `{"found":true,"text":"ALTO"}`, none)
	_, ts2 := testServer(t, upstream2.URL)
	if resp, _ := postPhotos(t, ts2, login(t, ts2), nil, fakeJPEG); resp.StatusCode != 422 {
		t.Fatalf("mostly nothing found: %d", resp.StatusCode)
	}

	// The reconciling call fails: the first reading is used rather than losing the photo.
	upstream3, reconcile3, _ := fakeReadings(t, "500", `{"found":true,"text":"uno"}`, `{"found":true,"text":"uno"}`, `{"found":true,"text":"una"}`)
	s3, ts3 := testServer(t, upstream3.URL)
	resp, out := postPhotos(t, ts3, login(t, ts3), nil, fakeJPEG)
	if resp.StatusCode != 200 || len(*reconcile3) != 1 {
		t.Fatalf("failed reconcile: %d %v", resp.StatusCode, out)
	}
	if l, _ := s3.store.GetLesson(out["id"].(string)); l.Text != "uno" && l.Text != "una" {
		t.Fatalf("fallback text: %q", l.Text)
	}

	// Every reading fails: the provider's error is reported.
	upstream4, _, _ := fakeReadings(t, "", "500", "500", "500")
	_, ts4 := testServer(t, upstream4.URL)
	if resp, out := postPhotos(t, ts4, login(t, ts4), nil, fakeJPEG); resp.StatusCode != 502 || !strings.Contains(out["error"].(string), "HTTP 400") {
		t.Fatalf("all failed: %d %v", resp.StatusCode, out)
	}
}

func TestParsePhotoResult(t *testing.T) {
	res, err := parsePhotoResult("```json\n{\"found\": true, \"title\": \"A\", \"text\": \"  Hola.  \"}\n```")
	if err != nil || !res.Found || res.Text != "Hola." {
		t.Fatalf("fenced: %v %v", res, err)
	}
	if res, _ = parsePhotoResult(`{"found": true, "text": "123 — 45"}`); res.Found {
		t.Fatal("text without letters counts as nothing found")
	}
	if res, _ = parsePhotoResult(`{"found": true, "title": "Ríо", "text": "un relictо de vegetación"}`); res.Text != "un relicto de vegetación" || res.Title != "Río" {
		t.Fatalf("Cyrillic look-alikes: %q %q", res.Text, res.Title)
	}
	if got := openingWords("Muchos años después, frente al pelotón de fusilamiento, el coronel\nAureliano", 50); got != "Muchos años después, frente al pelotón de…" {
		t.Fatalf("opening words: %q", got)
	}
	if got := openingWords("¡Gracias!\n\nOtra línea", 50); got != "¡Gracias!" {
		t.Fatalf("short first line: %q", got)
	}
	if _, err = parsePhotoResult("I can't read that."); err == nil {
		t.Fatal("no JSON must be an error")
	}
}
