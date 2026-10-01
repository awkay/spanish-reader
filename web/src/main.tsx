import { render } from 'preact';
import { loadLessons, loadSettings, loadVocab, refreshShared } from './services.ts';
import { App } from './ui/App.tsx';
import './app.css';

async function start() {
  if ('serviceWorker' in navigator && location.protocol === 'https:') {
    navigator.serviceWorker.register('sw.js').catch(() => {});
  }
  await Promise.all([loadSettings(), loadVocab(), loadLessons()]);
  // Ask the browser not to evict our data (works for Home Screen apps on iOS).
  navigator.storage?.persist?.().catch(() => {});
  render(<App />, document.getElementById('app')!);
  // A Home Screen app is resumed, not reloaded: pick up what the other phone shared or removed meanwhile.
  const onLibrary = () => !location.hash || location.hash === '#/';
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible' && onLibrary()) refreshShared();
  });
  addEventListener('online', () => onLibrary() && refreshShared());
}

start();
