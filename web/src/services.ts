// App services: vocabulary, glossing (local cache → shared server cache → AI), sentence analysis, pre-gloss,
// sharing, backup. Mirrors the Android app's repositories and GlossService.
import { api, type SentenceData } from './api.ts';
import {
  GLOSS_SYSTEM, MalformedResponse, SENTENCE_SYSTEM, type FoundPhrase, type Gloss, type GlossRequest, type SentenceAnalysis,
  glossUserPrompt, pageWindow, parseGlosses, parseSentences, planPreGloss, sentenceUserPrompt,
} from './core/gloss.ts';
import { formKey, sentenceHash } from './core/hash.ts';
import { type Page, type TokenizedText, cleanImport, paginate, pageWordForms, suggestTitle, tokenize } from './core/text.ts';
import {
  type VocabEntry, type WordDetail, Status, applyPageFinished, markNewAsKnown, onTap, setStatus,
} from './core/vocab.ts';
import * as db from './db.ts';
import type { GlossRow, Lesson, SentenceRow } from './db.ts';

// ---------- tiny observable ----------

export class Observable<T> {
  private listeners = new Set<(v: T) => void>();
  private value: T;
  constructor(v: T) {
    this.value = v;
  }
  get(): T {
    return this.value;
  }
  set(v: T) {
    this.value = v;
    this.listeners.forEach((l) => l(v));
  }
  subscribe(l: (v: T) => void): () => void {
    this.listeners.add(l);
    return () => this.listeners.delete(l);
  }
}

// ---------- settings ----------

export interface Settings {
  fontSize: number;
  wordsPerPage: number;
  preGlossPagesAhead: number;
  preGlossSentencesPerWord: number;
  speed: number;
  joinWrappedLines: boolean;
  name: string; // shown as "shared by"
}

export const DEFAULT_SETTINGS: Settings = {
  fontSize: 20, wordsPerPage: 250, preGlossPagesAhead: 3, preGlossSentencesPerWord: 3, speed: 1, joinWrappedLines: true, name: '',
};

export const settings = new Observable<Settings>(DEFAULT_SETTINGS);

export async function loadSettings() {
  const stored = await db.get<Partial<Settings>>('settings', 'settings');
  settings.set({ ...DEFAULT_SETTINGS, ...stored });
}

export async function updateSettings(patch: Partial<Settings>) {
  const next = { ...settings.get(), ...patch };
  settings.set(next);
  await db.put('settings', next, 'settings');
}

// ---------- vocabulary ----------

/** All vocabulary entries by form, kept in memory (a few thousand rows at most). */
export const vocab = new Observable<Map<string, VocabEntry>>(new Map());

export async function loadVocab() {
  const rows = await db.getAll<VocabEntry>('vocab');
  vocab.set(new Map(rows.map((r) => [r.form, r])));
}

export const statusOf = (form: string) => vocab.get().get(form)?.status ?? Status.NEW;

async function saveEntries(entries: VocabEntry[]) {
  if (entries.length === 0) return;
  await db.putAll('vocab', entries);
  const m = new Map(vocab.get());
  entries.forEach((e) => m.set(e.form, e));
  vocab.set(m);
}

export async function tapWord(form: string, sentence: string) {
  const cur = vocab.get().get(form);
  const next = onTap(cur, form, Date.now(), sentence);
  if (next !== cur) await saveEntries([next]);
}

export async function setWordStatus(form: string, status: number, sentence?: string) {
  const now = Date.now();
  const cur = vocab.get().get(form) ?? {
    form, lemma: null, status: Status.NEW, translation: null, contextSentence: sentence ?? null, firstSeenMillis: now, lastSeenMillis: now, timesSeen: 1,
  };
  await saveEntries([setStatus(cur, status, now)]);
}

export async function forgetWord(form: string) {
  await db.del('vocab', form);
  const m = new Map(vocab.get());
  m.delete(form);
  vocab.set(m);
}

/** Fills lemma/translation from a gloss where missing; status untouched. */
export async function annotate(form: string, g: Gloss) {
  const cur = vocab.get().get(form);
  if (!cur) return;
  const next = { ...cur, lemma: cur.lemma ?? g.lemma, translation: cur.translation ?? g.meaningInContext };
  if (next.lemma !== cur.lemma || next.translation !== cur.translation) await saveEntries([next]);
}

