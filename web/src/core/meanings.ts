// Port of :core gloss/LikelyMeanings.kt: what the device already holds for a word, without asking the AI.
// Checked against fixtures/likely-meanings.json (written by Kotlin GoldenFixtureTest).
import { type Gloss, tidyGloss } from './gloss.ts';
import { normalizeLemma } from './vocab.ts';

/** One stored spelling: its vocabulary lemma/translation (null without an entry) and cached glosses, newest first. */
export interface StoredForm { form: string; lemma?: string | null; translation?: string | null; glosses?: Gloss[] }

/** [forms] is empty for the tapped word's own meanings; else the related forms that gave it. */
export interface LikelyMeaning { text: string; count: number; forms: string[] }

export interface LikelyMeanings {
  lemma: string | null;
  lemmaMeaning: string | null;
  partsOfSpeech: string[];
  meanings: LikelyMeaning[];
  otherMeanings: string[];
  relatedForms: string[];
}

export const MAX_OWN = 5;
export const MAX_RELATED = 3;
export const MAX_OTHER = 5;
export const MAX_POS = 3;
/** Related spellings considered, in the order given (callers pass the most recently seen first). */
export const MAX_RELATED_FORMS = 8;

export const isEmptyMeanings = (m: LikelyMeanings) => m.meanings.length === 0 && m.otherMeanings.length === 0;

/** Comparison key for a meaning: NFC, lowercase, punctuation and spacing folded ("Bank." = "bank"). */
export function meaningKey(text: string | null | undefined): string | null {
  if (text == null) return null;
  const k = text.normalize('NFC').toLowerCase().replace(/[^\p{L}\p{N}]+/gu, ' ').trim();
  return k || null;
}

/** The word families [own] belongs to: its vocabulary lemma, its glosses' lemmas and its own form. */
export function familyKeys(own: StoredForm): Set<string> {
  const out = new Set<string>();
  const add = (k: string | null) => { if (k !== null) out.add(k); };
  add(normalizeLemma(own.lemma));
  add(normalizeLemma(own.form));
  for (const g of own.glosses ?? []) add(normalizeLemma(tidyGloss(g).lemma));
  return out;
}

const entryRelated = (c: StoredForm, keys: Set<string>) => {
  const l = normalizeLemma(c.lemma);
  const f = normalizeLemma(c.form);
  return (l !== null && keys.has(l)) || (f !== null && keys.has(f));
};

const inFamily = (g: Gloss, keys: Set<string>) => {
  const l = normalizeLemma(g.lemma);
  return l !== null && keys.has(l);
};

/** Same word family as [keys]: by its vocabulary entry, or by one of its glosses' lemmas. */
export function isRelated(c: StoredForm, keys: Set<string>): boolean {
  return entryRelated(c, keys) || (c.glosses ?? []).some((g) => inFamily(tidyGloss(g), keys));
}

interface Item { first: number; count: number; spellings: Map<string, number>; forms: string[] }

class Tally {
  private items = new Map<string, Item>();
  private keyOf: (s: string | null | undefined) => string | null;
  constructor(keyOf: (s: string | null | undefined) => string | null = meaningKey) {
    this.keyOf = keyOf;
  }

  has(text: string | null | undefined): boolean {
    const k = this.keyOf(text);
    return k !== null && this.items.has(k);
  }

  add(text: string | null | undefined, form: string | null = null) {
    if (text == null) return;
    const t = text.trim();
    const key = this.keyOf(t);
    if (key === null) return;
    let item = this.items.get(key);
    if (!item) {
      item = { first: this.items.size, count: 0, spellings: new Map(), forms: [] };
      this.items.set(key, item);
    }
    item.count++;
    item.spellings.set(t, (item.spellings.get(t) ?? 0) + 1);
    if (form !== null && !item.forms.includes(form)) item.forms.push(form);
  }

  ranked(): Item[] {
    return [...this.items.values()].sort((a, b) => b.count - a.count || a.first - b.first);
  }
}

