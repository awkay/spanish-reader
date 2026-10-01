package main

import (
	"bytes"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
)

const sentenceGapMs = 400

// TTS renders a page of sentences as one MP3 with per-sentence timings. Each sentence is synthesized once per voice
// (cached as WAV) by Piper; pages are concatenated PCM with short gaps, encoded by lame. Synthesis runs one page at a
// time, so Piper's memory (~200 MB with a 63 MB voice) is only used while a page is being made.
type TTS struct {
	piper, model, lame, dir string
	mu                      sync.Mutex
}

// Timing is [startMs, endMs] of one sentence within the page audio.
type Timing [2]int

func newTTS(piper, model, lame, dir string) *TTS {
	return &TTS{piper: piper, model: model, lame: lame, dir: dir}
}

func (t *TTS) Enabled() bool { return t.piper != "" && t.model != "" }

// Voice is the model's file name without extension, e.g. es_MX-claude-high.
func (t *TTS) Voice() string {
	return strings.TrimSuffix(filepath.Base(t.model), ".onnx")
}

func hashHex(parts ...string) string {
	h := sha256.New()
	for _, p := range parts {
		h.Write([]byte(p))
		h.Write([]byte{0})
	}
	return hex.EncodeToString(h.Sum(nil))
}

// Page returns the audio key (file name stem) and timings for sentences, synthesizing whatever isn't cached.
func (t *TTS) Page(sentences []string) (string, []Timing, error) {
	if !t.Enabled() {
		return "", nil, errors.New("text-to-speech is not configured on the server")
	}
	voice := t.Voice()
	keys := make([]string, len(sentences))
	for i, s := range sentences {
		keys[i] = hashHex(voice, strings.TrimSpace(s))
	}
	pageKey := hashHex(append([]string{voice}, keys...)...)[:32]
	pageDir := filepath.Join(t.dir, "pages")
	mp3 := filepath.Join(pageDir, pageKey+".mp3")
	meta := filepath.Join(pageDir, pageKey+".json")
	if timings, ok := readTimings(mp3, meta); ok {
		return pageKey, timings, nil
	}

	t.mu.Lock()
	defer t.mu.Unlock()
	if timings, ok := readTimings(mp3, meta); ok {
		return pageKey, timings, nil
	}
	wavDir := filepath.Join(t.dir, "sentences", voice)
	for _, d := range []string{wavDir, pageDir} {
		if err := os.MkdirAll(d, 0o700); err != nil {
			return "", nil, err
		}
	}
	if err := t.synthesizeMissing(sentences, keys, wavDir); err != nil {
		return "", nil, err
	}

	var pcm bytes.Buffer
	timings := make([]Timing, len(sentences))
	rate := 0
	for i, k := range keys {
		r, data, err := readWav(filepath.Join(wavDir, k+".wav"))
		if err != nil {
			return "", nil, fmt.Errorf("sentence %d: %w", i+1, err)
		}
		if rate == 0 {
			rate = r
		} else if r != rate {
			return "", nil, fmt.Errorf("sample rate changed from %d to %d", rate, r)
		}
		if i > 0 {
			pcm.Write(make([]byte, rate*sentenceGapMs/1000*2))
		}
		start := samplesToMs(pcm.Len()/2, rate)
		pcm.Write(data)
		timings[i] = Timing{start, samplesToMs(pcm.Len()/2, rate)}
	}
	if err := t.encode(pcm.Bytes(), rate, mp3); err != nil {
		return "", nil, err
	}
	raw, _ := json.Marshal(timings)
	if err := writeAtomic(meta, raw); err != nil {
		return "", nil, err
	}
	return pageKey, timings, nil
}

func samplesToMs(samples, rate int) int { return (samples*1000 + rate/2) / rate }

