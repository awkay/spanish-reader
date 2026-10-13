// Port of :core practice/VerbPractice.kt: verb pool, local random picks, prompts (from fixtures/prompts.json, generated
// by Kotlin), lenient reply parsing.
import prompts from '../../../fixtures/prompts.json' with { type: 'json' };
import { type Gloss, MalformedResponse, jsonCandidates, plainInfinitive } from './gloss.ts';
import type { VocabEntry } from './vocab.ts';

export interface PracticeVerb { infinitive: string; meaning: string | null; forms: string[] }
export interface Subject { id: string; spanish: string; english: string }
export interface VerbTense { id: string; label: string; example: string; instruction: string; subjects: string[] }
export interface PracticeSpec { verb: PracticeVerb; tense: VerbTense; subject: Subject; knownWords: string[] }
export interface VerbExercise { english: string; spanish: string; verbForm: string | null; verbMeaning: string | null }
export interface AnswerCheck { correct: boolean; feedback: string; hint: string | null }

export const SUBJECTS: Subject[] = prompts.practiceSubjects;
export const TENSES: VerbTense[] = prompts.practiceTenses;
export const MAX_TRIES: number = prompts.practiceMaxTries;
export const EXERCISE_SYSTEM: string = prompts.practiceExerciseSystem;
export const CHECK_SYSTEM: string = prompts.practiceCheckSystem;

export const subjectLabel = (s: Subject) => `${s.spanish} (${s.english})`;
const isExposed = (e: VocabEntry) => e.status >= 1 && e.status <= 5;

type Rand = () => number;
const pickOne = <T>(xs: T[], rand: Rand): T => xs[Math.floor(rand() * xs.length)];

function shuffled<T>(xs: T[], rand: Rand): T[] {
  const a = [...xs];
  for (let i = a.length - 1; i > 0; i--) {
    const j = Math.floor(rand() * (i + 1));
    [a[i], a[j]] = [a[j], a[i]];
  }
  return a;
}

/** The verbs among the exposed vocabulary, by plain infinitive (a form counts when one of its glosses says verb). */
export function practiceVerbs(entries: VocabEntry[], glossesOf: (form: string) => Gloss[]): PracticeVerb[] {
  const forms = new Map<string, string[]>();
  const meanings = new Map<string, string>();
  for (const e of entries) {
    if (!isExposed(e)) continue;
    for (const g of glossesOf(e.form)) {
      if (!g.verb) continue;
      const inf = plainInfinitive(g.verb.infinitive) ?? plainInfinitive(g.lemma);
      if (!inf) continue;
      const list = forms.get(inf) ?? [];
      if (!list.includes(e.form)) list.push(e.form);
      forms.set(inf, list);
      const m = g.lemmaMeaning?.trim();
      if (m && !meanings.has(inf)) meanings.set(inf, m);
    }
  }
  return [...forms].map(([infinitive, fs]) => ({ infinitive, meaning: meanings.get(infinitive) ?? null, forms: fs }))
    .sort((a, b) => (a.infinitive < b.infinitive ? -1 : a.infinitive > b.infinitive ? 1 : 0));
}

/** Up to [count] distinct exposed words (lemma where known), in random order, without [exclude]. */
export function knownWords(entries: VocabEntry[], exclude: Set<string>, count = 100, rand: Rand = Math.random): string[] {
  const words = new Set<string>();
  for (const e of entries) {
    if (!isExposed(e)) continue;
    const w = e.lemma?.trim().toLowerCase() || e.form;
    if (!exclude.has(w) && /\p{L}/u.test(w)) words.add(w);
  }
  return shuffled([...words], rand).slice(0, count);
}

/** A random verb (avoiding [recent] while others remain), tense and subject; null when there are no verbs yet. */
export function pickExercise(
  verbs: PracticeVerb[], entries: VocabEntry[], rand: Rand = Math.random, recent: string[] = [], tenses: VerbTense[] = TENSES,
): PracticeSpec | null {
  if (verbs.length === 0 || tenses.length === 0) return null;
  const fresh = verbs.filter((v) => !recent.includes(v.infinitive));
  const verb = pickOne(fresh.length ? fresh : verbs, rand);
  const tense = pickOne(tenses, rand);
  const subjectId = pickOne(tense.subjects, rand);
  const subject = SUBJECTS.find((s) => s.id === subjectId)!;
  const exclude = new Set([verb.infinitive, ...verb.forms]);
  return { verb, tense, subject, knownWords: knownWords(entries, exclude, 100, rand) };
}

const reminder = (isRetry: boolean) => (isRetry ? '\nYour previous reply could not be parsed. Reply with valid JSON only.' : '');

