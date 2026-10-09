import { useEffect, useMemo, useRef, useState } from 'preact/hooks';
import type { Gloss } from '../core/gloss.ts';
import { type Page, type Token, locatePhrase, paginate, pageWordForms, tokenize } from '../core/text.ts';
import { Status, nextHighlighted } from '../core/vocab.ts';
import * as db from '../db.ts';
import type { Lesson } from '../db.ts';
import { Player } from '../player.ts';
import {
  type Lookup, finishPage, improve, lookup, markPageKnown, phraseVersion, preGloss, saveLesson, sentenceRows, settings as settingsObs,
  annotate, inheritedFor, lessonLemmas, loadLessonLemmas, shownStatus, tapWord, translation, updateSettings, vocab as vocabObs,
} from '../services.ts';
import { statusBackground } from './colors.ts';
import { navigate, useObservable } from './hooks.ts';
import { WordSheet } from './WordSheet.tsx';

const player = new Player();

export interface PhraseSpan { phrase: string; meaning: string; tokens: Set<number> }

export interface Selection {
  token: Token;
  sentence: string;
  result: Lookup | null;
  improving: boolean;
  improveError: string | null;
  translation: string | { error: string } | null;
  /** When the word was first met already colored by its word family: that family's lemma. */
  familyLemma?: string | null;
}

