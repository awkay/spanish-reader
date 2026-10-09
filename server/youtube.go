package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
)

// YouTube lessons: the server downloads a video's audio (yt-dlp), transcribes it with word timestamps (ASR),
// stores the transcript as a shared lesson, and later cuts page audio out of the original recording, so the
// clients play the real speaker with the same per-page MP3 + sentence timings they get from Piper.
//
// Files (media is kept forever):
//
//	youtube/media/<videoId>.mp3     the downloaded audio
//	youtube/<videoId>.json          title, duration and transcript (re-importing the video reuses it)
//	youtube/usage.json              speech-recognition minutes used today (SR_ASR_DAILY_MINUTES)
//
// Safety: only a validated 11-character video ID reaches yt-dlp, inside a URL the server builds itself, after "--",
// with --ignore-config and --no-playlist; commands run without a shell. Duration is checked before downloading.
// Jobs run one at a time and the queue is short.

const (
	chunkMaxMs   = 8 * 60 * 1000 // per transcription request (providers time out after ~60 s of processing)
	chunkMinMs   = 4 * 60 * 1000 // look for a pause after this much
	pagePadMs    = 150           // audio kept before the first and after the last word of a page
	maxQueuedYT  = 3
	silenceNoise = "-35dB"
)

var errNoRecording = errors.New("this video's audio is missing on the server")

var (
	videoIDRe = regexp.MustCompile(`^[A-Za-z0-9_-]{11}$`)
	jobIDRe   = regexp.MustCompile(`^[0-9a-f]{16}$`)
)

// parseYouTubeID accepts youtube.com/watch?v=, youtu.be/, /shorts/, /live/ and /embed/ links (http or https) and
// returns the video ID. Anything else is refused.
func parseYouTubeID(raw string) (string, error) {
	raw = strings.TrimSpace(raw)
	if !strings.Contains(raw, "://") {
		raw = "https://" + raw
	}
	u, err := url.Parse(raw)
	if err != nil || (u.Scheme != "https" && u.Scheme != "http") || u.User != nil || u.Port() != "" {
		return "", errors.New("not a YouTube link")
	}
	host := strings.TrimPrefix(strings.ToLower(u.Hostname()), "www.")
	var id string
	switch host {
	case "youtu.be":
		id = strings.Trim(u.Path, "/")
	case "youtube.com", "m.youtube.com", "music.youtube.com":
		parts := strings.Split(strings.Trim(u.Path, "/"), "/")
		switch {
		case len(parts) == 1 && parts[0] == "watch":
			id = u.Query().Get("v")
		case len(parts) == 2 && (parts[0] == "shorts" || parts[0] == "live" || parts[0] == "embed"):
			id = parts[1]
		}
	default:
		return "", errors.New("not a YouTube link")
	}
	if !videoIDRe.MatchString(id) {
		return "", errors.New("no video ID in that YouTube link")
	}
	return id, nil
}

func watchURL(id string) string { return "https://www.youtube.com/watch?v=" + id }

type YTJob struct {
	ID       string `json:"id"`
	VideoID  string `json:"videoId"`
	Status   string `json:"status"` // queued | downloading | transcribing | done | error
	Detail   string `json:"detail,omitempty"`
	Title    string `json:"title,omitempty"`
	LessonID string `json:"lessonId,omitempty"`
	Error    string `json:"error,omitempty"`
}

type ytRecord struct {
	VideoID    string     `json:"videoId"`
	Title      string     `json:"title"`
	DurationMs int        `json:"durationMs"`
	Transcript Transcript `json:"transcript"`
}

type YouTube struct {
	ytdlp, ffmpeg, cookies string
	maxMinutes, dailyMins  int
	asr                    *ASR
	store                  *Store
	dir, pagesDir          string
	now                    func() time.Time

	mu    sync.Mutex
	jobs  map[string]*YTJob
	queue chan *YTJob
	once  sync.Once
	cutMu sync.Mutex
}