/** Turning past a page: still-blue words go in at LEVEL_1 with their sentence and any cached AI meaning. */
export async function finishPage(text: TokenizedText, page: Page): Promise<number> {
  const forms = pageWordForms(page);
  const firstSentence = new Map<string, string>();
  for (const t of page.tokens) if (t.normalized && !firstSentence.has(t.normalized)) firstSentence.set(t.normalized, text.sentenceFor(t));
  const details = new Map<string, WordDetail>();
  for (const [form, sentence] of firstSentence) {
    if (statusOf(form) !== Status.NEW) continue;
    const row = (await db.get<GlossRow>('glosses', db.glossKey(await sentenceHash(sentence), form))) ?? (await db.latestGlossForForm(form));
    details.set(form, { contextSentence: sentence, lemma: row?.gloss.lemma ?? null, translation: row?.gloss.meaningInContext ?? null });
  }
  const added = applyPageFinished(forms, vocab.get(), Date.now(), details);
  await saveEntries(added);
  return added.length;
}

export async function markPageKnown(page: Page): Promise<number> {
  const marked = markNewAsKnown(pageWordForms(page), vocab.get(), Date.now());
  await saveEntries(marked);
  return marked.length;
}

// ---------- lessons ----------

export const lessons = new Observable<Lesson[]>([]);

export async function loadLessons() {
  const rows = await db.getAll<Lesson>('lessons');
  lessons.set(rows.sort((a, b) => b.createdAt - a.createdAt));
}

const newId = () => crypto.randomUUID();

export async function createLesson(title: string, rawText: string, joinWrappedLines: boolean, sharedId: string | null = null): Promise<Lesson> {
  const text = cleanImport(rawText, joinWrappedLines);
  if (!text.trim()) throw new Error('The text is empty');
  const lesson: Lesson = { id: newId(), title: title.trim() || suggestTitle(text), text, createdAt: Date.now(), currentPage: 0, sharedId };
  await db.put('lessons', lesson);
  await loadLessons();
  return lesson;
}

export async function saveLesson(l: Lesson) {
  await db.put('lessons', l);
  await loadLessons();
}

export async function deleteLesson(id: string) {
  await db.del('lessons', id);
  await loadLessons();
}

/** Copies a shared lesson into this device's library (once; returns the existing copy if already added). */
export async function addSharedLesson(id: string): Promise<Lesson> {
  const existing = lessons.get().find((l) => l.sharedId === id);
  if (existing) return existing;
  const shared = await api.lesson(id);
  const lesson: Lesson = { id: newId(), title: shared.title, text: shared.text, createdAt: Date.now(), currentPage: 0, sharedId: shared.id };
  await db.put('lessons', lesson);
  await loadLessons();
  return lesson;
}

/** Shares a local lesson plus every AI result this device has for its sentences. Never vocabulary. */
export async function shareLesson(l: Lesson): Promise<void> {
  const text = tokenize(l.text);
  const sentences: SentenceData[] = [];
  for (let i = 0; i < text.sentenceCount; i++) {
    const s = text.sentenceText(i);
    const hash = await sentenceHash(s);
    const row = await db.get<SentenceRow>('sentences', hash);
    const glosses: Record<string, Gloss> = {};
    for (const t of text.sentenceTokens(i)) {
      if (!t.normalized) continue;
      const g = await db.get<GlossRow>('glosses', db.glossKey(hash, t.normalized));
      if (g) glosses[t.normalized] = g.gloss;
    }
    if (row || Object.keys(glosses).length) {
      sentences.push({ hash, translation: row?.translation ?? undefined, phrases: row?.phrases, scanned: row?.scanned, glosses });
    }
  }
  const summary = await api.shareLesson({ title: l.title, text: l.text, sharedBy: settings.get().name || undefined, sentences });
  await saveLesson({ ...l, sharedId: summary.id });
}

// ---------- AI ----------

const AI_BATCH = 20;
const SENTENCE_BATCH = 15;

async function limited<T>(tasks: Array<() => Promise<T>>, concurrency: number): Promise<T[]> {
  const results: T[] = new Array(tasks.length);
  let next = 0;
  await Promise.all(Array.from({ length: Math.min(concurrency, tasks.length) }, async () => {
    while (next < tasks.length) {
      const i = next++;
      results[i] = await tasks[i]();
    }
  }));
  return results;
}

