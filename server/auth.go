package main

import (
	"crypto/hmac"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	sessionCookie   = "sr_session"
	sessionLifetime = 365 * 24 * time.Hour
	failureDelay    = 2 * time.Second
	lockoutWindow   = time.Hour
	lockoutFailures = 20
)

// Gate checks the shared access code. Attempts are handled one at a time (a mutex), and every wrong code costs
// failureDelay while holding it, so parallel guessing gains nothing. After lockoutFailures wrong codes within
// lockoutWindow, all attempts are refused until the window passes.
type Gate struct {
	code     string
	secret   []byte
	now      func() time.Time
	sleep    func(time.Duration)
	mu       sync.Mutex
	failures []time.Time
}

func newGate(code string, secret []byte) *Gate {
	return &Gate{code: code, secret: secret, now: time.Now, sleep: time.Sleep}
}

// Check returns (token, ok, lockedOut).
func (g *Gate) Check(code string) (string, bool, bool) {
	g.mu.Lock()
	defer g.mu.Unlock()
	now := g.now()
	recent := g.failures[:0]
	for _, t := range g.failures {
		if now.Sub(t) < lockoutWindow {
			recent = append(recent, t)
		}
	}
	g.failures = recent
	if len(g.failures) >= lockoutFailures {
		g.sleep(failureDelay)
		return "", false, true
	}
	if subtle.ConstantTimeCompare([]byte(strings.TrimSpace(code)), []byte(g.code)) == 1 {
		return g.issue(now.Add(sessionLifetime)), true, false
	}
	g.failures = append(g.failures, now)
	g.sleep(failureDelay)
	return "", false, false
}

func (g *Gate) mac(payload string) string {
	m := hmac.New(sha256.New, g.secret)
	m.Write([]byte(payload))
	return base64.RawURLEncoding.EncodeToString(m.Sum(nil))
}

// issue creates "<unix expiry>.<hmac>". Changing SR_SECRET (or the access code, see Valid) logs everyone out.
func (g *Gate) issue(expiry time.Time) string {
	payload := strconv.FormatInt(expiry.Unix(), 10)
	return payload + "." + g.mac(payload+"|"+g.code)
}

// Valid reports whether token was issued by this server for the current access code and hasn't expired.
func (g *Gate) Valid(token string) bool {
	payload, sig, ok := strings.Cut(token, ".")
	if !ok {
		return false
	}
	exp, err := strconv.ParseInt(payload, 10, 64)
	if err != nil || g.now().Unix() > exp {
		return false
	}
	return hmac.Equal([]byte(sig), []byte(g.mac(payload+"|"+g.code)))
}

func (s *Server) handleLogin(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Code string `json:"code"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096)).Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "bad request")
		return
	}
	token, ok, locked := s.gate.Check(body.Code)
	switch {
	case locked:
		writeError(w, http.StatusTooManyRequests, "Too many attempts. Try again later.")
	case !ok:
		writeError(w, http.StatusUnauthorized, "That code isn't right.")
	default:
		http.SetCookie(w, &http.Cookie{
			Name: sessionCookie, Value: token, Path: "/", MaxAge: int(sessionLifetime.Seconds()),
			HttpOnly: true, Secure: true, SameSite: http.SameSiteLaxMode,
		})
		writeJSON(w, map[string]string{"token": token})
	}
}

// requireAuth accepts the session cookie (web app) or "Authorization: Bearer <token>" (Android "Share to web").
func (s *Server) requireAuth(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		token := ""
		if c, err := r.Cookie(sessionCookie); err == nil {
			token = c.Value
		}
		if h := r.Header.Get("Authorization"); strings.HasPrefix(h, "Bearer ") {
			token = strings.TrimPrefix(h, "Bearer ")
		}
		if !s.gate.Valid(token) {
			writeError(w, http.StatusUnauthorized, "Not signed in")
			return
		}
		next(w, r)
	}
}
