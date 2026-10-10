package main

import (
	"bytes"
	"encoding/json"
	"io"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"strings"
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
	var got []map[string]any
	reply := `{"found":true,"title":"Parque  Los Colibríes","text":"Parque Los Colibríes\r\n\r\nBienvenidos al parque."}`
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body map[string]any
		_ = json.NewDecoder(r.Body).Decode(&body)
		got = append(got, body)
		answer, _ := json.Marshal(reply)
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
	// Responses API: the vision model, the text first, then each photo as a data URL, in order.
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
	content := got[1]["messages"].([]any)[1].(map[string]any)["content"].([]any)
	if len(content) != 2 || content[1].(map[string]any)["type"] != "image_url" {
		t.Fatalf("chat request: %v", got[1])
	}
	s.cfg.AIProtocol = "anthropic"
	postPhotos(t, ts, tok, nil, fakeJPEG)
	content = got[2]["messages"].([]any)[0].(map[string]any)["content"].([]any)
	src := content[0].(map[string]any)["source"].(map[string]any)
	if content[0].(map[string]any)["type"] != "image" || src["media_type"] != "image/jpeg" || content[1].(map[string]any)["type"] != "text" {
		t.Fatalf("anthropic request: %v", got[2])
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
	if len(got) != 4 {
		t.Fatalf("bad uploads must not reach the AI: %d calls", len(got))
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
	if _, err = parsePhotoResult("I can't read that."); err == nil {
		t.Fatal("no JSON must be an error")
	}
}
