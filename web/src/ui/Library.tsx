import { useEffect, useMemo, useState } from 'preact/hooks';
import { api, type SharedLessonSummary } from '../api.ts';
import { paginate, pageWordForms, tokenize } from '../core/text.ts';
import { lessonStats } from '../core/vocab.ts';
import type { Lesson } from '../db.ts';
import { addSharedLesson, deleteLesson, lessons as lessonsObs, preGlossStatus, settings as settingsObs, shareLesson, vocab as vocabObs } from '../services.ts';
import { navigate, useObservable } from './hooks.ts';

export function Library() {
  const lessons = useObservable(lessonsObs);
  const vocab = useObservable(vocabObs);
  const settings = useObservable(settingsObs);
  const pregloss = useObservable(preGlossStatus);
  const [shared, setShared] = useState<SharedLessonSummary[] | null>(null);
  const [sharedError, setSharedError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  const loadShared = () => api.lessons().then(setShared, (e) => setSharedError((e as Error).message));
  useEffect(() => {
    loadShared();
  }, []);

  const statuses = useMemo(() => new Map([...vocab].map(([k, v]) => [k, v.status])), [vocab]);

  const share = async (l: Lesson) => {
    setMessage('Sharing…');
    try {
      await shareLesson(l);
      setMessage(`Shared “${l.title}”.`);
      loadShared();
    } catch (e) {
      setMessage('Sharing failed: ' + (e as Error).message);
    }
  };
  const remove = async (l: Lesson) => {
    if (confirm(`Delete “${l.title}” from this device? Your vocabulary is kept.`)) await deleteLesson(l.id);
  };
  const add = async (s: SharedLessonSummary) => {
    const l = await addSharedLesson(s.id);
    navigate(`#/read/${l.id}`);
  };
  const removeShared = async (s: SharedLessonSummary) => {
    if (!confirm(`Remove “${s.title}” from the shared library for everyone? Copies on devices stay.`)) return;
    await api.deleteShared(s.id).catch((e) => setMessage((e as Error).message));
    loadShared();
  };

  const added = new Set(lessons.map((l) => l.sharedId).filter(Boolean));

  return (
    <div class="screen">
      <header class="bar">
        <h1>Spanish Reader</h1>
        <a class="icon-btn" href="#/vocab" title="Vocabulary">📖</a>
        <a class="icon-btn" href="#/settings" title="Settings">⚙︎</a>
      </header>
      <main class="content">
        {message && <p class="note" onClick={() => setMessage(null)}>{message}</p>}
        {pregloss && <p class="note">{pregloss}</p>}
        <a class="primary block" href="#/import">＋ New lesson</a>
        <h2>My lessons</h2>
        {lessons.length === 0 && <p class="muted">No lessons yet. Add one from the shared library below, or paste some Spanish text.</p>}
        {lessons.map((l) => <LessonCard key={l.id} lesson={l} statuses={statuses} wordsPerPage={settings.wordsPerPage} onShare={share} onDelete={remove} />)}
        <h2>Shared library</h2>
        {sharedError && <p class="error">{sharedError}</p>}
        {shared === null && !sharedError && <p class="muted">Loading…</p>}
        {shared?.length === 0 && <p class="muted">Nothing shared yet.</p>}
        {shared?.map((s) => (
          <div class="card" key={s.id}>
            <div class="grow">
              <div class="title">{s.title}</div>
              <div class="small muted">{s.words} words{s.sharedBy ? ` · shared by ${s.sharedBy}` : ''}</div>
            </div>
            {added.has(s.id) ? <span class="small muted">Added</span> : <button onClick={() => add(s)}>Add</button>}
            <button class="icon-btn" title="Remove from shared library" onClick={() => removeShared(s)}>🗑</button>
          </div>
        ))}
      </main>
    </div>
  );
}

function LessonCard({ lesson, statuses, wordsPerPage, onShare, onDelete }: {
  lesson: Lesson; statuses: Map<string, number>; wordsPerPage: number; onShare: (l: Lesson) => void; onDelete: (l: Lesson) => void;
}) {
  const analysis = useMemo(() => {
    const pages = paginate(tokenize(lesson.text), wordsPerPage);
    return { forms: pages.flatMap(pageWordForms), pageCount: pages.length };
  }, [lesson.text, wordsPerPage]);
  const s = lessonStats(analysis.forms, statuses);
  return (
    <div class="card">
      <a class="grow" href={`#/read/${lesson.id}`}>
        <div class="title">{lesson.title}</div>
        <div class="small muted">
          {s.totalWords} words · {s.newCount} new · {s.learningCount} learning · {Math.round(s.knownPercent)}% known
        </div>
        <div class="small muted">Page {Math.min(lesson.currentPage + 1, analysis.pageCount)} of {analysis.pageCount}</div>
      </a>
      {!lesson.sharedId && <button class="icon-btn" title="Share with the household" onClick={() => onShare(lesson)}>⇪</button>}
      <button class="icon-btn" title="Delete" onClick={() => onDelete(lesson)}>🗑</button>
    </div>
  );
}