/** Glosses requests in batches of 20 (2 at a time), retrying once on an unparseable reply. Missing ids = failed. */
export async function glossBatch(requests: GlossRequest[], improve = false): Promise<Map<string, Gloss>> {
  const out = new Map<string, Gloss>();
  const batches: GlossRequest[][] = [];
  for (let i = 0; i < requests.length; i += AI_BATCH) batches.push(requests.slice(i, i + AI_BATCH));
  let lastError: unknown = null;
  await limited(batches.map((batch) => async () => {
    let pending = batch;
    for (let attempt = 0; attempt < 2 && pending.length; attempt++) {
      try {
        const reply = await api.ai(GLOSS_SYSTEM, glossUserPrompt(pending, attempt > 0), pending.length, improve);
        parseGlosses(reply, pending).forEach((g, id) => out.set(id, g));
      } catch (e) {
        lastError = e;
        if (!(e instanceof MalformedResponse)) break;
      }
      pending = pending.filter((r) => !out.has(r.id));
    }
  }), 2);
  if (out.size === 0 && requests.length && lastError) throw lastError;
  return out;
}

async function storeGlosses(items: Array<{ form: string; sentence: string; gloss: Gloss }>, toServer = true) {
  if (items.length === 0) return;
  const rows: GlossRow[] = [];
  const bySentence = new Map<string, Record<string, Gloss>>();
  for (const it of items) {
    const hash = await sentenceHash(it.sentence);
    const fk = formKey(it.form);
    rows.push({ key: db.glossKey(hash, fk), formKey: fk, hash, gloss: it.gloss, storedAt: Date.now() });
    const g = bySentence.get(hash) ?? {};
    g[fk] = it.gloss;
    bySentence.set(hash, g);
  }
  await db.putAll('glosses', rows);
  for (const it of items) {
    if (it.gloss.isIdiomOrPhrase && it.gloss.phrase && it.gloss.phrase.includes(' ')) {
      await addPhrases(it.sentence, [{ phrase: it.gloss.phrase, meaning: it.gloss.phraseMeaning ?? '' }]);
    }
  }
  if (toServer) {
    api.cachePut([...bySentence].map(([hash, glosses]) => ({ hash, glosses }))).catch(() => {});
  }
}

/** Pulls whatever the shared server cache has for these sentences into the local cache. */
export async function pullServerCache(sentences: string[]): Promise<void> {
  const hashes = [...new Set(await Promise.all(sentences.map(sentenceHash)))];
  for (let i = 0; i < hashes.length; i += 500) {
    let data: Record<string, SentenceData>;
    try {
      data = await api.cacheGet(hashes.slice(i, i + 500));
    } catch {
      return;
    }
    const glossRows: GlossRow[] = [];
    const sentenceRows: SentenceRow[] = [];
    for (const [hash, d] of Object.entries(data)) {
      for (const [fk, gloss] of Object.entries(d.glosses ?? {})) glossRows.push({ key: db.glossKey(hash, fk), formKey: fk, hash, gloss, storedAt: Date.now() });
      if (d.translation || d.scanned || d.phrases?.length) {
        const cur = await db.get<SentenceRow>('sentences', hash);
        sentenceRows.push({
          hash,
          translation: d.translation ?? cur?.translation ?? null,
          phrases: mergePhrases(cur?.phrases ?? [], d.phrases ?? []),
          scanned: Boolean(d.scanned || cur?.scanned),
        });
      }
    }
    await db.putAll('glosses', glossRows);
    await db.putAll('sentences', sentenceRows);
    if (sentenceRows.length) phraseVersion.set(phraseVersion.get() + 1);
  }
}

const mergePhrases = (a: FoundPhrase[], b: FoundPhrase[]) => {
  const out = [...a];
  for (const p of b) if (!out.some((x) => x.phrase.toLowerCase() === p.phrase.toLowerCase())) out.push(p);
  return out;
};

/** Bumped whenever phrases/translations change, so open readers re-read them. */
export const phraseVersion = new Observable(0);

async function addPhrases(sentence: string, phrases: FoundPhrase[]) {
  const hash = await sentenceHash(sentence);
  const cur = await db.get<SentenceRow>('sentences', hash);
  await db.put('sentences', { hash, translation: cur?.translation ?? null, phrases: mergePhrases(cur?.phrases ?? [], phrases), scanned: cur?.scanned ?? false });
  phraseVersion.set(phraseVersion.get() + 1);
}

export type Lookup = { gloss: Gloss; fromOtherSentence: boolean } | { error: string };

