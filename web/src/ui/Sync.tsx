import { useEffect, useState } from 'preact/hooks';
import type { SyncMeta } from '../core/sync.ts';
import { LocalChanges, SyncConflict, type SyncStatus, account, syncDown, syncStatus, syncUp } from '../services.ts';
import { useObservable } from './hooks.ts';

function ago(ms: number): string {
  const min = Math.round((Date.now() - ms) / 60_000);
  if (min < 1) return 'just now';
  if (min < 60) return `${min} min ago`;
  const h = Math.round(min / 60);
  if (h < 48) return `${h} h ago`;
  return new Date(ms).toLocaleDateString();
}

const describe = (m: SyncMeta) => `revision ${m.revision}, from ${m.device || 'a device'}, ${ago(m.updatedAt)}`;

/** Manual sync of this account's lessons and vocabulary with the server. */
export function Sync({ onSignOut }: { onSignOut: () => void }) {
  const user = useObservable(account);
  const [status, setStatus] = useState<SyncStatus | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const refresh = () => syncStatus().then(setStatus, (e) => setError((e as Error).message));
  useEffect(() => {
    if (user) refresh();
  }, [user]);

  const run = async (direction: 'up' | 'down') => {
    setError(null);
    setMessage(null);
    setBusy(direction === 'up' ? 'Syncing up…' : 'Syncing down…');
    const go = direction === 'up' ? syncUp : syncDown;
    try {
      let result: string;
      try {
        result = await go(false, setBusy);
      } catch (e) {
        if (e instanceof SyncConflict) {
          if (!confirm(`The server has a newer copy (${describe(e.current)}) that this device hasn't synced down.\n\nOverwrite it with this device's lessons and words?`)) return;
        } else if (e instanceof LocalChanges) {
          const since = e.state ? ` since ${ago(e.state.atMillis)}` : '';
          if (!confirm(`This device has changes${since} that aren't on the server.\n\nReplace them with the server's copy?`)) return;
        } else throw e;
        result = await go(true, setBusy);
      }
      setMessage(result);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(null);
      void refresh();
    }
  };

  const s = status;
  return (
    <div class="screen">
      <header class="bar"><a class="icon-btn" href="#/">←</a><h1>Sync</h1></header>
      <main class="content">
        {!user ? (
          <>
            <p>To sync, sign in with your name. Sign out, then enter your name and the access code.</p>
            <button class="primary" onClick={onSignOut}>Sign out</button>
          </>
        ) : (
          <>
            <p class="small muted">
              Signed in as <b>{user}</b>. Sync moves your lessons (with the page you're on) and your words with their
              statuses. <b>Sync up</b> replaces the server's copy with this device's; <b>sync down</b> replaces this
              device's with the server's. Word meanings are shared either way.
            </p>
            <div class="note">
              <div>Server: {s === null ? '…' : s.server ? (s.server.revision ? describe(s.server) : 'nothing synced up yet') : s.serverError}</div>
              <div>This device: {s === null ? '…' : s.state
                ? `synced ${s.state.direction} revision ${s.state.revision}, ${ago(s.state.atMillis)}`
                : 'never synced'}{s?.localChanges ? ' · changed since' : ''}</div>
            </div>
            <div class="row" style={{ marginTop: '12px' }}>
              <button class="primary" disabled={busy !== null} onClick={() => run('up')}>⇡ Sync up</button>
              <button class="primary" disabled={busy !== null} onClick={() => run('down')}>⇣ Sync down</button>
            </div>
            {busy && <p class="muted">{busy}</p>}
            {message && <p class="note">{message}</p>}
            {error && <p class="error">{error}</p>}
          </>
        )}
      </main>
    </div>
  );
}
