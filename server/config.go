package main

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

// Config comes from environment variables (systemd EnvironmentFile in production).
type Config struct {
	Listen     string // SR_LISTEN, default 127.0.0.1:8090
	DataDir    string // SR_DATA_DIR, default ./data
	AccessCode string // SR_ACCESS_CODE (required)
	Secret     []byte // SR_SECRET; if empty, generated once and kept in DataDir/secret
	WebDir     string // SR_WEB_DIR: optional, serve the built web app too (local testing without nginx)

	AIProtocol     string // SR_AI_PROTOCOL: responses | chat | anthropic (default responses)
	AIBaseURL      string // SR_AI_BASE_URL, e.g. https://api.z.ai/api/v1
	AIKey          string // SR_AI_API_KEY
	AIModel        string // SR_AI_MODEL, e.g. glm-5.3-flash
	AIImproveModel string // SR_AI_IMPROVE_MODEL: model for "Improve answer" (default: SR_AI_MODEL)
	VisionModel    string // SR_VISION_MODEL: reads photos for photo lessons; must accept images (default: SR_AI_MODEL)

	PiperBin   string // SR_PIPER_BIN, e.g. /opt/piper/piper
	PiperModel string // SR_PIPER_MODEL, e.g. /opt/piper/voices/es_MX-claude-high.onnx
	LameBin    string // SR_LAME_BIN, default lame

	// YouTube lessons: yt-dlp downloads the audio, an OpenAI-compatible /audio/transcriptions endpoint with word
	// timestamps (OpenRouter, Groq, OpenAI) transcribes it. Enabled when SR_ASR_API_KEY and SR_ASR_MODEL are set.
	YtDlpBin        string // SR_YTDLP_BIN, default yt-dlp
	YtDlpCookies    string // SR_YTDLP_COOKIES: optional cookies.txt, if YouTube asks the server to sign in
	FfmpegBin       string // SR_FFMPEG_BIN, default ffmpeg
	ASRBaseURL      string // SR_ASR_BASE_URL, default https://openrouter.ai/api/v1
	ASRKey          string // SR_ASR_API_KEY
	ASRModel        string // SR_ASR_MODEL, e.g. openai/whisper-large-v3
	YTMaxMinutes    int    // SR_YT_MAX_MINUTES: longest video accepted, default 60
	ASRDailyMinutes int    // SR_ASR_DAILY_MINUTES: transcription budget per UTC day, default 180
}

func env(name, def string) string {
	if v := strings.TrimSpace(os.Getenv(name)); v != "" {
		return v
	}
	return def
}

func envInt(name string, def int) (int, error) {
	v := env(name, "")
	if v == "" {
		return def, nil
	}
	n, err := strconv.Atoi(v)
	if err != nil || n <= 0 {
		return 0, fmt.Errorf("%s must be a positive number, not %q", name, v)
	}
	return n, nil
}

func loadConfig() (*Config, error) {
	c := &Config{
		Listen:         env("SR_LISTEN", "127.0.0.1:8090"),
		DataDir:        env("SR_DATA_DIR", "./data"),
		AccessCode:     env("SR_ACCESS_CODE", ""),
		WebDir:         env("SR_WEB_DIR", ""),
		AIProtocol:     env("SR_AI_PROTOCOL", "responses"),
		AIBaseURL:      env("SR_AI_BASE_URL", ""),
		AIKey:          env("SR_AI_API_KEY", ""),
		AIModel:        env("SR_AI_MODEL", ""),
		AIImproveModel: env("SR_AI_IMPROVE_MODEL", ""),
		VisionModel:    env("SR_VISION_MODEL", ""),
		PiperBin:       env("SR_PIPER_BIN", ""),
		PiperModel:     env("SR_PIPER_MODEL", ""),
		LameBin:        env("SR_LAME_BIN", "lame"),
		YtDlpBin:       env("SR_YTDLP_BIN", "yt-dlp"),
		YtDlpCookies:   env("SR_YTDLP_COOKIES", ""),
		FfmpegBin:      env("SR_FFMPEG_BIN", "ffmpeg"),
		ASRBaseURL:     env("SR_ASR_BASE_URL", "https://openrouter.ai/api/v1"),
		ASRKey:         env("SR_ASR_API_KEY", ""),
		ASRModel:       env("SR_ASR_MODEL", ""),
	}
	var err error
	if c.YTMaxMinutes, err = envInt("SR_YT_MAX_MINUTES", 60); err != nil {
		return nil, err
	}
	if c.ASRDailyMinutes, err = envInt("SR_ASR_DAILY_MINUTES", 180); err != nil {
		return nil, err
	}
	if c.AccessCode == "" {
		return nil, errors.New("SR_ACCESS_CODE is required")
	}
	switch c.AIProtocol {
	case "responses", "chat", "anthropic":
	default:
		return nil, fmt.Errorf("SR_AI_PROTOCOL must be responses, chat or anthropic, not %q", c.AIProtocol)
	}
	if c.AIImproveModel == "" {
		c.AIImproveModel = c.AIModel
	}
	if c.VisionModel == "" {
		c.VisionModel = c.AIModel
	}
	if err := os.MkdirAll(c.DataDir, 0o700); err != nil {
		return nil, err
	}
	secret, err := loadSecret(env("SR_SECRET", ""), filepath.Join(c.DataDir, "secret"))
	if err != nil {
		return nil, err
	}
	c.Secret = secret
	return c, nil
}

// loadSecret returns the configured secret, or one persisted in file (created on first start) so sessions survive
// restarts.
func loadSecret(configured, file string) ([]byte, error) {
	if configured != "" {
		return []byte(configured), nil
	}
	if b, err := os.ReadFile(file); err == nil && len(b) >= 32 {
		return b, nil
	}
	raw := make([]byte, 32)
	if _, err := rand.Read(raw); err != nil {
		return nil, err
	}
	secret := []byte(hex.EncodeToString(raw))
	return secret, os.WriteFile(file, secret, 0o600)
}
