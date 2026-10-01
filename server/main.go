// Command spanish-reader-server is the small backend for the Spanish Reader web app (the iPhone client).
//
// It only does what a browser can't do safely or cheaply on its own:
//   - access-code login (one shared code for the household),
//   - forwarding AI requests with the server's API key (the key never reaches the browser),
//   - text-to-speech with Piper, returning one MP3 per page plus sentence timings,
//   - a shared store of lessons and of per-sentence AI results (glosses, translations, idioms).
//
// Everything personal (known words, statuses) lives in each browser. Standard library only; configure with
// environment variables (see config.go). nginx serves the web app's static files and proxies /api here.
package main

import (
	"log"
	"net/http"
	"time"
)

func main() {
	cfg, err := loadConfig()
	if err != nil {
		log.Fatalf("config: %v", err)
	}
	srv, err := newServer(cfg)
	if err != nil {
		log.Fatalf("startup: %v", err)
	}
	httpSrv := &http.Server{
		Addr:              cfg.Listen,
		Handler:           srv.routes(),
		ReadHeaderTimeout: 10 * time.Second,
		// AI calls and TTS can take a while; nginx's proxy_read_timeout must be at least as long.
		WriteTimeout: 5 * time.Minute,
		IdleTimeout:  2 * time.Minute,
	}
	log.Printf("spanish-reader-server listening on %s (data in %s, tts: %v, voice: %q)",
		cfg.Listen, cfg.DataDir, srv.tts.Enabled(), srv.tts.Voice())
	log.Fatal(httpSrv.ListenAndServe())
}