func newYouTube(cfg *Config, store *Store, pagesDir string) *YouTube {
	return &YouTube{
		ytdlp: cfg.YtDlpBin, ffmpeg: cfg.FfmpegBin, cookies: cfg.YtDlpCookies,
		maxMinutes: cfg.YTMaxMinutes, dailyMins: cfg.ASRDailyMinutes,
		asr:   newASR(cfg.ASRBaseURL, cfg.ASRKey, cfg.ASRModel),
		store: store, dir: filepath.Join(cfg.DataDir, "youtube"), pagesDir: pagesDir,
		now: time.Now, jobs: map[string]*YTJob{}, queue: make(chan *YTJob, maxQueuedYT),
	}
}

func (y *YouTube) Enabled() bool { return y.asr.Enabled() && y.ytdlp != "" && y.ffmpeg != "" }

func (y *YouTube) Job(id string) (YTJob, bool) {
	y.mu.Lock()
	defer y.mu.Unlock()
	j, ok := y.jobs[id]
	if !ok {
		return YTJob{}, false
	}
	return *j, true
}

func (y *YouTube) update(j *YTJob, f func(*YTJob)) {
	y.mu.Lock()
	f(j)
	y.mu.Unlock()
}

// Start queues an import, or returns the running job / existing lesson for the same video.
func (y *YouTube) Start(videoID string) (YTJob, error) {
	if l, ok := y.store.FindVideo(videoID); ok {
		return y.finished(videoID, l), nil
	}
	y.mu.Lock()
	defer y.mu.Unlock()
	active := 0
	for _, j := range y.jobs {
		if j.Status == "done" || j.Status == "error" {
			continue
		}
		if j.VideoID == videoID {
			return *j, nil
		}
		active++
	}
	if active >= maxQueuedYT {
		return YTJob{}, errors.New("other videos are still being imported; try again in a few minutes")
	}
	j := &YTJob{ID: randomHex(8), VideoID: videoID, Status: "queued"}
	y.jobs[j.ID] = j
	y.queue <- j
	y.once.Do(func() { go y.worker() })
	return *j, nil
}

func (y *YouTube) finished(videoID string, l Lesson) YTJob {
	y.mu.Lock()
	defer y.mu.Unlock()
	j := &YTJob{ID: randomHex(8), VideoID: videoID, Status: "done", Title: l.Title, LessonID: l.ID}
	y.jobs[j.ID] = j
	return *j
}

func (y *YouTube) worker() {
	for j := range y.queue {
		lessonID, err := y.run(context.Background(), j)
		y.update(j, func(j *YTJob) {
			if err != nil {
				log.Printf("youtube %s: %v", j.VideoID, err)
				j.Status, j.Error, j.Detail = "error", err.Error(), ""
			} else {
				j.Status, j.LessonID, j.Detail = "done", lessonID, ""
			}
		})
	}
}

func (y *YouTube) recordPath(id string) string { return filepath.Join(y.dir, id+".json") }
func (y *YouTube) mediaPath(id string) string  { return filepath.Join(y.dir, "media", id+".mp3") }

func (y *YouTube) loadRecord(id string) (*ytRecord, bool) {
	if !videoIDRe.MatchString(id) {
		return nil, false
	}
	raw, err := os.ReadFile(y.recordPath(id))
	if err != nil {
		return nil, false
	}
	var rec ytRecord
	if json.Unmarshal(raw, &rec) != nil {
		return nil, false
	}
	if _, err := os.Stat(y.mediaPath(id)); err != nil {
		return nil, false
	}
	return &rec, true
}

func (y *YouTube) run(ctx context.Context, j *YTJob) (string, error) {
	rec, ok := y.loadRecord(j.VideoID)
	if !ok {
		var err error
		if rec, err = y.importVideo(ctx, j); err != nil {
			return "", err
		}
	}
	if l, ok := y.store.FindVideo(j.VideoID); ok {
		return l.ID, nil
	}
	l, err := y.store.PutLesson(Lesson{
		Title: rec.Title, Text: lessonText(&rec.Transcript), Source: "youtube", SourceURL: watchURL(rec.VideoID),
		VideoID: rec.VideoID, DurationSec: rec.DurationMs / 1000,
	})
	return l.ID, err
}