export function Reader({ id }: { id: string }) {
  const settings = useObservable(settingsObs);
  useObservable(vocabObs);
  const lemmas = useObservable(lessonLemmas);
  const phraseVer = useObservable(phraseVersion);
  const audio = useObservable(player.state);
  const [lesson, setLesson] = useState<Lesson | null | undefined>(undefined);
  const [pageIndex, setPageIndex] = useState(0);
  const [selection, setSelection] = useState<Selection | null>(null);
  const [spans, setSpans] = useState<Map<number, PhraseSpan[]>>(new Map());
  const [toast, setToast] = useState<string | null>(null);
  const [sheetHeight, setSheetHeight] = useState(0);
  const lastPage = useRef(0);
  const preGlossedUpTo = useRef(-1);

  useEffect(() => {
    db.get<Lesson>('lessons', id).then((l) => {
      setLesson(l ?? null);
      if (l) {
        setPageIndex(l.currentPage);
        lastPage.current = l.currentPage;
      }
    });
  }, [id]);

  const text = useMemo(() => (lesson ? tokenize(lesson.text) : null), [lesson?.text]);
  const pages = useMemo(() => (text ? paginate(text, settings.wordsPerPage) : []), [text, settings.wordsPerPage]);
  useEffect(() => {
    if (text) loadLessonLemmas(text);
  }, [text]);
  const page: Page | undefined = pages[Math.min(pageIndex, Math.max(0, pages.length - 1))];

  /** Speakable sentences of a page: (tokenizer index, text). */
  const pageSentences = (p: Page | undefined) => {
    if (!p || !text) return [];
    const seen = new Set<number>();
    const out: Array<{ index: number; text: string }> = [];
    for (const t of p.tokens) {
      if (t.kind !== 'WORD' || seen.has(t.sentenceIndex)) continue;
      seen.add(t.sentenceIndex);
      out.push({ index: t.sentenceIndex, text: text.sentenceText(t.sentenceIndex) });
    }
    return out;
  };

  // Idioms of the visible page → which tokens to underline.
  useEffect(() => {
    if (!page || !text) return;
    const sents = pageSentences(page);
    sentenceRows(sents.map((s) => s.text)).then((rows) => {
      const m = new Map<number, PhraseSpan[]>();
      for (const s of sents) {
        const row = rows.get(s.text);
        if (!row) continue;
        const tokens = text.sentenceTokens(s.index);
        for (const p of row.phrases) {
          const hit = locatePhrase(tokens, p.phrase);
          if (!hit) continue;
          const span: PhraseSpan = { phrase: p.phrase, meaning: p.meaning, tokens: new Set(hit.map((t) => t.index)) };
          span.tokens.forEach((i) => m.set(i, [...(m.get(i) ?? []), span]));
        }
      }
      setSpans(m);
    });
  }, [page, phraseVer]);

  // Page turns: forward turns apply the page rule; keep pre-gloss a few pages ahead.
  useEffect(() => {
    if (!lesson || !text || !page) return;
    const from = lastPage.current;
    lastPage.current = page.index;
    (async () => {
      if (page.index > from) {
        let n = 0;
        for (let p = from; p < page.index; p++) n += await finishPage(text, pages[p]);
        if (n) setToast(`${n} words added at level 1`);
      }
      if (lesson.currentPage !== page.index) {
        const updated = { ...lesson, currentPage: page.index };
        setLesson(updated);
        await saveLesson(updated);
      }
      const ahead = settings.preGlossPagesAhead;
      if (ahead > 0 && page.index + ahead > preGlossedUpTo.current) {
        const start = Math.max(page.index, preGlossedUpTo.current);
        preGlossedUpTo.current = page.index + ahead;
        preGloss(lesson, start, page.index + ahead - start);
      }
    })();
  }, [page?.index, lesson?.id]);

  useEffect(() => {
    if (!toast) return;
    const t = setTimeout(() => setToast(null), 2500);
    return () => clearTimeout(t);
  }, [toast]);

  // Audio: at the end of a page, turn it and continue.
  useEffect(() => {
    player.onPageEnded = (ended) => {
      if (ended + 1 < pages.length) {
        setPageIndex(ended + 1);
        player.play({ lessonTitle: lesson?.title ?? '', pageIndex: ended + 1, sentences: pageSentences(pages[ended + 1]) });
        player.prefetch(pageSentences(pages[ended + 2]).map((s) => s.text));
      }
    };
  }, [pages, lesson?.title]);

  // Follow the audio if it moves to another page.
  useEffect(() => {
    if (audio.pageIndex !== null && audio.playing && audio.pageIndex !== pageIndex) setPageIndex(audio.pageIndex);
  }, [audio.pageIndex]);

  // Keep the spoken sentence in view.
  useEffect(() => {
    if (audio.sentenceIndex === null || audio.pageIndex !== pageIndex) return;
    document.querySelector(`[data-s="${audio.sentenceIndex}"]`)?.scrollIntoView({ block: 'center', behavior: 'smooth' });
  }, [audio.sentenceIndex]);

  // Keep the current word visible between the header and the word sheet.
  useEffect(() => {
    if (!selection) return;
    const el = document.querySelector(`.page-text [data-i="${selection.token.index}"]`);
    if (!el) return;
    const r = el.getBoundingClientRect();
    const top = (document.querySelector('.reader .bar')?.getBoundingClientRect().bottom ?? 0) + 12;
    const bottom = window.innerHeight - sheetHeight - 12;
    if (r.bottom > bottom) window.scrollBy({ top: r.bottom - bottom, behavior: 'smooth' });
    else if (r.top < top) window.scrollBy({ top: r.top - top, behavior: 'smooth' });
  }, [selection?.token.index, sheetHeight]);

  if (lesson === undefined) return <div class="screen"><p class="content muted">Loading…</p></div>;
  if (lesson === null || !text || !page) {
    return <div class="screen"><header class="bar"><a class="icon-btn" href="#/">←</a><h1>Not found</h1></header></div>;
  }

  const goTo = (p: number) => {
    if (p < 0 || p >= pages.length) return;
    setSelection(null);
    setPageIndex(p);
    window.scrollTo(0, 0);
  };

  const onTap = async (token: Token) => {
    if (!token.normalized) return;
    if (audio.playing) player.pause();
    const sentence = text.sentenceFor(token);
    const familyLemma = inheritedFor(token.normalized) !== null ? lemmas.get(token.normalized) ?? null : null;
    const sel: Selection = { token, sentence, result: null, improving: false, improveError: null, translation: null, familyLemma };
    setSelection(sel);
    await tapWord(token.normalized, sentence);
    translation(sentence).then((tr) => setSelection((cur) => (cur?.token === token ? { ...cur, translation: tr } : cur)));
    const result = await lookup(token.text, sentence);
    if ('gloss' in result && !result.fromOtherSentence) annotate(token.normalized, result.gloss);
    setSelection((cur) => (cur?.token === token ? { ...cur, result } : cur));
  };

  // Next/Prev in the sheet: the nearest still-highlighted word on this page; never turns the page.
  const statusFor = shownStatus;
  const selIndex = selection?.token.index;
  const selPos = selIndex === undefined ? -1 : page.tokens.findIndex((t) => t.index === selIndex);
  const stepTo = (dir: 1 | -1) => {
    const i = selPos < 0 ? null : nextHighlighted(page.tokens, statusFor, selPos, dir);
    return i === null ? null : () => onTap(page.tokens[i]);
  };

  const onImprove = async () => {
    if (!selection || selection.improving) return;
    const token = selection.token;
    const previous: Gloss | undefined = selection.result && 'gloss' in selection.result ? selection.result.gloss : undefined;
    setSelection({ ...selection, improving: true, improveError: null });
    const result = await improve(token.text, selection.sentence, previous);
    setSelection((cur) => {
      if (cur?.token !== token) return cur;
      return 'gloss' in result ? { ...cur, result, improving: false } : { ...cur, improving: false, improveError: result.error };
    });
  };

  const playPause = () => {
    if (audio.playing) return player.pause();
    if (player.loadedPage === page.index) return player.resume();
    player.play({ lessonTitle: lesson.title, pageIndex: page.index, sentences: pageSentences(page) });
    player.prefetch(pageSentences(pages[page.index + 1]).map((s) => s.text));
  };

  const blueCount = new Set(pageWordForms(page).filter((f) => shownStatus(f) === Status.NEW)).size;
  const isLast = page.index === pages.length - 1;

  return (
    <div class="screen reader">
      <header class="bar">
        <a class="icon-btn" href="#/" onClick={() => player.pause()}>←</a>
        <div class="grow">
          <h1 class="ellipsis">{lesson.title}</h1>
          <div class="small muted">Page {page.index + 1} of {pages.length}</div>
        </div>
        <button class="icon-btn" title="Smaller text" onClick={() => updateSettings({ fontSize: Math.max(14, settings.fontSize - 2) })}>A−</button>
        <button class="icon-btn" title="Larger text" onClick={() => updateSettings({ fontSize: Math.min(36, settings.fontSize + 2) })}>A+</button>
      </header>
      <main class="content" style={selection ? { paddingBottom: `${sheetHeight + 24}px` } : undefined} onTouchStart={swipeStart} onTouchEnd={(e) => swipeEnd(e, () => goTo(page.index + 1), () => goTo(page.index - 1))}>
        <PageText page={page} statusOf={shownStatus} spans={spans} selected={selection?.token ?? null}
          spoken={audio.pageIndex === page.index ? audio.sentenceIndex : null} fontSize={settings.fontSize} onTap={onTap} />
        <div class="page-nav">
          <button disabled={page.index === 0} onClick={() => goTo(page.index - 1)}>‹ Previous</button>
          {isLast
            ? <button class="primary" onClick={async () => {
              const n = await finishPage(text, page);
              player.pause();
              setToast(`Lesson finished: ${n} words added at level 1`);
              setTimeout(() => navigate('#/'), 800);
            }}>Finish lesson</button>
            : <button class="primary" onClick={() => goTo(page.index + 1)}>Next page ›</button>}
        </div>
        {blueCount > 0 && (
          <button class="link center" onClick={async () => setToast(`${await markPageKnown(page)} words marked Known`)}>
            Mark all {blueCount} blue words Known
          </button>
        )}
        <p class="small muted center">Turning the page adds the remaining blue words to your vocabulary at level 1.</p>
      </main>
      <footer class="player">
        {audio.error && <div class="error small">{audio.error}</div>}
        <div class="row spread">
          <button class="icon-btn" title="Previous sentence" onClick={() => player.previous()}>⏮</button>
          <button class="play" onClick={playPause} title={audio.playing ? 'Pause' : 'Play'}>{audio.loading ? '…' : audio.playing ? '❚❚' : '▶'}</button>
          <button class="icon-btn" title="Next sentence" onClick={() => player.next()}>⏭</button>
          <button class={'icon-btn' + (audio.loop ? ' on' : '')} title="Loop sentence" onClick={() => player.toggleLoop()}>🔂</button>
          <select value={String(settings.speed)} onChange={(e) => updateSettings({ speed: Number((e.target as HTMLSelectElement).value) })} title="Speed">
            {[0.5, 0.6, 0.7, 0.75, 0.8, 0.9, 1, 1.1, 1.25, 1.5, 1.75, 2].map((s) => <option value={String(s)}>{s}×</option>)}
          </select>
        </div>
      </footer>
      {toast && <div class="toast">{toast}</div>}
      {selection && (
        <WordSheet selection={selection} status={shownStatus(selection.token.normalized!)}
          phrases={spans.get(selection.token.index) ?? []} onImprove={onImprove} onClose={() => setSelection(null)}
          onPrev={stepTo(-1)} onNext={stepTo(1)} onHeight={setSheetHeight} />
      )}
    </div>
  );
}

