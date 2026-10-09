package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"
)

// fakeYtDlp logs its arguments to $SR_FAKE_LOG, prints "$SR_FAKE_DURATION\tTitle" for --print, and otherwise
// writes a fake MP3 where -o points.
func fakeYtDlp() {
	if log := os.Getenv("SR_FAKE_LOG"); log != "" {
		f, _ := os.OpenFile(log, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o600)
		fmt.Fprintln(f, strings.Join(os.Args[1:], " "))
		f.Close()
	}
	args := os.Args[1:]
	for i, a := range args {
		if a == "--skip-download" {
			fmt.Printf("%s\tCharla de prueba\n", os.Getenv("SR_FAKE_DURATION"))
			return
		}
		if a == "-o" {
			_ = os.WriteFile(strings.ReplaceAll(args[i+1], "%(ext)s", "mp3"), []byte("media"), 0o600)
		}
	}
}

// fakeFfmpeg reports pauses at 250 s, 300 s and 530 s for silencedetect, and otherwise writes "cut <ss> <t>" to
// the output file (the last argument).
func fakeFfmpeg() {
	for _, a := range os.Args {
		if strings.HasPrefix(a, "silencedetect") {
			for _, s := range []float64{250, 300, 530} {
				fmt.Fprintf(os.Stderr, "[silencedetect @ 0x1] silence_end: %.3f | silence_duration: 1.000\n", s+0.5)
			}
			return
		}
	}
	var ss, t string
	if !strings.Contains(strings.Join(os.Args, " "), " -ss ") {
		// Converting an upload: report its length the way ffmpeg does (unless SR_FAKE_DURATION is empty).
		if d, err := strconv.Atoi(os.Getenv("SR_FAKE_DURATION")); err == nil {
			fmt.Fprintf(os.Stderr, "  Duration: %02d:%02d:%02d.40, start: 0.000000, bitrate: 60 kb/s\n", d/3600, d/60%60, d%60)
		}
	}
	for i, a := range os.Args {
		switch a {
		case "-ss":
			ss = os.Args[i+1]
		case "-t":
			t = os.Args[i+1]
		}
	}
	_ = os.WriteFile(os.Args[len(os.Args)-1], []byte("cut "+ss+" "+t), 0o600)
}

// fakeASR answers every chunk with two sentences, two seconds apart, and counts the requests.
func fakeASR(t *testing.T, calls *int) *httptest.Server {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/audio/transcriptions" || r.Header.Get("Authorization") != "Bearer asr-key" {
			http.Error(w, "bad", 400)
			return
		}
		_ = r.ParseMultipartForm(1 << 20)
		if r.FormValue("model") != "openai/whisper-large-v3" || r.FormValue("language") != "es" ||
			r.FormValue("response_format") != "verbose_json" || len(r.MultipartForm.File["file"]) != 1 {
			http.Error(w, "bad form", 400)
			return
		}
		*calls++
		fmt.Fprint(w, `{"text":"Hola, amigos. ¿Cómo están ustedes?",
			"segments":[{"start":0,"end":1.0,"text":" Hola, amigos."},{"start":3.0,"end":4.5,"text":" ¿Cómo están ustedes?"}],
			"words":[{"word":"Hola","start":0.1,"end":0.5},{"word":"amigos","start":0.5,"end":1.0},
			         {"word":"Cómo","start":3.0,"end":3.4},{"word":"están","start":3.4,"end":3.9},{"word":"ustedes","start":3.9,"end":4.5}]}`)
	}))
	t.Cleanup(srv.Close)
	return srv
}