func (y *YouTube) importVideo(ctx context.Context, j *YTJob) (*ytRecord, error) {
	id := j.VideoID
	y.update(j, func(j *YTJob) { j.Status, j.Detail = "downloading", "Checking the video" })
	durSec, title, err := y.metadata(ctx, id)
	if err != nil {
		return nil, err
	}
	y.update(j, func(j *YTJob) { j.Title = title })
	if durSec <= 0 {
		return nil, errors.New("live streams and videos without a known length can't be imported")
	}
	minutes := (durSec + 59) / 60
	if minutes > y.maxMinutes {
		return nil, fmt.Errorf("the video is %d minutes long; the limit is %d", minutes, y.maxMinutes)
	}

	y.update(j, func(j *YTJob) { j.Detail = "Downloading the audio" })
	if err := os.MkdirAll(filepath.Join(y.dir, "media"), 0o700); err != nil {
		return nil, err
	}
	media := y.mediaPath(id)
	if _, err := os.Stat(media); err != nil {
		if err := y.download(ctx, id); err != nil {
			return nil, err
		}
	}

	work, err := os.MkdirTemp(y.dir, "work-")
	if err != nil {
		return nil, err
	}
	defer os.RemoveAll(work)
	silences, err := y.silences(ctx, media)
	if err != nil {
		return nil, err
	}
	// Charged only once the audio is here, so failed downloads don't use up the budget.
	if err := y.reserveMinutes(minutes); err != nil {
		return nil, err
	}
	bounds := planChunks(durSec*1000, silences)
	rec := &ytRecord{VideoID: id, Title: title, DurationMs: durSec * 1000}
	for c := 0; c+1 < len(bounds); c++ {
		y.update(j, func(j *YTJob) {
			j.Status, j.Detail = "transcribing", fmt.Sprintf("Transcribing part %d of %d", c+1, len(bounds)-1)
		})
		chunk := filepath.Join(work, fmt.Sprintf("chunk%03d.mp3", c))
		if err := y.cut(ctx, media, chunk, bounds[c], bounds[c+1]-bounds[c], "16000", "48k"); err != nil {
			return nil, err
		}
		t, err := y.asr.Transcribe(ctx, chunk)
		if err != nil {
			return nil, err
		}
		t.Shift(bounds[c])
		rec.Transcript.Segments = append(rec.Transcript.Segments, t.Segments...)
		rec.Transcript.Words = append(rec.Transcript.Words, t.Words...)
	}
	if len(rec.Transcript.Words) == 0 {
		return nil, errors.New("no speech was recognized in this video")
	}
	raw, _ := json.Marshal(rec)
	return rec, writeAtomic(y.recordPath(id), raw)
}

func (y *YouTube) ytdlpArgs(extra ...string) []string {
	// --no-cache-dir: the service's home is read-only (systemd ProtectHome).
	args := []string{"--ignore-config", "--no-playlist", "--no-warnings", "--no-progress", "--no-cache-dir"}
	if y.cookies != "" {
		args = append(args, "--cookies", y.cookies)
	}
	return append(args, extra...)
}

func (y *YouTube) metadata(ctx context.Context, id string) (int, string, error) {
	out, err := runTool(ctx, y.ytdlp, y.ytdlpArgs("--skip-download", "--print", "%(duration)s\t%(title)s", "--", watchURL(id))...)
	if err != nil {
		return 0, "", fmt.Errorf("couldn't read the video: %w", err)
	}
	line, _, _ := strings.Cut(strings.TrimSpace(out), "\n")
	dur, title, _ := strings.Cut(line, "\t")
	d, _ := strconv.ParseFloat(strings.TrimSpace(dur), 64)
	title = strings.TrimSpace(title)
	if title == "" || title == "NA" {
		title = "YouTube " + id
	}
	return int(d + 0.5), title, nil
}