/** Tap-time: local cache → shared server cache → live AI (cached) → any sentence's gloss of this form. */
export async function lookup(form: string, sentence: string): Promise<Lookup> {
  const hash = await sentenceHash(sentence);
  const fk = formKey(form);
  const local = await db.get<GlossRow>('glosses', db.glossKey(hash, fk));
  if (local) return { gloss: local.gloss, fromOtherSentence: false };
  await pullServerCache([sentence]);
  const pulled = await db.get<GlossRow>('glosses', db.glossKey(hash, fk));
  if (pulled) return { gloss: pulled.gloss, fromOtherSentence: false };
  let error = 'Lookup failed';
  try {
    const got = await glossBatch([{ id: '1', form, sentence }]);
    const g = got.get('1');
    if (g) {
      await storeGlosses([{ form, sentence, gloss: g }]);
      return { gloss: g, fromOtherSentence: false };
    }
  } catch (e) {
    error = (e as Error).message;
  }
  const any = await db.latestGlossForForm(fk);
  return any ? { gloss: any.gloss, fromOtherSentence: true } : { error };
}

/** "Improve answer": the stronger model sees the previous answer; the result replaces it everywhere. */
export async function improve(form: string, sentence: string, previous: Gloss | undefined): Promise<Lookup> {
  try {
    const got = await glossBatch([{ id: '1', form, sentence, previous }], true);
    const g = got.get('1');
    if (!g) return { error: 'The model returned nothing usable' };
    await storeGlosses([{ form, sentence, gloss: g }]);
    return { gloss: g, fromOtherSentence: false };
  } catch (e) {
    return { error: (e as Error).message };
  }
}

/** Translation + idioms for sentences without a stored translation; batches of 15. */
export async function analyzeSentences(sentences: string[]): Promise<void> {
  const todo: string[] = [];
  for (const s of [...new Set(sentences)]) {
    if (!/\p{L}/u.test(s)) continue;
    const row = await db.get<SentenceRow>('sentences', await sentenceHash(s));
    if (!row?.translation) todo.push(s);
  }
  const batches: string[][] = [];
  for (let i = 0; i < todo.length; i += SENTENCE_BATCH) batches.push(todo.slice(i, i + SENTENCE_BATCH));
  await limited(batches.map((batch) => async () => {
    const ids = batch.map((_, i) => String(i));
    let result = new Map<string, SentenceAnalysis>();
    for (let attempt = 0; attempt < 2; attempt++) {
      try {
        result = parseSentences(await api.ai(SENTENCE_SYSTEM, sentenceUserPrompt(batch.map((s, i) => [String(i), s]), attempt > 0), batch.length), ids);
        break;
      } catch (e) {
        if (!(e instanceof MalformedResponse)) return;
      }
    }
    const put: SentenceData[] = [];
    for (const [id, a] of result) {
      const s = batch[Number(id)];
      const hash = await sentenceHash(s);
      const cur = await db.get<SentenceRow>('sentences', hash);
      await db.put('sentences', { hash, translation: a.translation ?? cur?.translation ?? null, phrases: mergePhrases(cur?.phrases ?? [], a.phrases), scanned: true });
      put.push({ hash, translation: a.translation ?? undefined, phrases: a.phrases, scanned: true });
    }
    if (put.length) {
      phraseVersion.set(phraseVersion.get() + 1);
      api.cachePut(put).catch(() => {});
    }
  }), 2);
}

export async function translation(sentence: string): Promise<string | { error: string }> {
  const hash = await sentenceHash(sentence);
  let row = await db.get<SentenceRow>('sentences', hash);
  if (row?.translation) return row.translation;
  await pullServerCache([sentence]);
  row = await db.get<SentenceRow>('sentences', hash);
  if (row?.translation) return row.translation;
  await analyzeSentences([sentence]);
  row = await db.get<SentenceRow>('sentences', hash);
  return row?.translation ?? { error: "Couldn't get a translation" };
}

export async function sentenceRows(sentences: string[]): Promise<Map<string, SentenceRow>> {
  const out = new Map<string, SentenceRow>();
  for (const s of sentences) {
    const row = await db.get<SentenceRow>('sentences', await sentenceHash(s));
    if (row) out.set(s, row);
  }
  return out;
}

// ---------- pre-gloss ----------

export const preGlossStatus = new Observable<string | null>(null);
let preGlossRunning: Promise<void> | null = null;

/**
 * Prepares [count] pages from [fromPage]: pulls the shared cache first (free when the other reader already read
 * them), then translates/scans the sentences and glosses the remaining non-known words. Runs while the app is
 * open; one run at a time.
 */
