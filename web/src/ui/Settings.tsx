import { useState } from 'preact/hooks';
import * as db from '../db.ts';
import { exportBackup, importBackup, settings as settingsObs, updateSettings } from '../services.ts';
import { useObservable } from './hooks.ts';

export function Settings({ onSignOut }: { onSignOut: () => void }) {
  const s = useObservable(settingsObs);
  const [message, setMessage] = useState<string | null>(null);

  const download = async () => {
    const blob = new Blob([await exportBackup()], { type: 'application/json' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = `spanish-reader-${new Date().toISOString().slice(0, 10)}.json`;
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 10_000);
  };
  const restore = async (file: File | undefined) => {
    if (!file) return;
    try {
      setMessage(await importBackup(await file.text()));
    } catch (e) {
      setMessage('Restore failed: ' + (e as Error).message);
    }
  };
  const keep = async () => {
    const ok = await navigator.storage?.persist?.();
    setMessage(ok ? 'This device will keep your data.' : 'The browser did not grant persistent storage (add the app to the Home Screen).');
  };
  const num = (label: string, value: number, min: number, max: number, step: number, key: keyof typeof s) => (
    <label class="setting">
      <span>{label}: <b>{value}</b></span>
      <input type="range" min={min} max={max} step={step} value={value}
        onChange={(e) => updateSettings({ [key]: Number((e.target as HTMLInputElement).value) })} />
    </label>
  );
  return (
    <div class="screen">
      <header class="bar"><a class="icon-btn" href="#/">←</a><h1>Settings</h1></header>
      <main class="content column">
        {message && <p class="note" onClick={() => setMessage(null)}>{message}</p>}
        <h2>Reading</h2>
        {num('Text size', s.fontSize, 14, 36, 1, 'fontSize')}
        {num('Words per page', s.wordsPerPage, 100, 500, 25, 'wordsPerPage')}
        <h2>Word meanings</h2>
        {num('Pages prepared ahead (0 = whole lesson)', s.preGlossPagesAhead, 0, 20, 1, 'preGlossPagesAhead')}
        {num('Sentences explained per word', s.preGlossSentencesPerWord, 1, 10, 1, 'preGlossSentencesPerWord')}
        <h2>Sharing</h2>
        <label class="setting"><span>Your name (shown on lessons you share)</span>
          <input value={s.name} onChange={(e) => updateSettings({ name: (e.target as HTMLInputElement).value })} /></label>
        <h2>Backup</h2>
        <div class="row wrap">
          <button onClick={download}>Export</button>
          <label class="button">Restore<input type="file" accept="application/json,.json" hidden onChange={(e) => restore((e.target as HTMLInputElement).files?.[0])} /></label>
        </div>
        <p class="small muted">Same format as the Android app. Restore merges; nothing is deleted.</p>
        <h2>Storage</h2>
        <div class="row wrap">
          <button onClick={keep}>Keep data on this device</button>
          <button onClick={async () => { await db.clear('audio'); setMessage('Saved audio cleared.'); }}>Clear saved audio</button>
        </div>
        <h2>Account</h2>
        <button onClick={onSignOut}>Sign out</button>
      </main>
    </div>
  );
}