func (y *YouTube) download(ctx context.Context, id string) error {
	args := []string{"-f", "bestaudio/best", "-x", "--audio-format", "mp3", "--audio-quality", "64K",
		"-o", filepath.Join(y.dir, "media", id+".%(ext)s")}
	if strings.ContainsRune(y.ffmpeg, filepath.Separator) {
		args = append(args, "--ffmpeg-location", y.ffmpeg)
	}
	args = append(args, "--", watchURL(id))
	if _, err := runTool(ctx, y.ytdlp, y.ytdlpArgs(args...)...); err != nil {
		return fmt.Errorf("download failed: %w", err)
	}
	if _, err := os.Stat(y.mediaPath(id)); err != nil {
		return errors.New("download produced no audio file")
	}
	return nil
}

var silenceEndRe = regexp.MustCompile(`silence_end: ([0-9.]+) \| silence_duration: ([0-9.]+)`)

// silences returns the midpoints (ms) of the pauses ffmpeg finds.
func (y *YouTube) silences(ctx context.Context, media string) ([]int, error) {
	cmd := exec.CommandContext(ctx, y.ffmpeg, "-nostdin", "-hide_banner", "-i", media,
		"-af", "silencedetect=noise="+silenceNoise+":d=0.3", "-f", "null", "-")
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	if err := cmd.Run(); err != nil {
		return nil, fmt.Errorf("ffmpeg failed: %v: %s", err, truncate(stderr.String(), 300))
	}
	var out []int
	for _, m := range silenceEndRe.FindAllStringSubmatch(stderr.String(), -1) {
		end, _ := strconv.ParseFloat(m[1], 64)
		dur, _ := strconv.ParseFloat(m[2], 64)
		out = append(out, int((end-dur/2)*1000))
	}
	return out, nil
}

// planChunks splits [0, total) at pauses into pieces of at most chunkMaxMs, preferring the last pause after
// chunkMinMs; with no such pause it cuts hard at chunkMaxMs. Returns the boundaries, starting with 0 and ending
// with total.
func planChunks(total int, silences []int) []int {
	bounds := []int{0}
	start := 0
	for total-start > chunkMaxMs {
		cut := start + chunkMaxMs
		for _, s := range silences {
			if s >= start+chunkMinMs && s <= start+chunkMaxMs {
				cut = s
			}
		}
		bounds = append(bounds, cut)
		start = cut
	}
	return append(bounds, total)
}

func msArg(ms int) string { return strconv.FormatFloat(float64(ms)/1000, 'f', 3, 64) }

// cut re-encodes [startMs, startMs+durMs) of media as mono MP3.
func (y *YouTube) cut(ctx context.Context, media, out string, startMs, durMs int, rate, bitrate string) error {
	tmp := out + ".tmp.mp3"
	_, err := runTool(ctx, y.ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
		"-ss", msArg(startMs), "-i", media, "-t", msArg(durMs), "-vn", "-ac", "1", "-ar", rate, "-b:a", bitrate, tmp)
	if err != nil {
		os.Remove(tmp)
		return fmt.Errorf("ffmpeg failed: %w", err)
	}
	return os.Rename(tmp, out)
}

func runTool(ctx context.Context, bin string, args ...string) (string, error) {
	cmd := exec.CommandContext(ctx, bin, args...)
	var stdout, stderr bytes.Buffer
	cmd.Stdout, cmd.Stderr = &stdout, &stderr
	if err := cmd.Run(); err != nil {
		return "", fmt.Errorf("%v: %s", err, truncate(strings.TrimSpace(stderr.String()), 300))
	}
	return stdout.String(), nil
}

// reserveMinutes charges a video's length against today's speech-recognition budget.
func (y *YouTube) reserveMinutes(minutes int) error {
	y.mu.Lock()
	defer y.mu.Unlock()
	path := filepath.Join(y.dir, "usage.json")
	var u struct {
		Day     string `json:"day"`
		Minutes int    `json:"minutes"`
	}
	if raw, err := os.ReadFile(path); err == nil {
		_ = json.Unmarshal(raw, &u)
	}
	today := y.now().UTC().Format("2006-01-02")
	if u.Day != today {
		u.Day, u.Minutes = today, 0
	}
	if u.Minutes+minutes > y.dailyMins {
		return fmt.Errorf("today's transcription limit (%d minutes) would be exceeded; %d minutes left", y.dailyMins, max(0, y.dailyMins-u.Minutes))
	}
	u.Minutes += minutes
	if err := os.MkdirAll(y.dir, 0o700); err != nil {
		return err
	}
	raw, _ := json.Marshal(u)
	return writeAtomic(path, raw)
}

