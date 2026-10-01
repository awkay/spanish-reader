import { useEffect, useState } from 'preact/hooks';
import { api, setUnauthorizedHandler } from '../api.ts';
import { Import } from './Import.tsx';
import { Library } from './Library.tsx';
import { Login } from './Login.tsx';
import { Reader } from './Reader.tsx';
import { Settings } from './Settings.tsx';
import { Vocabulary } from './Vocabulary.tsx';

type Auth = 'checking' | 'in' | 'out';

function useHash() {
  const [hash, setHash] = useState(location.hash || '#/');
  useEffect(() => {
    const f = () => setHash(location.hash || '#/');
    addEventListener('hashchange', f);
    return () => removeEventListener('hashchange', f);
  }, []);
  return hash;
}

export function App() {
  const [auth, setAuth] = useState<Auth>('checking');
  const hash = useHash();
  useEffect(() => {
    setUnauthorizedHandler(() => setAuth('out'));
    api.session().then(() => setAuth('in'), (e) => {
      // Offline: let the app work from local data; the server will ask again when reachable.
      setAuth(e.status === 401 ? 'out' : 'in');
    });
  }, []);
  if (auth === 'checking') return <div class="screen center muted">…</div>;
  if (auth === 'out') return <Login onDone={() => setAuth('in')} />;
  const signOut = async () => {
    await fetch('/api/logout', { method: 'POST' }).catch(() => {});
    setAuth('out');
  };
  const read = hash.match(/^#\/read\/(.+)$/);
  if (read) return <Reader id={decodeURIComponent(read[1])} />;
  if (hash === '#/import') return <Import />;
  if (hash === '#/vocab') return <Vocabulary />;
  if (hash === '#/settings') return <Settings onSignOut={signOut} />;
  return <Library />;
}
