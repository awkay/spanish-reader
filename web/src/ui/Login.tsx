import { useState } from 'preact/hooks';
import { api } from '../api.ts';

export function Login({ onDone }: { onDone: () => void }) {
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const submit = async (e: Event) => {
    e.preventDefault();
    if (!code.trim() || busy) return;
    setBusy(true);
    setError(null);
    try {
      await api.login(code.trim());
      onDone();
    } catch (err) {
      setError((err as Error).message);
      setCode('');
    } finally {
      setBusy(false);
    }
  };
  return (
    <main class="login">
      <img src="icon.svg" alt="" width="96" height="96" />
      <h1>Spanish Reader</h1>
      <form onSubmit={submit}>
        <label for="code">Access code</label>
        <input id="code" type="password" inputMode="numeric" autoComplete="current-password" autoFocus
          value={code} onInput={(e) => setCode((e.target as HTMLInputElement).value)} />
        <button type="submit" class="primary" disabled={busy || !code.trim()}>{busy ? 'Checking…' : 'Enter'}</button>
        {error && <p class="error">{error}</p>}
      </form>
    </main>
  );
}
