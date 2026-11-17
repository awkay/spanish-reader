package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"mime/multipart"
	"net/http"
	"strings"
	"time"
)

// Speech input for the Android conversation mode: the phone records one thing the learner says (a short m4a clip)
// and POSTs the raw audio to /api/speech; the answer is {"text": "..."}. It uses the same OpenAI-compatible
// transcription endpoint and key as YouTube lessons (SR_ASR_*), with SR_ASR_SPEECH_MODEL if set. Clips are short, so
// they are not counted against the daily YouTube minutes; the size limit keeps each one under about a minute.

const maxSpeechBytes = 3 << 20

// speechPrompt steers the recognizer for a learner who mixes in English. Whisper-type models read the prompt as
// preceding text rather than instructions, so it is written as Spanglish of the kind expected.
const speechPrompt = "Un estudiante de español habla con un amigo. A veces mezcla palabras en inglés, like this, " +
	"y las dejamos en inglés: el weekend fui al store con mi friend."

func (s *Server) speechASR() *ASR {
	return newASR(s.cfg.ASRBaseURL, s.cfg.ASRKey, s.cfg.ASRSpeechModel)
}

func (s *Server) handleSpeech(w http.ResponseWriter, r *http.Request) {
	asr := s.speechASR()
	if !asr.Enabled() {
		writeError(w, http.StatusServiceUnavailable, "speech recognition is not configured on the server")
		return
	}
	audio, err := io.ReadAll(http.MaxBytesReader(w, r.Body, maxSpeechBytes))
	if err != nil {
		writeError(w, http.StatusRequestEntityTooLarge, "the recording is too long")
		return
	}
	if len(audio) == 0 {
		writeError(w, http.StatusBadRequest, "no audio")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 90*time.Second)
	defer cancel()
	text, err := asr.TranscribeText(ctx, speechFileName(r.Header.Get("Content-Type")), audio, speechPrompt)
	if err != nil {
		log.Printf("speech: %v", err)
		writeError(w, http.StatusBadGateway, err.Error())
		return
	}
	writeJSON(w, map[string]string{"text": text})
}

// speechFileName gives the upload a name whose extension tells the provider the format.
func speechFileName(contentType string) string {
	switch strings.TrimSpace(strings.SplitN(contentType, ";", 2)[0]) {
	case "audio/webm":
		return "speech.webm"
	case "audio/ogg":
		return "speech.ogg"
	case "audio/wav", "audio/x-wav":
		return "speech.wav"
	case "audio/mpeg":
		return "speech.mp3"
	default:
		return "speech.m4a"
	}
}

// TranscribeText is plain transcription of one short Spanish clip (no timestamps), retried once on 429/5xx/network
// errors.
func (a *ASR) TranscribeText(ctx context.Context, name string, audio []byte, prompt string) (string, error) {
	if !a.Enabled() {
		return "", errors.New("speech recognition is not configured on the server")
	}
	var lastErr error
	for attempt := 0; attempt < 2; attempt++ {
		if attempt > 0 {
			a.sleep(time.Second)
		}
		text, retry, err := a.textOnce(ctx, name, audio, prompt)
		if err == nil {
			return text, nil
		}
		lastErr = err
		if !retry || ctx.Err() != nil {
			break
		}
	}
	return "", lastErr
}

func (a *ASR) textOnce(ctx context.Context, name string, audio []byte, prompt string) (string, bool, error) {
	var body bytes.Buffer
	mw := multipart.NewWriter(&body)
	fw, _ := mw.CreateFormFile("file", name)
	fw.Write(audio)
	for _, kv := range [][2]string{{"model", a.model}, {"language", "es"}, {"response_format", "json"},
		{"temperature", "0"}, {"prompt", prompt}} {
		_ = mw.WriteField(kv[0], kv[1])
	}
	mw.Close()
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, a.base+"/audio/transcriptions", &body)
	if err != nil {
		return "", false, err
	}
	req.Header.Set("Content-Type", mw.FormDataContentType())
	req.Header.Set("Authorization", "Bearer "+a.key)
	resp, err := a.client.Do(req)
	if err != nil {
		return "", true, fmt.Errorf("speech recognition: %w", err)
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if resp.StatusCode != http.StatusOK {
		retry := resp.StatusCode == http.StatusTooManyRequests || resp.StatusCode >= 500
		return "", retry, fmt.Errorf("speech recognition: HTTP %d: %s", resp.StatusCode, truncate(string(raw), 300))
	}
	var out struct {
		Text string `json:"text"`
	}
	if err := json.Unmarshal(raw, &out); err != nil {
		return "", false, fmt.Errorf("speech recognition: unreadable response: %w", err)
	}
	return strings.TrimSpace(out.Text), false, nil
}