// PageAudio cuts the span of a page's sentences out of the original audio and returns the audio key (served by
// /api/audio) and timings relative to the cut.
func (y *YouTube) PageAudio(ctx context.Context, videoID string, sentences []string) (string, []Timing, error) {
	rec, ok := y.loadRecord(videoID)
	if !ok {
		return "", nil, errNoRecording
	}
	abs, ok := alignSentences(rec.Transcript.Words, sentences)
	if !ok {
		return "", nil, errors.New("couldn't find this page in the recording")
	}
	start, end := -1, 0
	for _, t := range abs {
		if start < 0 || t[0] < start {
			start = t[0]
		}
		end = max(end, t[1])
	}
	start = max(0, start-pagePadMs)
	end = min(end+pagePadMs, rec.DurationMs)
	if end <= start {
		return "", nil, errors.New("couldn't find this page in the recording")
	}
	timings := make([]Timing, len(abs))
	for i, t := range abs {
		timings[i] = Timing{max(0, t[0]-start), max(0, t[1]-start)}
	}
	key := hashHex("youtube", videoID, strconv.Itoa(start), strconv.Itoa(end))[:32]
	out := filepath.Join(y.pagesDir, key+".mp3")
	if _, err := os.Stat(out); err == nil {
		return key, timings, nil
	}
	y.cutMu.Lock()
	defer y.cutMu.Unlock()
	if _, err := os.Stat(out); err == nil {
		return key, timings, nil
	}
	if err := os.MkdirAll(y.pagesDir, 0o700); err != nil {
		return "", nil, err
	}
	if err := y.cut(ctx, y.mediaPath(videoID), out, start, end-start, "44100", "64k"); err != nil {
		return "", nil, err
	}
	return key, timings, nil
}

func randomHex(n int) string {
	raw := make([]byte, n)
	_, _ = rand.Read(raw)
	return hex.EncodeToString(raw)
}

func (s *Server) handleYouTubeStart(w http.ResponseWriter, r *http.Request) {
	var body struct {
		URL string `json:"url"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096)).Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "bad request")
		return
	}
	id, err := parseYouTubeID(body.URL)
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	if !s.yt.Enabled() {
		writeError(w, http.StatusServiceUnavailable, "YouTube import is not configured on the server")
		return
	}
	job, err := s.yt.Start(id)
	if err != nil {
		writeError(w, http.StatusTooManyRequests, err.Error())
		return
	}
	writeJSON(w, job)
}

func (s *Server) handleYouTubeJob(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	job, ok := s.yt.Job(id)
	if !jobIDRe.MatchString(id) || !ok {
		writeError(w, http.StatusNotFound, "no such import")
		return
	}
	writeJSON(w, job)
}

// handleVideoAudio is /api/tts for a YouTube lesson: the same request and response, cut from the recording. It is
// keyed by video ID, not lesson ID, so a device's copy keeps its audio after the shared lesson is removed.
func (s *Server) handleVideoAudio(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Sentences []string `json:"sentences"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20)).Decode(&body); err != nil ||
		len(body.Sentences) == 0 || len(body.Sentences) > 300 {
		writeError(w, http.StatusBadRequest, "bad request")
		return
	}
	id := r.PathValue("video")
	if !videoIDRe.MatchString(id) {
		writeError(w, http.StatusNotFound, "no such video")
		return
	}
	key, timings, err := s.yt.PageAudio(r.Context(), id, body.Sentences)
	if errors.Is(err, errNoRecording) {
		writeError(w, http.StatusNotFound, err.Error())
		return
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, map[string]any{"audio": "/api/audio/" + key + ".mp3", "timings": timings, "voice": "original"})
}
