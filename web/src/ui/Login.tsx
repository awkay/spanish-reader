import { useState } from 'preact/hooks';
import { api } from '../api.ts';

const NAME_KEY = 'sr-name';

function savedName(): string {
  try {
    return localStorage.getItem(NAME_KEY) ?? '';
  } catch {
    return '';
  }
}

export function Login({ onDone }: { onDone: () => void }) {
  const [name, setName] = useState(savedName);
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const submit = async (e: Event) => {
    e.preventDefault();
    if (!code.trim() || !name.trim() || busy) return;
    setBusy(true);
    setError(null);
    try {
      await api.login(code.trim(), name.trim());
      try {
        localStorage.setItem(NAME_KEY, name.trim());
      } catch {
        // private mode: the name just isn't remembered
      }
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
        <label for="name">Your name</label>
        <input id="name" type="text" autoComplete="username" autoCapitalize="none" autoCorrect="off" spellcheck={false}
          placeholder="one word, like tony" autoFocus={!name}
          value={name} onInput={(e) => setName((e.target as HTMLInputElement).value.toLowerCase().replace(/[^a-z]/g, ''))} />
        <label for="code">Access code</label>
        <input id="code" type="password" inputMode="numeric" autoComplete="current-password" autoFocus={!!name}
          value={code} onInput={(e) => setCode((e.target as HTMLInputElement).value)} />
        <button type="submit" class="primary" disabled={busy || !code.trim() || !name.trim()}>{busy ? 'Checking…' : 'Enter'}</button>
        {error && <p class="error">{error}</p>}
      </form>
    </main>
  );
}
