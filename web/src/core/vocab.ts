// Port of :core vocab/ (WordStatus, VocabularyRules, LessonStats).

export const Status = {
  NEW: 0, LEVEL_1: 1, RECOGNIZED: 2, FAMILIAR: 3, LEARNED: 4, KNOWN: 5, IGNORED: -1,
} as const;
export type Status = (typeof Status)[keyof typeof Status];

export const STATUS_LABEL: Record<number, string> = {
  0: 'New', 1: 'Level 1', 2: 'Recognized', 3: 'Familiar', 4: 'Learned', 5: 'Known', [-1]: 'Ignored',
};

export const isLearning = (s: number) => s >= 1 && s <= 4;
/** 1.0 for LEVEL_1 down to 0.25 for LEARNED; 0 otherwise. */
export const highlightIntensity = (s: number) => (isLearning(s) ? (5 - s) / 4 : 0);

export interface VocabEntry {
  form: string;
  lemma: string | null;
  status: number;
  translation: string | null;
  contextSentence: string | null;
  firstSeenMillis: number;
  lastSeenMillis: number;
  timesSeen: number;
}

export interface WordDetail { contextSentence?: string | null; lemma?: string | null; translation?: string | null }

/** A tapped word: absent or NEW → added at LEVEL_1; anything else unchanged. */
export function onTap(entry: VocabEntry | undefined, form: string, now: number, contextSentence: string | null): VocabEntry {
  if (!entry) {
    return { form, lemma: null, status: Status.LEVEL_1, translation: null, contextSentence, firstSeenMillis: now, lastSeenMillis: now, timesSeen: 1 };
  }
  if (entry.status === Status.NEW) {
    return { ...entry, status: Status.LEVEL_1, contextSentence: entry.contextSentence ?? contextSentence, lastSeenMillis: now };
  }
  return entry;
}

export function setStatus(entry: VocabEntry, status: number, now: number): VocabEntry {
  return entry.status === status ? entry : { ...entry, status, lastSeenMillis: now };
}

/** Distinct forms on a page that are still NEW (or absent), in page order. */
export function newFormsOnPage(forms: string[], statuses: Map<string, number>): string[] {
  const seen = new Set<string>();
  const out: string[] = [];
  for (const f of forms) {
    if (seen.has(f)) continue;
    seen.add(f);
    if ((statuses.get(f) ?? Status.NEW) === Status.NEW) out.push(f);
  }
  return out;
}

/** Turning past a page: still-NEW words enter at LEVEL_1 with sentence and AI details. Never KNOWN. */
export function applyPageFinished(
  forms: string[], entries: Map<string, VocabEntry>, now: number, details: Map<string, WordDetail>,
): VocabEntry[] {
  const statuses = new Map([...entries].map(([k, v]) => [k, v.status]));
  return newFormsOnPage(forms, statuses).map((form) => {
    const d = details.get(form);
    const e = entries.get(form);
    if (e) {
      return {
        ...e, status: Status.LEVEL_1, lemma: e.lemma ?? d?.lemma ?? null, translation: e.translation ?? d?.translation ?? null,
        contextSentence: e.contextSentence ?? d?.contextSentence ?? null, lastSeenMillis: now,
      };
    }
    return {
      form, lemma: d?.lemma ?? null, status: Status.LEVEL_1, translation: d?.translation ?? null,
      contextSentence: d?.contextSentence ?? null, firstSeenMillis: now, lastSeenMillis: now, timesSeen: 1,
    };
  });
}

/** The learner's explicit "all blue words here are known". */
export function markNewAsKnown(forms: string[], entries: Map<string, VocabEntry>, now: number): VocabEntry[] {
  const statuses = new Map([...entries].map(([k, v]) => [k, v.status]));
  return newFormsOnPage(forms, statuses).map((form) => {
    const e = entries.get(form);
    return e ? { ...e, status: Status.KNOWN, lastSeenMillis: now }
      : { form, lemma: null, status: Status.KNOWN, translation: null, contextSentence: null, firstSeenMillis: now, lastSeenMillis: now, timesSeen: 1 };
  });
}

export interface LessonStats { totalWords: number; uniqueWords: number; newCount: number; learningCount: number; knownPercent: number }

export function lessonStats(forms: string[], statuses: Map<string, number>): LessonStats {
  const unique = new Set(forms);
  let newCount = 0, learning = 0, known = 0;
  for (const f of unique) {
    const s = statuses.get(f) ?? Status.NEW;
    if (s === Status.NEW) newCount++;
    else if (isLearning(s)) learning++;
    else if (s === Status.KNOWN) known++;
  }
  return { totalWords: forms.length, uniqueWords: unique.size, newCount, learningCount: learning, knownPercent: unique.size ? (known * 100) / unique.size : 0 };
}

/**
 * Reader Next/Prev: position in `tokens` of the nearest word after (`dir` 1) or before (-1) position `from` that is
 * still highlighted (NEW or LEVEL_1..LEARNED). KNOWN, IGNORED and non-word tokens are skipped. Null at the page edge.
 */
export function nextHighlighted(
  tokens: ReadonlyArray<{ normalized: string | null }>, statusOf: (form: string) => number, from: number, dir: 1 | -1,
): number | null {
  for (let i = from + dir; i >= 0 && i < tokens.length; i += dir) {
    const form = tokens[i].normalized;
    if (form === null) continue;
    const s = statusOf(form);
    if (s === Status.NEW || isLearning(s)) return i;
  }
  return null;
}
