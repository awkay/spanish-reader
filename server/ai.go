package main

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// AIProxy turns {system, user} prompts from the web app into one provider request using the server's key.
// Clients can't pick the endpoint or model (only "improve" for the stronger one), so the proxy can't be used as an
// open relay.
type AIProxy struct {
	cfg     *Config
	client  *http.Client
	permits chan struct{}
	sleep   func(time.Duration)
}

func newAIProxy(cfg *Config) *AIProxy {
	return &AIProxy{
		cfg:     cfg,
		client:  &http.Client{Timeout: 3 * time.Minute},
		permits: make(chan struct{}, 3),
		sleep:   time.Sleep,
	}
}

type aiRequest struct {
	System  string `json:"system"`
	User    string `json:"user"`
	Items   int    `json:"items"`   // number of words/sentences, to size max_tokens for Anthropic
	Improve bool   `json:"improve"` // use SR_AI_IMPROVE_MODEL
	// Set only by server code (photo import), never from a client's JSON.
	Model  string    `json:"-"` // overrides the configured model
	Effort string    `json:"-"` // reasoning effort when not "low" (Responses API; z.ai chat then keeps thinking on)
	Images []aiImage `json:"-"` // sent after the text, in order
}

// aiImage is a photo for a vision-capable model.
type aiImage struct {
	MediaType string // image/jpeg, image/png or image/webp
	Data      []byte
}

func (im aiImage) dataURL() string {
	return "data:" + im.MediaType + ";base64," + base64.StdEncoding.EncodeToString(im.Data)
}

func (s *Server) handleAI(w http.ResponseWriter, r *http.Request) {
	var req aiRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 512<<10)).Decode(&req); err != nil || req.User == "" {
		writeError(w, http.StatusBadRequest, "bad request")
		return
	}
	text, err := s.ai.Complete(r.Context(), req)
	if err != nil {
		log.Printf("ai: %v", err)
		writeError(w, http.StatusBadGateway, err.Error())
		return
	}
	writeJSON(w, map[string]string{"text": text})
}

// Complete sends one request, retrying 429/5xx/network errors with backoff (honoring Retry-After).
func (p *AIProxy) Complete(ctx context.Context, req aiRequest) (string, error) {
	if p.cfg.AIBaseURL == "" || p.cfg.AIModel == "" {
		return "", errors.New("AI is not configured on the server")
	}
	select {
	case p.permits <- struct{}{}:
		defer func() { <-p.permits }()
	case <-ctx.Done():
		return "", ctx.Err()
	}
	model := p.cfg.AIModel
	if req.Improve {
		model = p.cfg.AIImproveModel
	}
	if req.Model != "" {
		model = req.Model
	}
	url, body, headers := p.build(req, model)
	var lastErr error
	for attempt := 0; attempt < 4; attempt++ {
		if attempt > 0 {
			p.sleep(time.Duration(1<<attempt) * time.Second)
		}
		httpReq, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(body))
		if err != nil {
			return "", err
		}
		for k, v := range headers {
			httpReq.Header.Set(k, v)
		}
		resp, err := p.client.Do(httpReq)
		if err != nil {
			lastErr = err
			if ctx.Err() != nil {
				return "", ctx.Err()
			}
			continue
		}
		raw, _ := io.ReadAll(io.LimitReader(resp.Body, 8<<20))
		resp.Body.Close()
		if resp.StatusCode == http.StatusOK {
			return p.extract(raw)
		}
		lastErr = fmt.Errorf("AI provider returned HTTP %d: %s", resp.StatusCode, truncate(string(raw), 300))
		if resp.StatusCode != 429 && resp.StatusCode < 500 {
			return "", lastErr
		}
		if s, err := strconv.Atoi(resp.Header.Get("Retry-After")); err == nil && s > 0 && s <= 60 {
			p.sleep(time.Duration(s) * time.Second)
		}
	}
	return "", lastErr
}

