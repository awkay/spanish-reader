package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// ASR is a client for an OpenAI-compatible /audio/transcriptions endpoint (OpenRouter, Groq, OpenAI) that returns
// word timestamps (response_format=verbose_json, timestamp_granularities[]=word). The endpoint and model come from
// SR_ASR_* settings; hosted model lineups change, so nothing is hard-coded.
type ASR struct {
	base, key, model string
	client           *http.Client
	sleep            func(time.Duration)
}

func newASR(base, key, model string) *ASR {
	return &ASR{base: strings.TrimRight(base, "/"), key: key, model: model,
		client: &http.Client{Timeout: 5 * time.Minute}, sleep: time.Sleep}
}

func (a *ASR) Enabled() bool { return a.base != "" && a.key != "" && a.model != "" }

// TimedWord is one recognized word with its time span in milliseconds.
type TimedWord struct {
	W string `json:"w"`
	S int    `json:"s"`
	E int    `json:"e"`
}

// Segment is one recognized phrase (Whisper segments carry the punctuation; words usually don't).
type Segment struct {
	Text string `json:"text"`
	S    int    `json:"s"`
	E    int    `json:"e"`
}

type Transcript struct {
	Segments []Segment   `json:"segments"`
	Words    []TimedWord `json:"words"`
}

// Shift moves every time by offsetMs (chunks are transcribed separately).
func (t *Transcript) Shift(offsetMs int) {
	for i := range t.Segments {
		t.Segments[i].S += offsetMs
		t.Segments[i].E += offsetMs
	}
	for i := range t.Words {
		t.Words[i].S += offsetMs
		t.Words[i].E += offsetMs
	}
}

// Transcribe sends one audio file (Spanish), retrying 429/5xx/network errors with backoff.
func (a *ASR) Transcribe(ctx context.Context, path string) (*Transcript, error) {
	if !a.Enabled() {
		return nil, errors.New("speech recognition is not configured on the server")
	}
	audio, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var lastErr error
	for attempt := 0; attempt < 3; attempt++ {
		if attempt > 0 {
			a.sleep(time.Duration(2<<attempt) * time.Second)
		}
		t, retry, err := a.once(ctx, filepath.Base(path), audio)
		if err == nil {
			return t, nil
		}
		lastErr = err
		if !retry || ctx.Err() != nil {
			break
		}
	}
	return nil, lastErr
}

func (a *ASR) once(ctx context.Context, name string, audio []byte) (*Transcript, bool, error) {
	var body bytes.Buffer
	mw := multipart.NewWriter(&body)
	fw, _ := mw.CreateFormFile("file", name)
	fw.Write(audio)
	for _, kv := range [][2]string{{"model", a.model}, {"language", "es"}, {"response_format", "verbose_json"},
		{"timestamp_granularities[]", "word"}, {"timestamp_granularities[]", "segment"}, {"temperature", "0"}} {
		_ = mw.WriteField(kv[0], kv[1])
	}
	mw.Close()
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, a.base+"/audio/transcriptions", &body)
	if err != nil {
		return nil, false, err
	}
	req.Header.Set("Content-Type", mw.FormDataContentType())
	req.Header.Set("Authorization", "Bearer "+a.key)
	resp, err := a.client.Do(req)
	if err != nil {
		return nil, true, fmt.Errorf("speech recognition: %w", err)
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 32<<20))
	if resp.StatusCode != http.StatusOK {
		retry := resp.StatusCode == http.StatusTooManyRequests || resp.StatusCode >= 500
		return nil, retry, fmt.Errorf("speech recognition: HTTP %d: %s", resp.StatusCode, truncate(string(raw), 300))
	}
	var out struct {
		Segments []struct {
			Start, End float64
			Text       string
		} `json:"segments"`
		Words []struct {
			Word       string
			Start, End float64
		} `json:"words"`
	}
	if err := json.Unmarshal(raw, &out); err != nil {
		return nil, false, fmt.Errorf("speech recognition: unreadable response: %w", err)
	}
	if len(out.Words) == 0 && len(out.Segments) > 0 {
		return nil, false, errors.New("speech recognition returned no word timestamps (the model must support timestamp_granularities=word)")
	}
	ms := func(s float64) int { return int(s*1000 + 0.5) }
	t := &Transcript{}
	for _, s := range out.Segments {
		if text := strings.TrimSpace(s.Text); text != "" {
			t.Segments = append(t.Segments, Segment{Text: text, S: ms(s.Start), E: ms(s.End)})
		}
	}
	for _, w := range out.Words {
		if word := strings.TrimSpace(w.Word); word != "" {
			t.Words = append(t.Words, TimedWord{W: word, S: ms(w.Start), E: ms(w.End)})
		}
	}
	return t, false, nil
}
