import { useState } from 'preact/hooks';
import { isJustUrl, isYouTubeUrl } from '../core/text.ts';
import { createLesson, importYouTube, preGloss, settings as settingsObs, updateSettings } from '../services.ts';
import { navigate, useObservable } from './hooks.ts';

export function Import() {
  const settings = useObservable(settingsObs);
  const [title, setTitle] = useState('');
  const [text, setText] = useState('');
  const [join, setJoin] = useState(settings.joinWrappedLines);
  const [error, setError] = useState<string | null>(null);
  const [progress, setProgress] = useState<string | null>(null);
  const youtube = isYouTubeUrl(text);

  const paste = async () => {
    try {
      setText(await navigator.clipboard.readText());
    } catch {
      setError('Paste with a long-press in the text box instead.');
    }
  };
  const create = async () => {
    try {
      await updateSettings({ joinWrappedLines: join });
      const l = await createLesson(title, text, join);
      preGloss(l, 0, settings.preGlossPagesAhead || 0);
      navigate(`#/read/${l.id}`);
    } catch (e) {
      setError((e as Error).message);
    }
  };
  const fromYouTube = async () => {
    setError(null);
    setProgress('Starting…');
    try {
      const l = await importYouTube(text.trim(), setProgress);
      preGloss(l, 0, settings.preGlossPagesAhead || 0);
      navigate(`#/read/${l.id}`);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setProgress(null);
    }
  };
  return (
    <div class="screen">
      <header class="bar">
        <a class="icon-btn" href="#/">←</a>
        <h1>New lesson</h1>
      </header>
      <main class="content column">
        <input placeholder="Title (optional)" value={title} onInput={(e) => setTitle((e.target as HTMLInputElement).value)} />
        <button onClick={paste}>Paste from clipboard</button>
        <textarea placeholder="Spanish text" rows={12} value={text} onInput={(e) => setText((e.target as HTMLTextAreaElement).value)} />
        {youtube && (
          <p class="small muted">
            A YouTube video: the server downloads its audio and transcribes it (a few minutes for a long video). You'll
            hear the real speaker in the reader.
          </p>
        )}
        {isJustUrl(text) && !youtube && <p class="error small">That looks like a link. Open the article, select its text and paste that instead.</p>}
        <label class="row">
          <input type="checkbox" checked={join} onChange={(e) => setJoin((e.target as HTMLInputElement).checked)} />
          <span>Join wrapped lines <span class="small muted">(text copied from PDFs or emails)</span></span>
        </label>
        {error && <p class="error">{error}</p>}
        {progress && <p class="muted">{progress}</p>}
        {youtube
          ? <button class="primary" disabled={progress !== null} onClick={fromYouTube}>Import from YouTube</button>
          : <button class="primary" disabled={!text.trim()} onClick={create}>Create lesson</button>}
      </main>
    </div>
  );
}
