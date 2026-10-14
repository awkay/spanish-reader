package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"time"
)

// Manual sync: each account keeps one snapshot of its progress (lessons with their current page, vocabulary with
// statuses; the backup format). "Sync up" replaces it, "sync down" reads it. A push names the revision it was based
// on; if someone pushed since, it is refused (409) unless forced, so a device doesn't silently overwrite newer work.
//
//	<SR_DATA_DIR>/store/users/<user>/sync.json        current
//	<SR_DATA_DIR>/store/users/<user>/sync-<rev>.json  the previous syncKeep revisions, for recovery by hand

const (
	syncKeep     = 5
	syncMaxBytes = 32 << 20
)

type SyncMeta struct {
	Revision  int    `json:"revision"`
	UpdatedAt int64  `json:"updatedAt"` // unix ms
	Device    string `json:"device"`
}

type syncFile struct {
	SyncMeta
	Snapshot json.RawMessage `json:"snapshot"`
}

var errSyncConflict = errors.New("sync conflict")

func (st *Store) syncPath(user string, rev int) string {
	name := "sync.json"
	if rev > 0 {
		name = fmt.Sprintf("sync-%d.json", rev)
	}
	return filepath.Join(st.dir, "users", user, name)
}

// GetSync returns the account's snapshot; revision 0 and a nil snapshot when nothing was pushed yet.
func (st *Store) GetSync(user string) (syncFile, error) {
	raw, err := os.ReadFile(st.syncPath(user, 0))
	if errors.Is(err, os.ErrNotExist) {
		return syncFile{}, nil
	}
	if err != nil {
		return syncFile{}, err
	}
	var f syncFile
	err = json.Unmarshal(raw, &f)
	return f, err
}

// PutSync stores snapshot as the next revision. Unless force, base must be the current revision; otherwise it
// returns the current meta and errSyncConflict.
func (st *Store) PutSync(user string, base int, force bool, device string, snapshot json.RawMessage, now time.Time) (SyncMeta, error) {
	st.mu.Lock()
	defer st.mu.Unlock()
	cur, err := st.GetSync(user)
	if err != nil {
		return SyncMeta{}, err
	}
	if !force && base != cur.Revision {
		return cur.SyncMeta, errSyncConflict
	}
	if err := os.MkdirAll(filepath.Dir(st.syncPath(user, 0)), 0o700); err != nil {
		return SyncMeta{}, err
	}
	if cur.Revision > 0 {
		if err := os.Rename(st.syncPath(user, 0), st.syncPath(user, cur.Revision)); err != nil {
			return SyncMeta{}, err
		}
		_ = os.Remove(st.syncPath(user, cur.Revision-syncKeep))
	}
	next := syncFile{SyncMeta{cur.Revision + 1, now.UnixMilli(), device}, snapshot}
	raw, err := json.Marshal(next)
	if err != nil {
		return SyncMeta{}, err
	}
	return next.SyncMeta, writeAtomic(st.syncPath(user, 0), raw)
}

// syncUser is the request's account, or "" after answering 403 for a session without one.
func syncUser(w http.ResponseWriter, r *http.Request) string {
	u := userOf(r)
	if u == "" {
		writeError(w, http.StatusForbidden, "Sign in with your name to sync.")
	}
	return u
}

func (s *Server) handleSyncMeta(w http.ResponseWriter, r *http.Request) {
	user := syncUser(w, r)
	if user == "" {
		return
	}
	f, err := s.store.GetSync(user)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, f.SyncMeta)
}

func (s *Server) handleSyncGet(w http.ResponseWriter, r *http.Request) {
	user := syncUser(w, r)
	if user == "" {
		return
	}
	f, err := s.store.GetSync(user)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, f)
}

func (s *Server) handleSyncPut(w http.ResponseWriter, r *http.Request) {
	user := syncUser(w, r)
	if user == "" {
		return
	}
	var req struct {
		BaseRevision int             `json:"baseRevision"`
		Force        bool            `json:"force"`
		Device       string          `json:"device"`
		Snapshot     json.RawMessage `json:"snapshot"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, syncMaxBytes)).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "bad request")
		return
	}
	// The backup format: {version <= 1, lessons: [...], vocab: [...]}. Its content is the clients' business.
	var shape struct {
		Version int               `json:"version"`
		Lessons []json.RawMessage `json:"lessons"`
		Vocab   []json.RawMessage `json:"vocab"`
	}
	if json.Unmarshal(req.Snapshot, &shape) != nil || shape.Version < 1 || shape.Version > 1 {
		writeError(w, http.StatusBadRequest, "not a snapshot this server understands")
		return
	}
	meta, err := s.store.PutSync(user, req.BaseRevision, req.Force, truncate(req.Device, 100), req.Snapshot, time.Now())
	switch {
	case errors.Is(err, errSyncConflict):
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusConflict)
		_ = json.NewEncoder(w).Encode(map[string]any{"error": "The server has a newer copy.", "current": meta})
	case err != nil:
		writeError(w, http.StatusInternalServerError, err.Error())
	default:
		writeJSON(w, meta)
	}
}