// synthesizeMissing runs Piper once for every sentence without a cached WAV (JSON-lines input, one file each).
func (t *TTS) synthesizeMissing(sentences, keys []string, wavDir string) error {
	tmp, err := os.MkdirTemp(wavDir, "tmp-")
	if err != nil {
		return err
	}
	defer os.RemoveAll(tmp)
	var input bytes.Buffer
	var pending []int
	seen := map[string]bool{}
	for i, k := range keys {
		if seen[k] {
			continue
		}
		seen[k] = true
		if _, err := os.Stat(filepath.Join(wavDir, k+".wav")); err == nil {
			continue
		}
		line, _ := json.Marshal(map[string]string{"text": strings.TrimSpace(sentences[i]), "output_file": filepath.Join(tmp, k+".wav")})
		input.Write(line)
		input.WriteByte('\n')
		pending = append(pending, i)
	}
	if len(pending) == 0 {
		return nil
	}
	cmd := exec.Command(t.piper, "--model", t.model, "--json-input", "--sentence_silence", "0")
	cmd.Stdin = &input
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	if err := cmd.Run(); err != nil {
		return fmt.Errorf("piper failed: %v: %s", err, truncate(stderr.String(), 300))
	}
	for _, i := range pending {
		k := keys[i]
		if err := os.Rename(filepath.Join(tmp, k+".wav"), filepath.Join(wavDir, k+".wav")); err != nil {
			return fmt.Errorf("piper produced no audio for sentence %d: %w", i+1, err)
		}
	}
	return nil
}

func (t *TTS) encode(pcm []byte, rate int, out string) error {
	tmp := out + ".tmp"
	khz := strconv.FormatFloat(float64(rate)/1000, 'f', -1, 64)
	cmd := exec.Command(t.lame, "--quiet", "-r", "-s", khz, "--bitwidth", "16", "--signed", "--little-endian",
		"-m", "m", "-b", "48", "-", tmp)
	cmd.Stdin = bytes.NewReader(pcm)
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	if err := cmd.Run(); err != nil {
		os.Remove(tmp)
		return fmt.Errorf("lame failed: %v: %s", err, truncate(stderr.String(), 300))
	}
	return os.Rename(tmp, out)
}

func readTimings(mp3, meta string) ([]Timing, bool) {
	if _, err := os.Stat(mp3); err != nil {
		return nil, false
	}
	raw, err := os.ReadFile(meta)
	if err != nil {
		return nil, false
	}
	var timings []Timing
	return timings, json.Unmarshal(raw, &timings) == nil
}

// readWav returns the sample rate and PCM data of a 16-bit mono WAV file.
func readWav(path string) (int, []byte, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return 0, nil, err
	}
	if len(b) < 12 || string(b[0:4]) != "RIFF" || string(b[8:12]) != "WAVE" {
		return 0, nil, errors.New("not a WAV file")
	}
	rate, channels, bits := 0, 0, 0
	for off := 12; off+8 <= len(b); {
		id := string(b[off : off+4])
		size := int(binary.LittleEndian.Uint32(b[off+4 : off+8]))
		body := off + 8
		if body+size > len(b) {
			size = len(b) - body
		}
		switch id {
		case "fmt ":
			channels = int(binary.LittleEndian.Uint16(b[body+2:]))
			rate = int(binary.LittleEndian.Uint32(b[body+4:]))
			bits = int(binary.LittleEndian.Uint16(b[body+14:]))
		case "data":
			if channels != 1 || bits != 16 || rate == 0 {
				return 0, nil, fmt.Errorf("unsupported WAV: %d channels, %d bits, %d Hz", channels, bits, rate)
			}
			return rate, b[body : body+size&^1], nil
		}
		off = body + size + size%2
	}
	return 0, nil, errors.New("WAV has no data chunk")
}

func writeAtomic(path string, data []byte) error {
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

var audioFile = regexp.MustCompile(`^[0-9a-f]{32}\.mp3$`)

func (s *Server) handleTTS(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Sentences []string `json:"sentences"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20)).Decode(&body); err != nil ||
		len(body.Sentences) == 0 || len(body.Sentences) > 300 {
		writeError(w, http.StatusBadRequest, "bad request")
		return
	}
	for _, s := range body.Sentences {
		if strings.TrimSpace(s) == "" || len(s) > 4000 {
			writeError(w, http.StatusBadRequest, "empty or oversized sentence")
			return
		}
	}
	key, timings, err := s.tts.Page(body.Sentences)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, map[string]any{"audio": "/api/audio/" + key + ".mp3", "timings": timings, "voice": s.tts.Voice()})
}

func (s *Server) handleAudio(w http.ResponseWriter, r *http.Request) {
	name := r.PathValue("file")
	if !audioFile.MatchString(name) {
		http.NotFound(w, r)
		return
	}
	w.Header().Set("Content-Type", "audio/mpeg")
	w.Header().Set("Cache-Control", "private, max-age=31536000, immutable")
	http.ServeFile(w, r, filepath.Join(s.tts.dir, "pages", name))
}