func (p *AIProxy) build(req aiRequest, model string) (string, []byte, map[string]string) {
	base := strings.TrimRight(p.cfg.AIBaseURL, "/")
	auth := map[string]string{"Content-Type": "application/json"}
	var body any
	var url string
	switch p.cfg.AIProtocol {
	case "anthropic":
		url = base + "/v1/messages"
		auth["x-api-key"] = p.cfg.AIKey
		auth["anthropic-version"] = "2023-06-01"
		maxTokens := 1024
		if n := req.Items * 600; n > maxTokens {
			maxTokens = min(n, 16000)
		}
		var content any = req.User
		if len(req.Images) > 0 {
			parts := []map[string]any{}
			for _, im := range req.Images {
				parts = append(parts, map[string]any{"type": "image", "source": map[string]string{
					"type": "base64", "media_type": im.MediaType, "data": base64.StdEncoding.EncodeToString(im.Data)}})
			}
			content = append(parts, map[string]any{"type": "text", "text": req.User})
		}
		body = map[string]any{
			"model": model, "max_tokens": maxTokens, "temperature": 0.2, "system": req.System,
			"messages": []map[string]any{{"role": "user", "content": content}},
		}
	case "chat":
		url = base + "/chat/completions"
		auth["Authorization"] = "Bearer " + p.cfg.AIKey
		var content any = req.User
		if len(req.Images) > 0 {
			parts := []map[string]any{{"type": "text", "text": req.User}}
			for _, im := range req.Images {
				parts = append(parts, map[string]any{"type": "image_url", "image_url": map[string]string{"url": im.dataURL()}})
			}
			content = parts
		}
		b := map[string]any{
			"model": model, "temperature": 0.2,
			"response_format": map[string]string{"type": "json_object"},
			"messages":        []map[string]any{{"role": "system", "content": req.System}, {"role": "user", "content": content}},
		}
		if (strings.Contains(base, "z.ai") || strings.Contains(base, "bigmodel.cn")) && req.Effort == "" {
			b["thinking"] = map[string]string{"type": "disabled"} // GLM reasons by default; glossing doesn't need it
		}
		body = b
	default: // responses
		effort := "low"
		if req.Effort != "" {
			effort = req.Effort
		}
		url = base + "/responses"
		auth["Authorization"] = "Bearer " + p.cfg.AIKey
		var input any = req.User
		if len(req.Images) > 0 {
			parts := []map[string]any{{"type": "input_text", "text": req.User}}
			for _, im := range req.Images {
				parts = append(parts, map[string]any{"type": "input_image", "image_url": im.dataURL()})
			}
			input = []map[string]any{{"role": "user", "content": parts}}
		}
		body = map[string]any{
			"model": model, "instructions": req.System, "input": input, "temperature": 0.2,
			"reasoning": map[string]string{"effort": effort},
			"text":      map[string]any{"format": map[string]string{"type": "json_object"}},
		}
	}
	raw, _ := json.Marshal(body)
	return url, raw, auth
}

// extract pulls the assistant text out of a provider response.
func (p *AIProxy) extract(raw []byte) (string, error) {
	var v struct {
		OutputText string `json:"output_text"`
		Output     []struct {
			Type    string `json:"type"`
			Content []struct {
				Type string `json:"type"`
				Text string `json:"text"`
			} `json:"content"`
		} `json:"output"`
		Choices []struct {
			Message struct {
				Content string `json:"content"`
			} `json:"message"`
		} `json:"choices"`
		Content []struct {
			Type string `json:"type"`
			Text string `json:"text"`
		} `json:"content"`
	}
	if err := json.Unmarshal(raw, &v); err != nil {
		return "", fmt.Errorf("unreadable AI response: %v", err)
	}
	var sb strings.Builder
	switch p.cfg.AIProtocol {
	case "anthropic":
		for _, c := range v.Content {
			if c.Type == "text" {
				sb.WriteString(c.Text)
			}
		}
	case "chat":
		if len(v.Choices) > 0 {
			sb.WriteString(v.Choices[0].Message.Content)
		}
	default:
		for _, o := range v.Output {
			if o.Type != "message" {
				continue
			}
			for _, c := range o.Content {
				if c.Type == "output_text" {
					sb.WriteString(c.Text)
				}
			}
		}
		if sb.Len() == 0 {
			sb.WriteString(v.OutputText)
		}
	}
	if sb.Len() == 0 {
		return "", fmt.Errorf("AI response had no text: %s", truncate(string(raw), 200))
	}
	return sb.String(), nil
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n] + "…"
}
