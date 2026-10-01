import { render } from 'preact';
import { loadLessons, loadSettings, loadVocab } from './services.ts';
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
}

start();