let touchX = 0, touchY = 0;
function swipeStart(e: TouchEvent) {
  touchX = e.touches[0].clientX;
  touchY = e.touches[0].clientY;
}
function swipeEnd(e: TouchEvent, next: () => void, prev: () => void) {
  const dx = e.changedTouches[0].clientX - touchX;
  const dy = e.changedTouches[0].clientY - touchY;
  if (Math.abs(dx) > 70 && Math.abs(dx) > 2 * Math.abs(dy)) (dx < 0 ? next : prev)();
}

function PageText({ page, statusOf, spans, selected, spoken, fontSize, onTap }: {
  page: Page; statusOf: (form: string) => number; spans: Map<number, PhraseSpan[]>; selected: Token | null;
  spoken: number | null; fontSize: number; onTap: (t: Token) => void;
}) {
  // Trim leading/trailing whitespace tokens like the Android reader.
  let a = 0, b = page.tokens.length;
  while (a < b && page.tokens[a].kind === 'WHITESPACE') a++;
  while (b > a && page.tokens[b - 1].kind === 'WHITESPACE') b--;
  const tokens = page.tokens.slice(a, b);
  const underlined = new Set<number>();
  for (const t of tokens) spans.get(t.index)?.forEach((s) => {
    s.tokens.forEach((i) => underlined.add(i));
    s.tokens.forEach((i) => s.tokens.has(i + 2) && underlined.add(i + 1));
  });
  const byIndex = new Map(tokens.map((t) => [t.index, t]));
  return (
    <div class="page-text" style={{ fontSize: `${fontSize}px` }} lang="es"
      onClick={(e) => {
        const el = (e.target as HTMLElement).closest('[data-i]');
        const t = el && byIndex.get(Number(el.getAttribute('data-i')));
        if (t) onTap(t);
      }}>
      {tokens.map((t) => {
        const cls = [
          t.sentenceIndex === spoken ? 'spoken' : '',
          underlined.has(t.index) ? 'phrase' : '',
        ];
        if (t.kind !== 'WORD') return <span key={t.index} class={cls.join(' ')} data-s={t.sentenceIndex}>{t.text}</span>;
        const status = statusOf(t.normalized!);
        const bg = statusBackground(status);
        cls.push('w');
        if (bg) cls.push('hl');
        if (selected?.index === t.index) cls.push('sel');
        return <span key={t.index} class={cls.join(' ')} style={bg ? { backgroundColor: bg } : undefined} data-i={t.index} data-s={t.sentenceIndex}>{t.text}</span>;
      })}
    </div>
  );
}