/** The most common spelling; the first seen on a tie. */
function display(item: Item): string {
  let best: [string, number] | null = null;
  for (const e of item.spellings) if (best === null || e[1] > best[1]) best = e;
  return best![0];
}

function pick(values: Array<string | null | undefined>): string | null {
  const t = new Tally(normalizeLemma);
  values.forEach((v) => t.add(v));
  const top = t.ranked()[0];
  return top ? display(top) : null;
}

/** See LikelyMeanings.build in Kotlin. */
export function buildLikelyMeanings(own: StoredForm, candidates: StoredForm[]): LikelyMeanings {
  const keys = familyKeys(own);
  const ownKey = meaningKey(own.form);
  const ownGlosses = (own.glosses ?? []).map(tidyGloss);
  const related = candidates
    .filter((c) => meaningKey(c.form) !== ownKey && isRelated(c, keys))
    .slice(0, MAX_RELATED_FORMS)
    .map((c) => [c, (c.glosses ?? []).map(tidyGloss).filter((g) => inFamily(g, keys))] as const);

  const ownTally = new Tally();
  ownGlosses.forEach((g) => ownTally.add(g.meaningInContext));
  if (!ownTally.has(own.translation)) ownTally.add(own.translation);

  const relatedTally = new Tally();
  for (const [c, glosses] of related) {
    const said = new Set(glosses.map((g) => meaningKey(g.meaningInContext)).filter((k) => k !== null));
    for (const g of glosses) if (!ownTally.has(g.meaningInContext)) relatedTally.add(g.meaningInContext, c.form);
    const tk = meaningKey(c.translation);
    if (entryRelated(c, keys) && !(tk !== null && said.has(tk)) && !ownTally.has(c.translation)) relatedTally.add(c.translation, c.form);
  }

  const otherTally = new Tally();
  const sources: Array<[Gloss, string | null]> = [
    ...ownGlosses.map((g) => [g, null] as [Gloss, null]),
    ...related.flatMap(([c, gs]) => gs.map((g) => [g, c.form] as [Gloss, string])),
  ];
  for (const [g, form] of sources) {
    for (const m of g.otherMeanings ?? []) if (!ownTally.has(m) && !relatedTally.has(m)) otherTally.add(m, form);
  }

  const ownShown = ownTally.ranked().slice(0, MAX_OWN).map((i) => ({ text: display(i), count: i.count, forms: [] as string[] }));
  const relatedItems = relatedTally.ranked().slice(0, MAX_RELATED);
  const otherItems = otherTally.ranked().slice(0, MAX_OTHER);
  const contributed = new Set([...relatedItems, ...otherItems].flatMap((i) => i.forms));

  const relatedGlosses = related.flatMap(([, gs]) => gs);
  const lemma = pick(ownGlosses.map((g) => g.lemma))
    ?? (own.lemma?.trim() || null)
    ?? pick(relatedGlosses.map((g) => g.lemma))
    ?? pick(related.map(([c]) => c.lemma));
  const lemmaKey = normalizeLemma(lemma);
  const lemmaMeaningOf = (gs: Gloss[]) => {
    const t = new Tally();
    gs.filter((g) => normalizeLemma(g.lemma) === lemmaKey).forEach((g) => t.add(g.lemmaMeaning));
    const top = t.ranked()[0];
    return top ? display(top) : null;
  };
  const posOf = (gs: Gloss[]) => {
    const t = new Tally();
    gs.forEach((g) => t.add(g.partOfSpeech));
    return t.ranked().slice(0, MAX_POS).map(display);
  };
  const ownPos = posOf(ownGlosses);

  return {
    lemma,
    lemmaMeaning: lemmaMeaningOf(ownGlosses) ?? lemmaMeaningOf(relatedGlosses),
    partsOfSpeech: ownPos.length ? ownPos : posOf(relatedGlosses),
    meanings: [...ownShown, ...relatedItems.map((i) => ({ text: display(i), count: i.count, forms: [...i.forms] }))],
    otherMeanings: otherItems.map(display),
    relatedForms: related.map(([c]) => c.form).filter((f) => contributed.has(f)),
  };
}