func ytServer(t *testing.T, durationSec string) (*Server, *httptest.Server, string, *int) {
	t.Helper()
	s, ts := testServer(t, "")
	calls := new(int)
	asr := fakeASR(t, calls)
	log := filepath.Join(t.TempDir(), "ytdlp.log")
	t.Setenv("SR_FAKE_LOG", log)
	t.Setenv("SR_FAKE_DURATION", durationSec)
	s.cfg.YtDlpBin, s.cfg.FfmpegBin = fakeTool(t, "yt-dlp"), fakeTool(t, "ffmpeg")
	s.cfg.ASRBaseURL, s.cfg.ASRKey, s.cfg.ASRModel = asr.URL+"/v1", "asr-key", "openai/whisper-large-v3"
	s.cfg.YTMaxMinutes, s.cfg.ASRDailyMinutes = 60, 30
	s.yt = newYouTube(s.cfg, s.store, filepath.Join(s.tts.dir, "pages"))
	return s, ts, log, calls
}

func waitJob(t *testing.T, ts *httptest.Server, token, id string) map[string]any {
	t.Helper()
	for i := 0; i < 200; i++ {
		_, job := do(t, ts, "GET", "/api/youtube/jobs/"+id, token, nil)
		if job["status"] == "done" || job["status"] == "error" {
			return job
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatal("import never finished")
	return nil
}

func TestParseYouTubeID(t *testing.T) {
	good := map[string]string{
		"https://www.youtube.com/watch?v=dQw4w9WgXcQ":            "dQw4w9WgXcQ",
		"https://youtube.com/watch?v=dQw4w9WgXcQ&t=42s&list=PL1": "dQw4w9WgXcQ",
		"https://m.youtube.com/watch?v=dQw4w9WgXcQ":              "dQw4w9WgXcQ",
		"https://youtu.be/dQw4w9WgXcQ?si=abc":                    "dQw4w9WgXcQ",
		"youtu.be/dQw4w9WgXcQ":                                   "dQw4w9WgXcQ",
		"https://www.youtube.com/shorts/dQw4w9WgXcQ":             "dQw4w9WgXcQ",
		"  https://www.youtube.com/live/dQw4w9WgXcQ  ":           "dQw4w9WgXcQ",
	}
	for in, want := range good {
		if got, err := parseYouTubeID(in); err != nil || got != want {
			t.Errorf("%q: got %q, %v", in, got, err)
		}
	}
	for _, in := range []string{"", "--exec=touch /tmp/x", "-o /etc/passwd", "https://evil.com/watch?v=dQw4w9WgXcQ",
		"https://youtube.com.evil.com/watch?v=dQw4w9WgXcQ", "https://www.youtube.com/watch?v=--exec=rm",
		"https://www.youtube.com/watch?v=dQw4w9WgXc", "file:///etc/passwd", "https://youtu.be/dQw4w9WgXcQ/extra",
		"https://user@youtube.com/watch?v=dQw4w9WgXcQ", "https://youtube.com:8080/watch?v=dQw4w9WgXcQ",
		"https://www.youtube.com/playlist?list=PL123", "ftp://youtube.com/watch?v=dQw4w9WgXcQ"} {
		if got, err := parseYouTubeID(in); err == nil {
			t.Errorf("%q should be refused, got %q", in, got)
		}
	}
}

func TestPlanChunks(t *testing.T) {
	min := 60 * 1000
	if got := planChunks(5*min, nil); fmt.Sprint(got) != fmt.Sprint([]int{0, 5 * min}) {
		t.Fatalf("short audio is one chunk: %v", got)
	}
	// Pauses at 250 s, 300 s, 530 s: cut at the last pause inside [4 min, 8 min], then hard cuts.
	got := planChunks(20*min, []int{250000, 300000, 530000})
	want := []int{0, 300000, 780000, 1200000}
	if fmt.Sprint(got) != fmt.Sprint(want) {
		t.Fatalf("got %v, want %v", got, want)
	}
	for i := 1; i < len(got); i++ {
		if got[i]-got[i-1] > chunkMaxMs {
			t.Fatalf("chunk %d too long", i)
		}
	}
}

func words(spec ...any) []TimedWord {
	var out []TimedWord
	for i := 0; i < len(spec); i += 3 {
		out = append(out, TimedWord{spec[i].(string), spec[i+1].(int), spec[i+2].(int)})
	}
	return out
}

func TestAlignSentences(t *testing.T) {
	tl := words("Hola", 0, 400, "amigos.", 400, 900, "¿Cómo", 3000, 3300, "están", 3300, 3700, "ustedes?", 3700, 4200,
		"Dáselo", 6000, 6500, "a", 6500, 6600, "Juan", 6600, 7000, "uh", 7000, 7200, "mañana", 7200, 7800,
		"Hola", 9000, 9400, "amigos", 9400, 9900)
	// Accents/case/punctuation ignored, a filler word skipped, the page starts mid-lesson.
	got, _ := alignSentences(tl, []string{"¿COMO ESTAN ustedes?", "Dáselo a Juan mañana.", "Hola, amigos."})
	want := []Timing{{3000, 4200}, {6000, 7800}, {9000, 9900}}
	if fmt.Sprint(got) != fmt.Sprint(want) {
		t.Fatalf("got %v, want %v", got, want)
	}
	// A repeated sentence later in the text is found when the page starts there.
	if got, _ := alignSentences(tl, []string{"Hola amigos"}); got[0] != (Timing{0, 900}) {
		t.Fatalf("first occurrence: %v", got)
	}
	// A sentence the recognizer never heard is interpolated between its neighbours.
	got, ok := alignSentences(tl, []string{"¿Cómo están ustedes?", "Xyzzy plugh.", "Dáselo a Juan."})
	if !ok || got[1] != (Timing{4200, 6000}) {
		t.Fatalf("interpolated: %v", got)
	}
	// A page from some other text matches nothing.
	if _, ok := alignSentences(tl, []string{"Xyzzy plugh.", "Foo bar."}); ok {
		t.Fatal("unrelated text should not align")
	}
}

func TestLessonText(t *testing.T) {
	tr := &Transcript{Segments: []Segment{{"Hola, amigos.", 0, 1000}, {"¿Cómo están", 1100, 2000},
		{"ustedes?", 2000, 2500}, {"Bien.", 5000, 5500}}}
	if got := lessonText(tr); got != "Hola, amigos. ¿Cómo están ustedes?\n\nBien." {
		t.Fatalf("got %q", got)
	}
}

func TestYouTubeImportAndPageAudio(t *testing.T) {
	s, ts, log, calls := ytServer(t, "620")
	token := login(t, ts)
	_, sess := do(t, ts, "GET", "/api/session", token, nil)
	if sess["youtube"] != true {
		t.Fatalf("session should report YouTube import: %v", sess)
	}
	resp, job := do(t, ts, "POST", "/api/youtube", token, map[string]string{"url": "https://youtu.be/dQw4w9WgXcQ"})
	if resp.StatusCode != 200 {
		t.Fatalf("start: %d %v", resp.StatusCode, job)
	}
	job = waitJob(t, ts, token, job["id"].(string))
	if job["status"] != "done" || job["title"] != "Charla de prueba" {
		t.Fatalf("job: %v", job)
	}
	if *calls != 2 {
		t.Fatalf("620 s with a pause at 300 s should be two transcription requests, got %d", *calls)
	}
	lessonID := job["lessonId"].(string)
	l, _ := s.store.GetLesson(lessonID)
	if l.Source != "youtube" || l.VideoID != "dQw4w9WgXcQ" || l.DurationSec != 620 ||
		l.Text != "Hola, amigos.\n\n¿Cómo están ustedes?\n\nHola, amigos.\n\n¿Cómo están ustedes?" {
		t.Fatalf("lesson: %+v", l)
	}
	raw, _ := os.ReadFile(log)
	for _, want := range []string{"--ignore-config", "--no-playlist", "-- https://www.youtube.com/watch?v=dQw4w9WgXcQ"} {
		if !strings.Contains(string(raw), want) {
			t.Fatalf("yt-dlp args should contain %q: %s", want, raw)
		}
	}

	// Page = second and third sentences: from chunk 1 at 3.0 s to chunk 2 at 300 s + 1.0 s.
	resp, out := do(t, ts, "POST", "/api/youtube/dQw4w9WgXcQ/audio", token,
		map[string]any{"sentences": []string{"¿Cómo están ustedes?", "Hola, amigos."}})
	if resp.StatusCode != 200 {
		t.Fatalf("audio: %d %v", resp.StatusCode, out)
	}
	timings, _ := json.Marshal(out["timings"])
	if string(timings) != "[[150,1650],[297250,298150]]" {
		t.Fatalf("timings relative to the cut: %s", timings)
	}
	audio := out["audio"].(string)
	req, _ := http.NewRequest("GET", ts.URL+audio, nil)
	req.Header.Set("Authorization", "Bearer "+token)
	ar, err := http.DefaultClient.Do(req)
	if err != nil || ar.StatusCode != 200 {
		t.Fatalf("serve audio: %v %v", ar, err)
	}
	body := make([]byte, 64)
	n, _ := ar.Body.Read(body)
	ar.Body.Close()
	if got := string(body[:n]); got != "cut 2.850 298.300" {
		t.Fatalf("cut span: %q", got)
	}

	// Importing the same video again reuses the lesson: no download, no transcription.
	before, _ := os.ReadFile(log)
	_, again := do(t, ts, "POST", "/api/youtube", token, map[string]string{"url": "https://www.youtube.com/watch?v=dQw4w9WgXcQ"})
	if again["status"] != "done" || again["lessonId"] != lessonID || *calls != 2 {
		t.Fatalf("re-import should reuse the lesson: %v (calls %d)", again, *calls)
	}
	// Deleting the shared lesson and importing again rebuilds it from the saved transcript.
	do(t, ts, "DELETE", "/api/lessons/"+lessonID, token, nil)
	_, job = do(t, ts, "POST", "/api/youtube", token, map[string]string{"url": "youtu.be/dQw4w9WgXcQ"})
	job = waitJob(t, ts, token, job["id"].(string))
	after, _ := os.ReadFile(log)
	if job["status"] != "done" || job["lessonId"] == lessonID || *calls != 2 || string(after) != string(before) {
		t.Fatalf("rebuild from saved transcript: %v (calls %d)", job, *calls)
	}
}

func TestYouTubeRefusals(t *testing.T) {
	s, ts, log, calls := ytServer(t, "4000")
	token := login(t, ts)
	for _, p := range [][2]string{{"POST", "/api/youtube"}, {"GET", "/api/youtube/jobs/0123456789abcdef"},
		{"POST", "/api/youtube/dQw4w9WgXcQ/audio"}} {
		if resp, _ := do(t, ts, p[0], p[1], "", nil); resp.StatusCode != 401 {
			t.Errorf("%s %s without a token: %d", p[0], p[1], resp.StatusCode)
		}
	}
	for _, u := range []string{"--exec=touch /tmp/pwned", "-o /etc/x https://youtu.be/dQw4w9WgXcQ", "https://evil.com/x"} {
		if resp, _ := do(t, ts, "POST", "/api/youtube", token, map[string]string{"url": u}); resp.StatusCode != 400 {
			t.Errorf("%q: %d", u, resp.StatusCode)
		}
	}
	if _, err := os.Stat(log); err == nil {
		t.Fatal("yt-dlp must not run for refused links")
	}
	for _, p := range []string{"/api/youtube/jobs/nope", "/api/youtube/jobs/..%2Fsecret", "/api/youtube/jobs/0123456789abcdef"} {
		if resp, _ := do(t, ts, "GET", p, token, nil); resp.StatusCode != 404 {
			t.Errorf("%s: %d", p, resp.StatusCode)
		}
	}
	// Too long: refused after reading the metadata, before downloading or transcribing.
	_, job := do(t, ts, "POST", "/api/youtube", token, map[string]string{"url": "https://youtu.be/dQw4w9WgXcQ"})
	job = waitJob(t, ts, token, job["id"].(string))
	raw, _ := os.ReadFile(log)
	if job["status"] != "error" || !strings.Contains(job["error"].(string), "limit is 60") ||
		strings.Count(string(raw), "\n") != 1 || *calls != 0 {
		t.Fatalf("over-long video: %v; yt-dlp calls: %s", job, raw)
	}
	// Daily budget (30 min): a 20-minute video fits once, the second one doesn't.
	t.Setenv("SR_FAKE_DURATION", "1200")
	_, job = do(t, ts, "POST", "/api/youtube", token, map[string]string{"url": "https://youtu.be/aaaaaaaaaaa"})
	if job = waitJob(t, ts, token, job["id"].(string)); job["status"] != "done" {
		t.Fatalf("first video within budget: %v", job)
	}
	_, job = do(t, ts, "POST", "/api/youtube", token, map[string]string{"url": "https://youtu.be/bbbbbbbbbbb"})
	if job = waitJob(t, ts, token, job["id"].(string)); job["status"] != "error" || !strings.Contains(job["error"].(string), "limit (30 minutes)") {
		t.Fatalf("over budget: %v", job)
	}
	// A shared lesson can't claim a bogus video ID; unknown or malformed videos have no audio.
	_, sum := do(t, ts, "POST", "/api/lessons", token, map[string]any{"title": "x", "text": "Hola.", "videoId": "../../secret"})
	if l, _ := s.store.GetLesson(sum["id"].(string)); l.VideoID != "" {
		t.Fatalf("invalid video ID kept: %+v", l)
	}
	for _, v := range []string{"ccccccccccc", "..%2F..%2Fsecret", "short"} {
		if resp, _ := do(t, ts, "POST", "/api/youtube/"+v+"/audio", token,
			map[string]any{"sentences": []string{"Hola."}}); resp.StatusCode != 404 {
			t.Errorf("audio for %q: %d", v, resp.StatusCode)
		}
	}
}

// upload posts a device-downloaded recording the way the Android app does: fields first, then the file.
func upload(t *testing.T, ts *httptest.Server, token string, fields map[string]string, audio []byte) (*http.Response, map[string]any) {
	t.Helper()
	var buf bytes.Buffer
	mw := multipart.NewWriter(&buf)
	for _, k := range []string{"videoId", "title", "durationSec", "sharedBy"} {
		if v, ok := fields[k]; ok {
			_ = mw.WriteField(k, v)
		}
	}
	if audio != nil {
		fw, _ := mw.CreateFormFile("audio", "audio.webm")
		fw.Write(audio)
	}
	mw.Close()
	req, _ := http.NewRequest("POST", ts.URL+"/api/youtube/upload", &buf)
	req.Header.Set("Content-Type", mw.FormDataContentType())
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var out map[string]any
	_ = json.NewDecoder(resp.Body).Decode(&out)
	return resp, out
}

func TestYouTubeDeviceUploadMakesTheSameSharedLesson(t *testing.T) {
	s, ts, log, calls := ytServer(t, "620")
	s.cfg.YtDlpBin = "" // the device path must not need yt-dlp
	s.yt = newYouTube(s.cfg, s.store, filepath.Join(s.tts.dir, "pages"))
	token := login(t, ts)
	_, sess := do(t, ts, "GET", "/api/session", token, nil)
	if sess["youtube"] != false || sess["youtubeUpload"] != true {
		t.Fatalf("session: %v", sess)
	}
	// Nothing on the server yet: the device is asked to upload, and told the length limit.
	resp, prep := do(t, ts, "POST", "/api/youtube", token, map[string]any{"url": "https://youtu.be/dQw4w9WgXcQ", "deviceDownload": true})
	if resp.StatusCode != 200 || prep["status"] != "upload" || prep["videoId"] != "dQw4w9WgXcQ" || prep["maxMinutes"] != 60.0 || prep["id"] != "" {
		t.Fatalf("prepare: %d %v", resp.StatusCode, prep)
	}
	// The web path still needs yt-dlp.
	if resp, _ := do(t, ts, "POST", "/api/youtube", token, map[string]any{"url": "https://youtu.be/dQw4w9WgXcQ"}); resp.StatusCode != 503 {
		t.Fatalf("web start without yt-dlp: %d", resp.StatusCode)
	}
	// The device claims 100 s; ffmpeg reads 620 s from the file, which wins (two transcription chunks).
	resp, job := upload(t, ts, token, map[string]string{"videoId": "dQw4w9WgXcQ", "title": "Charla subida", "durationSec": "100", "sharedBy": "Tony"}, []byte("webm-audio"))
	if resp.StatusCode != 200 || job["status"] != "queued" {
		t.Fatalf("upload: %d %v", resp.StatusCode, job)
	}
	job = waitJob(t, ts, token, job["id"].(string))
	if job["status"] != "done" || job["title"] != "Charla subida" || *calls != 2 {
		t.Fatalf("job: %v (calls %d)", job, *calls)
	}
	if _, err := os.Stat(log); err == nil {
		t.Fatal("yt-dlp must not run for uploads")
	}
	if left, _ := os.ReadDir(filepath.Join(s.yt.dir, "uploads")); len(left) != 0 {
		t.Fatalf("upload file not removed: %v", left)
	}
	if media, _ := os.ReadFile(s.yt.mediaPath("dQw4w9WgXcQ")); !strings.HasPrefix(string(media), "cut") {
		t.Fatalf("converted media at mediaPath: %q", media)
	}

	// The web app sees it like a server import: listed with its video, full lesson, original page audio.
	req, _ := http.NewRequest("GET", ts.URL+"/api/lessons", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	lr, _ := http.DefaultClient.Do(req)
	var list []map[string]any
	_ = json.NewDecoder(lr.Body).Decode(&list)
	lr.Body.Close()
	if len(list) != 1 || list[0]["videoId"] != "dQw4w9WgXcQ" || list[0]["id"] != job["lessonId"] {
		t.Fatalf("shared library: %v", list)
	}
	_, l := do(t, ts, "GET", "/api/lessons/"+job["lessonId"].(string), token, nil)
	if l["source"] != "youtube" || l["sharedBy"] != "Tony" || l["sourceUrl"] != "https://www.youtube.com/watch?v=dQw4w9WgXcQ" ||
		l["durationSec"] != 620.0 || !strings.HasPrefix(l["text"].(string), "Hola, amigos.") {
		t.Fatalf("lesson: %v", l)
	}
	resp, out := do(t, ts, "POST", "/api/youtube/dQw4w9WgXcQ/audio", token, map[string]any{"sentences": []string{"¿Cómo están ustedes?", "Hola, amigos."}})
	if timings, _ := json.Marshal(out["timings"]); resp.StatusCode != 200 || string(timings) != "[[150,1650],[297250,298150]]" || out["voice"] != "original" {
		t.Fatalf("page audio: %d %v", resp.StatusCode, out)
	}

	// Re-import: done at once, no upload asked for, no new transcription.
	_, again := do(t, ts, "POST", "/api/youtube", token, map[string]any{"url": "youtu.be/dQw4w9WgXcQ", "deviceDownload": true})
	if again["status"] != "done" || again["lessonId"] != job["lessonId"] || *calls != 2 {
		t.Fatalf("re-import: %v", again)
	}
	// Shared lesson deleted: rebuilt from the saved transcript, still without an upload.
	do(t, ts, "DELETE", "/api/lessons/"+job["lessonId"].(string), token, nil)
	_, again = do(t, ts, "POST", "/api/youtube", token, map[string]any{"url": "youtu.be/dQw4w9WgXcQ", "deviceDownload": true})
	if again["status"] != "queued" {
		t.Fatalf("rebuild should queue a job, not ask for an upload: %v", again)
	}
	if again = waitJob(t, ts, token, again["id"].(string)); again["status"] != "done" || *calls != 2 {
		t.Fatalf("rebuild: %v", again)
	}
	// An upload racing a finished import is answered with the lesson and discarded.
	resp, dup := upload(t, ts, token, map[string]string{"videoId": "dQw4w9WgXcQ", "title": "x", "durationSec": "620"}, []byte("again"))
	if resp.StatusCode != 200 || dup["status"] != "done" || *calls != 2 {
		t.Fatalf("duplicate upload: %d %v", resp.StatusCode, dup)
	}
}

func TestYouTubeUploadRefusals(t *testing.T) {
	s, ts, _, calls := ytServer(t, "")
	token := login(t, ts)
	if resp, _ := upload(t, ts, "", map[string]string{"videoId": "dQw4w9WgXcQ", "durationSec": "60"}, []byte("a")); resp.StatusCode != 401 {
		t.Fatalf("without a token: %d", resp.StatusCode)
	}
	cases := []struct {
		fields map[string]string
		audio  []byte
		status int
		msg    string
	}{
		{map[string]string{"videoId": "../../etc/x", "durationSec": "60"}, []byte("a"), 400, "video ID"},
		{map[string]string{"videoId": "dQw4w9WgXcQ", "durationSec": "4000"}, []byte("a"), 400, "limit is 60"},
		{map[string]string{"videoId": "dQw4w9WgXcQ", "durationSec": "0"}, []byte("a"), 400, "known length"},
		{map[string]string{"videoId": "dQw4w9WgXcQ", "durationSec": "60"}, nil, 400, "no audio"},
		{map[string]string{"videoId": "dQw4w9WgXcQ", "durationSec": "60"}, []byte{}, 400, "empty"},
		{map[string]string{"videoId": "dQw4w9WgXcQ", "durationSec": "1900"}, []byte("a"), 400, "limit (30 minutes)"},
	}
	for _, c := range cases {
		resp, out := upload(t, ts, token, c.fields, c.audio)
		if resp.StatusCode != c.status || !strings.Contains(fmt.Sprint(out["error"]), c.msg) {
			t.Errorf("%v: %d %v", c.fields, resp.StatusCode, out)
		}
	}
	// Bigger than the cap for the length limit: 413, nothing kept.
	s.yt.maxMinutes = 1
	if resp, out := upload(t, ts, token, map[string]string{"videoId": "dQw4w9WgXcQ", "durationSec": "60"}, make([]byte, 4<<20)); resp.StatusCode != 413 {
		t.Errorf("too big: %d %v", resp.StatusCode, out)
	}
	if left, _ := os.ReadDir(filepath.Join(s.yt.dir, "uploads")); len(left) != 0 || *calls != 0 {
		t.Fatalf("refused uploads leave nothing: %v", left)
	}
	// The device's length is used when ffmpeg reports none, and is still checked against the limit.
	s.yt.maxMinutes = 60
	t.Setenv("SR_FAKE_DURATION", "")
	_, job := upload(t, ts, token, map[string]string{"videoId": "aaaaaaaaaaa", "durationSec": "90"}, []byte("a"))
	if job = waitJob(t, ts, token, job["id"].(string)); job["status"] != "done" || *calls != 1 {
		t.Fatalf("device length: %v", job)
	}
	if rec, ok := s.yt.loadRecord("aaaaaaaaaaa"); !ok || rec.DurationMs != 90000 || rec.Title != "YouTube aaaaaaaaaaa" {
		t.Fatalf("record: %+v", rec)
	}
}