export function preGloss(lesson: Lesson, fromPage: number, count: number): Promise<void> {
  const run = async () => {
    const s = settings.get();
    const text = tokenize(lesson.text);
    const window = pageWindow(paginate(text, s.wordsPerPage), fromPage, count);
    const sentences = [...new Set(window.map((t) => t.sentenceIndex))].map((i) => text.sentenceText(i));
    preGlossStatus.set('Checking shared cache…');
    await pullServerCache(sentences);
    preGlossStatus.set('Translating sentences…');
    await analyzeSentences(sentences);
    const statuses = new Map([...vocab.get()].map(([k, v]) => [k, v.status]));
    const plan = await planPreGloss(text, statuses, s.preGlossSentencesPerWord, window, sentenceHash);
    const todo: GlossRequest[] = [];
    for (const r of plan) {
      if (!(await db.get('glosses', db.glossKey(await sentenceHash(r.sentence), formKey(r.form))))) todo.push(r);
    }
    for (let i = 0; i < todo.length; i += 40) {
      preGlossStatus.set(`Looking up words ${i}/${todo.length}…`);
      const chunk = todo.slice(i, i + 40);
      const got = await glossBatch(chunk);
      await storeGlosses(chunk.flatMap((r) => (got.has(r.id) ? [{ form: r.form, sentence: r.sentence, gloss: got.get(r.id)! }] : [])));
    }
  };
  const chained = (preGlossRunning ?? Promise.resolve()).then(run, run)
    .catch((e) => preGlossStatus.set('Pre-gloss stopped: ' + (e as Error).message))
    .finally(() => {
      if (preGlossRunning === chained) {
        preGlossRunning = null;
        if (!preGlossStatus.get()?.startsWith('Pre-gloss stopped')) preGlossStatus.set(null);
      }
    });
  preGlossRunning = chained;
  return chained;
}

// ---------- backup (same JSON format as the Android app) ----------

interface BackupFile {
  version: number;
  exportedAtMillis: number;
  lessons: Array<{ title: string; text: string; createdAtMillis: number; currentPage: number }>;
  vocab: VocabEntry[];
}

export async function exportBackup(): Promise<string> {
  const ls = await db.getAll<Lesson>('lessons');
  const backup: BackupFile = {
    version: 1,
    exportedAtMillis: Date.now(),
    lessons: ls.map((l) => ({ title: l.title, text: l.text, createdAtMillis: l.createdAt, currentPage: l.currentPage })),
    vocab: [...vocab.get().values()],
  };
  return JSON.stringify(backup, null, 2);
}

/** Merges like Android: the more recently seen entry wins its status; gaps are filled; nothing is deleted. */
export function mergeVocab(existing: VocabEntry | undefined, incoming: VocabEntry): VocabEntry | null {
  if (!existing) return incoming;
  const [newer, older] = incoming.lastSeenMillis > existing.lastSeenMillis ? [incoming, existing] : [existing, incoming];
  const merged: VocabEntry = {
    ...newer,
    lemma: newer.lemma ?? older.lemma,
    translation: newer.translation ?? older.translation,
    contextSentence: newer.contextSentence ?? older.contextSentence,
    firstSeenMillis: Math.min(newer.firstSeenMillis, older.firstSeenMillis),
    timesSeen: Math.max(newer.timesSeen, older.timesSeen),
  };
  return JSON.stringify(merged) === JSON.stringify(existing) ? null : merged;
}

export async function importBackup(json: string): Promise<string> {
  let b: BackupFile;
  try {
    b = JSON.parse(json);
  } catch {
    throw new Error('Not a Spanish Reader backup');
  }
  if (typeof b !== 'object' || !b || typeof b.exportedAtMillis !== 'number') throw new Error('Not a Spanish Reader backup');
  if ((b.version ?? 1) > 1) throw new Error('Backup is from a newer version');
  const existingKeys = new Set(lessons.get().map((l) => `${l.title.trim()}\u0000${l.text.trim()}`));
  let added = 0;
  for (const l of b.lessons ?? []) {
    if (existingKeys.has(`${l.title.trim()}\u0000${l.text.trim()}`)) continue;
    await db.put('lessons', { id: newId(), title: l.title, text: l.text, createdAt: l.createdAtMillis, currentPage: l.currentPage ?? 0 } satisfies Lesson);
    added++;
  }
  const updated: VocabEntry[] = [];
  for (const v of b.vocab ?? []) {
    const incoming: VocabEntry = {
      form: v.form, lemma: v.lemma ?? null, status: v.status, translation: v.translation ?? null, contextSentence: v.contextSentence ?? null,
      firstSeenMillis: v.firstSeenMillis, lastSeenMillis: v.lastSeenMillis, timesSeen: v.timesSeen ?? 1,
    };
    const m = mergeVocab(vocab.get().get(v.form), incoming);
    if (m) updated.push(m);
  }
  await saveEntries(updated);
  await loadLessons();
  return `Added ${added} lessons; updated ${updated.length} words.`;
}

export { paginate, tokenize };
