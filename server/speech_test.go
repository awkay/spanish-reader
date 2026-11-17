package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestSpeechTranscribesAClip(t *testing.T) {
	var got struct{ model, language, format, prompt, file string }
	asr := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/audio/transcriptions" || r.Header.Get("Authorization") != "Bearer asr-key" {
			http.Error(w, "bad", 400)
			return
		}
		_ = r.ParseMultipartForm(1 << 20)
		got.model, got.language, got.format, got.prompt = r.FormValue("model"), r.FormValue("language"), r.FormValue("response_format"), r.FormValue("prompt")
		if fh := r.MultipartForm.File["file"]; len(fh) == 1 {
			f, _ := fh[0].Open()
			b, _ := io.ReadAll(f)
			got.file = fh[0].Filename + ":" + string(b)
		}
		fmt.Fprint(w, `{"text":" Ayer fui al store con mi familia. "}`)
	}))
	defer asr.Close()
	s, ts := testServer(t, "")
	s.cfg.ASRBaseURL, s.cfg.ASRKey, s.cfg.ASRModel, s.cfg.ASRSpeechModel = asr.URL+"/v1", "asr-key", "whisper", "gpt-transcribe"
	token := login(t, ts)

	text, status := postSpeech(t, ts, token, []byte("m4a-bytes"))
	if status != 200 || text != "Ayer fui al store con mi familia." {
		t.Fatalf("got %d %q", status, text)
	}
	if got.model != "gpt-transcribe" || got.language != "es" || got.format != "json" || got.prompt == "" || got.file != "speech.m4a:m4a-bytes" {
		t.Fatalf("request: %+v", got)
	}
	if _, status := postSpeech(t, ts, "", []byte("x")); status != 401 {
		t.Fatalf("without a token: %d", status)
	}
	if _, status := postSpeech(t, ts, token, bytes.Repeat([]byte("x"), maxSpeechBytes+1)); status != 413 {
		t.Fatalf("too long: %d", status)
	}
}

func TestSpeechNeedsASRConfigured(t *testing.T) {
	_, ts := testServer(t, "")
	if _, status := postSpeech(t, ts, login(t, ts), []byte("x")); status != 503 {
		t.Fatalf("got %d", status)
	}
}

func postSpeech(t *testing.T, ts *httptest.Server, token string, audio []byte) (string, int) {
	t.Helper()
	req, _ := http.NewRequest("POST", ts.URL+"/api/speech", bytes.NewReader(audio))
	req.Header.Set("Content-Type", "audio/mp4")
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var out map[string]string
	_ = json.NewDecoder(resp.Body).Decode(&out)
	return out["text"], resp.StatusCode
}
