package main

import (
	"encoding/json"
	"log"
	"net/http"
	"path/filepath"
)

type Server struct {
	cfg   *Config
	gate  *Gate
	ai    *AIProxy
	tts   *TTS
	store *Store
	yt    *YouTube
}

func newServer(cfg *Config) (*Server, error) {
	store, err := newStore(filepath.Join(cfg.DataDir, "store"))
	if err != nil {
		return nil, err
	}
	tts := newTTS(cfg.PiperBin, cfg.PiperModel, cfg.LameBin, filepath.Join(cfg.DataDir, "tts"))
	return &Server{
		cfg:   cfg,
		gate:  newGate(cfg.AccessCode, cfg.Secret),
		ai:    newAIProxy(cfg),
		tts:   tts,
		store: store,
		yt:    newYouTube(cfg, store, filepath.Join(tts.dir, "pages")),
	}, nil
}

func (s *Server) routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("POST /api/login", s.handleLogin)
	mux.HandleFunc("POST /api/logout", func(w http.ResponseWriter, r *http.Request) {
		http.SetCookie(w, &http.Cookie{Name: sessionCookie, Value: "", Path: "/", MaxAge: -1, HttpOnly: true, Secure: true, SameSite: http.SameSiteLaxMode})
		writeJSON(w, map[string]bool{"ok": true})
	})
	mux.HandleFunc("GET /api/session", s.requireAuth(func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, map[string]any{"ok": true, "tts": s.tts.Enabled(), "voice": s.tts.Voice(), "youtube": s.yt.Enabled(),
			"youtubeUpload": s.yt.UploadEnabled()})
	}))
	mux.HandleFunc("POST /api/ai", s.requireAuth(s.handleAI))
	mux.HandleFunc("POST /api/tts", s.requireAuth(s.handleTTS))
	mux.HandleFunc("GET /api/audio/{file}", s.requireAuth(s.handleAudio))
	mux.HandleFunc("GET /api/lessons", s.requireAuth(s.handleListLessons))
	mux.HandleFunc("POST /api/lessons", s.requireAuth(s.handlePutLesson))
	mux.HandleFunc("GET /api/lessons/{id}", s.requireAuth(s.handleGetLesson))
	mux.HandleFunc("DELETE /api/lessons/{id}", s.requireAuth(s.handleDeleteLesson))
	mux.HandleFunc("POST /api/youtube", s.requireAuth(s.handleYouTubeStart))
	mux.HandleFunc("POST /api/youtube/upload", s.requireAuth(s.handleYouTubeUpload))
	mux.HandleFunc("GET /api/youtube/jobs/{id}", s.requireAuth(s.handleYouTubeJob))
	mux.HandleFunc("POST /api/youtube/{video}/audio", s.requireAuth(s.handleVideoAudio))
	mux.HandleFunc("POST /api/cache/get", s.requireAuth(s.handleCacheGet))
	mux.HandleFunc("POST /api/cache/put", s.requireAuth(s.handleCachePut))
	if s.cfg.WebDir != "" {
		mux.Handle("/", http.FileServer(http.Dir(s.cfg.WebDir)))
	}
	return mux
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	if err := json.NewEncoder(w).Encode(v); err != nil {
		log.Printf("write response: %v", err)
	}
}

func writeError(w http.ResponseWriter, status int, msg string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]string{"error": msg})
}