export function exerciseUserPrompt(spec: PracticeSpec, isRetry = false): string {
  const o: Record<string, unknown> = { verb: spec.verb.infinitive };
  if (spec.verb.meaning != null) o.verbMeaning = spec.verb.meaning;
  Object.assign(o, {
    tense: spec.tense.label, tenseNote: spec.tense.instruction, example: spec.tense.example,
    subject: subjectLabel(spec.subject), knownWords: spec.knownWords,
  });
  return 'Write one exercise:\n' + JSON.stringify(o) + reminder(isRetry);
}

/** Hints get stronger with each try; the last try gets none because the model answer is shown. */
export function howToHint(tryNumber: number, ex: VerbExercise): string {
  if (tryNumber >= MAX_TRIES) return 'none';
  if (tryNumber === 1) return 'say where the problem is and which rule applies, without any Spanish words from the model answer';
  return 'give the correct verb form exactly' + (ex.verbForm != null ? ` ("${ex.verbForm}")` : '') +
    ' and explain how the sentence is built, without giving the whole sentence';
}

export function checkUserPrompt(spec: PracticeSpec, ex: VerbExercise, earlier: string[], answer: string, isRetry = false): string {
  const o: Record<string, unknown> = {
    english: ex.english, verb: spec.verb.infinitive, tense: spec.tense.label, subject: subjectLabel(spec.subject), modelAnswer: ex.spanish,
  };
  if (ex.verbForm != null) o.modelVerbForm = ex.verbForm;
  Object.assign(o, { earlierTries: earlier, try: earlier.length + 1, answer, howToHint: howToHint(earlier.length + 1, ex) });
  return 'Check this answer:\n' + JSON.stringify(o) + reminder(isRetry);
}

function str(o: Record<string, unknown>, ...keys: string[]): string | null {
  for (const k of keys) {
    const v = o[k];
    if (typeof v === 'string' && v.trim()) return v.trim();
  }
  return null;
}

const isObject = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);

export function parseExercise(text: string): VerbExercise {
  for (const o of jsonCandidates(text)) {
    if (!isObject(o)) continue;
    const english = str(o, 'english');
    const spanish = str(o, 'spanish');
    if (!english || !spanish) continue;
    return { english, spanish, verbForm: str(o, 'verbForm', 'verb_form'), verbMeaning: str(o, 'verbMeaning', 'verb_meaning') };
  }
  throw new MalformedResponse(`No exercise in reply: ${text.slice(0, 200)}`);
}

export function parseCheck(text: string): AnswerCheck {
  for (const o of jsonCandidates(text)) {
    if (!isObject(o)) continue;
    const c = o.correct;
    if (typeof c !== 'boolean' && typeof c !== 'string') continue;
    const correct = c === true || (typeof c === 'string' && c.toLowerCase() === 'true');
    return { correct, feedback: str(o, 'feedback') ?? (correct ? 'Correct!' : 'Not quite.'), hint: correct ? null : str(o, 'hint') };
  }
  throw new MalformedResponse(`No check result in reply: ${text.slice(0, 200)}`);
}

/** Same words as [reference], ignoring case, punctuation and spacing (accents still count). Saves an AI call. */
export function sameAnswer(answer: string, reference: string): boolean {
  const norm = (s: string) => s.normalize('NFC').toLowerCase().replace(/[^\p{L}\p{N}]+/gu, ' ').trim();
  return norm(answer) !== '' && norm(answer) === norm(reference);
}

type Complete = (system: string, user: string) => Promise<string>;

async function twice<T>(block: (retry: boolean) => Promise<T>): Promise<T> {
  try {
    return await block(false);
  } catch (e) {
    if (!(e instanceof MalformedResponse)) throw e;
    return block(true);
  }
}

export const generateExercise = (complete: Complete, spec: PracticeSpec) =>
  twice(async (retry) => parseExercise(await complete(EXERCISE_SYSTEM, exerciseUserPrompt(spec, retry))));

export async function checkAnswer(complete: Complete, spec: PracticeSpec, ex: VerbExercise, earlier: string[], answer: string): Promise<AnswerCheck> {
  if (sameAnswer(answer, ex.spanish)) return { correct: true, feedback: 'Correct!', hint: null };
  const c = await twice(async (retry) => parseCheck(await complete(CHECK_SYSTEM, checkUserPrompt(spec, ex, earlier, answer, retry))));
  // Small models sometimes echo the instruction instead of writing a hint.
  const echoed = c.hint != null && howToHint(earlier.length + 1, ex).toLowerCase().startsWith(c.hint.slice(0, 30).toLowerCase());
  return earlier.length + 1 >= MAX_TRIES || echoed ? { ...c, hint: null } : c;
}
