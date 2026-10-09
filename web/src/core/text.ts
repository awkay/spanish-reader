// Port of :core text/ (Tokenizer, SentenceSegmenter, Paginator, ImportCleaner, PhraseLocator, ListenScript).
// Behavior must match the Kotlin exactly; test/text.test.ts checks it against fixtures/text-golden.json.

export type TokenKind = 'WORD' | 'PUNCT' | 'WHITESPACE';

export interface Token {
  index: number;
  text: string;
  kind: TokenKind;
  start: number;
  /** Vocabulary key; only for WORD tokens. */
  normalized: string | null;
  sentenceIndex: number;
}

export interface Page {
  index: number;
  tokens: Token[];
}

const APOSTROPHES = new Set(["'", '’']);
const HYPHENS = new Set(['-', '‐', '‑']);
const LETTER = /\p{L}/u;
const MARK = /[\p{Mn}\p{Mc}\p{Me}]/u;
const DIGIT = /\p{Nd}/u;

const isMark = (c: string) => MARK.test(c);
const isWordChar = (c: string) => LETTER.test(c) || isMark(c);
const isJoiner = (c: string) => APOSTROPHES.has(c) || HYPHENS.has(c);
// Kotlin: Character.isWhitespace || Character.isSpaceChar.
const isSpace = (c: string) => c !== '﻿' && (/\s/u.test(c) || (c >= '\u001C' && c <= '\u001F'));

/** Vocabulary key for a word: NFC, Spanish lowercase, typographic apostrophe folded. Accents are kept. */
export function normalize(word: string): string {
  return word.normalize('NFC').replace(/’/g, "'").toLowerCase();
}

interface RawToken { text: string; kind: TokenKind; start: number }

function codePointAt(text: string, i: number): string {
  return String.fromCodePoint(text.codePointAt(i)!);
}

function splitRaw(text: string): RawToken[] {
  const out: RawToken[] = [];
  let i = 0;
  while (i < text.length) {
    const c = codePointAt(text, i);
    const start = i;
    if (isWordChar(c)) {
      i += c.length;
      while (i < text.length) {
        const d = codePointAt(text, i);
        if (isWordChar(d)) i += d.length;
        else if (isJoiner(text[i]) && i + 1 < text.length && isWordChar(codePointAt(text, i + 1))) i += 1;
        else break;
      }
      out.push({ text: text.slice(start, i), kind: 'WORD', start });
    } else if (isSpace(c)) {
      while (i < text.length && isSpace(codePointAt(text, i))) i += codePointAt(text, i).length;
      out.push({ text: text.slice(start, i), kind: 'WHITESPACE', start });
    } else if (DIGIT.test(c)) {
      // Numbers like 1.500 or 3,14 stay one token.
      i += 1;
      while (i < text.length) {
        const d = text[i];
        if (DIGIT.test(d)) i++;
        else if ((d === '.' || d === ',') && i + 1 < text.length && DIGIT.test(text[i + 1])) i++;
        else break;
      }
      out.push({ text: text.slice(start, i), kind: 'PUNCT', start });
    } else if (c === '.') {
      while (i < text.length && text[i] === '.') i++;
      out.push({ text: text.slice(start, i), kind: 'PUNCT', start });
    } else {
      i += c.length;
      // Keep combining marks with the base symbol so we never split a grapheme.
      while (i < text.length && isMark(codePointAt(text, i))) i += codePointAt(text, i).length;
      out.push({ text: text.slice(start, i), kind: 'PUNCT', start });
    }
  }
  return out;
}

// --- Sentence segmentation (SentenceSegmenter.kt) ---

const TERMINALS = new Set(['.', '!', '?', '…']);
const CLOSERS = new Set(['»', '”', '’', '"', "'", ')', ']']);
const SKIPPABLE_BEFORE_NEXT_WORD = new Set(['—', '–', '-', '¿', '¡', '«', '“', '"', '(', "'", '‘']);
const ABBREVIATIONS = new Set([
  'sr', 'sra', 'srta', 'sres', 'dr', 'dra', 'lic', 'ing', 'prof', 'profa', 'arq',
  'ud', 'uds', 'vd', 'vds', 'etc', 'av', 'avda', 'pág', 'págs', 'núm', 'tel', 'aprox',
  'gral', 'cap', 'dpto', 'depto', 'cía', 'ej', 'fig', 'vol', 'ed', 'mr', 'mrs', 'st',
]);

const isTerminal = (t: RawToken) =>
  t.kind === 'PUNCT' && (TERMINALS.has(t.text) || (t.text.length > 1 && [...t.text].every((c) => c === '.')));

const isAttachedCloser = (tokens: RawToken[], i: number) =>
  CLOSERS.has(tokens[i].text) && i > 0 && tokens[i - 1].kind !== 'WHITESPACE';

const isUpper = (c: string) => c !== c.toLowerCase() && c === c.toUpperCase();
const isLower = (c: string) => c !== c.toUpperCase() && c === c.toLowerCase();
const firstChar = (s: string) => s[0];

function nextWordAfter(tokens: RawToken[], from: number): RawToken | null {
  let j = from;
  while (j < tokens.length) {
    const t = tokens[j];
    if (t.kind === 'WORD') return t;
    if (t.kind === 'WHITESPACE' && !t.text.includes('\n')) j++;
    else if (t.kind === 'PUNCT' && SKIPPABLE_BEFORE_NEXT_WORD.has(t.text)) j++;
    else return null;
  }
  return null;
}

function endsSentence(tokens: RawToken[], i: number): boolean {
  let j = i + 1;
  while (j < tokens.length && (isTerminal(tokens[j]) || isAttachedCloser(tokens, j))) j++;
  if (j < tokens.length && tokens[j].kind !== 'WHITESPACE') return false;
  const next = nextWordAfter(tokens, j);
  if (tokens[i].text === '.') {
    const prev = i > 0 ? tokens[i - 1] : undefined;
    if (prev && prev.kind === 'WORD') {
      const key = normalize(prev.text);
      if (key === 'etc') return next === null || isUpper(firstChar(next.text));
      if (ABBREVIATIONS.has(key)) return false;
      if (prev.text.length === 1 && isUpper(prev.text[0])) return false;
    }
  }
  return tokens[i].text === '.' || next === null || !isLower(firstChar(next.text));
}

function assignSentences(tokens: RawToken[]): number[] {
  const result = new Array<number>(tokens.length);
  let sentence = 0;
  let pendingBreak = false;
  let hasContent = false;
  tokens.forEach((t, i) => {
    if (pendingBreak && !(t.kind === 'WHITESPACE' || isAttachedCloser(tokens, i))) {
      sentence++;
      pendingBreak = false;
      hasContent = false;
    }
    result[i] = sentence;
    if (t.kind !== 'WHITESPACE') hasContent = true;
    if (t.kind === 'WHITESPACE' && t.text.includes('\n') && hasContent) pendingBreak = true;
    if (isTerminal(t) && endsSentence(tokens, i)) pendingBreak = true;
  });
  return result;
}

/** Kotlin String.trim(): strips whitespace and space separators at both ends. */
export function ktTrim(s: string): string {
  let a = 0;
  let b = s.length;
  while (a < b && isSpace(s[a])) a++;
  while (b > a && isSpace(s[b - 1])) b--;
  return s.slice(a, b);
}

/** A tokenized document: tokens plus sentence lookups. */
export class TokenizedText {
  readonly source: string;
  readonly tokens: Token[];
  readonly sentenceRanges: Array<[number, number]>; // [start, end) token indices

  constructor(source: string, tokens: Token[]) {
    this.source = source;
    this.tokens = tokens;
    const ranges: Array<[number, number]> = [];
    let start = 0;
    for (let i = 1; i <= tokens.length; i++) {
      if (i === tokens.length || tokens[i].sentenceIndex !== tokens[start].sentenceIndex) {
        ranges.push([start, i]);
        start = i;
      }
    }
    this.sentenceRanges = ranges;
  }

  get sentenceCount(): number { return this.sentenceRanges.length; }

  sentenceTokens(s: number): Token[] {
    const [a, b] = this.sentenceRanges[s];
    return this.tokens.slice(a, b);
  }

  /** The sentence's text with surrounding whitespace trimmed. */
  sentenceText(s: number): string {
    return ktTrim(this.sentenceTokens(s).map((t) => t.text).join(''));
  }

  sentenceFor(token: Token): string { return this.sentenceText(token.sentenceIndex); }
}

export function tokenize(text: string): TokenizedText {
  const raw = splitRaw(text);
  const sentences = assignSentences(raw);
  return new TokenizedText(text, raw.map((r, i) => ({
    index: i, text: r.text, kind: r.kind, start: r.start,
    normalized: r.kind === 'WORD' ? normalize(r.text) : null,
    sentenceIndex: sentences[i],
  })));
}

export const DEFAULT_WORDS_PER_PAGE = 250;

/** Pages of roughly [wordsPerPage] words, breaking only between sentences. */
export function paginate(text: TokenizedText, wordsPerPage = DEFAULT_WORDS_PER_PAGE): Page[] {
  const pages: Page[] = [];
  let pageStart = -1;
  let pageWords = 0;
  for (const [a, b] of text.sentenceRanges) {
    let words = 0;
    for (let k = a; k < b; k++) if (text.tokens[k].kind === 'WORD') words++;
    if (pageStart >= 0 && pageWords > 0 && pageWords + words > wordsPerPage) {
      pages.push({ index: pages.length, tokens: text.tokens.slice(pageStart, a) });
      pageStart = -1;
      pageWords = 0;
    }
    if (pageStart < 0) pageStart = a;
    pageWords += words;
  }
  if (pageStart >= 0) pages.push({ index: pages.length, tokens: text.tokens.slice(pageStart) });
  return pages;
}

export const pageWordForms = (p: Page) => p.tokens.flatMap((t) => (t.normalized ? [t.normalized] : []));

// --- ImportCleaner.kt ---

const LINE_ENDERS = new Set(['.', '!', '?', '…', ':', ';', '»', '”', '"', ')']);
const LINE_STARTERS = new Set(['—', '–', '-', '•', '*', '«', '“', '¿', '¡']);
const INVISIBLE = /[﻿​‌‍⁠­]/g;
const INLINE_SPACE = /[ \t   ]+/g;
const LIST_ITEM = /^[0-9]+[.)][ \t\n\x0B\f\r]/;

export function cleanImport(raw: string, joinWrappedLines = true): string {
  const lines = raw.replace(/\r\n/g, '\n').replace(/\r/g, '\n').replace(INVISIBLE, '')
    .split('\n').map((l) => ktTrim(l.replace(INLINE_SPACE, ' ')));
  let out = '';
  let inParagraph = false;
  for (const line of lines) {
    if (line.length === 0) {
      inParagraph = false;
      continue;
    }
    if (!inParagraph) {
      if (out.length > 0) out += '\n\n';
      out += line;
      inParagraph = true;
      continue;
    }
    const prevEnd = out[out.length - 1];
    if (!joinWrappedLines) out += '\n' + line;
    else if (prevEnd === '-' && out.length >= 2 && isWordLetter(out[out.length - 2]) && isLower(line[0])) out = out.slice(0, -1) + line;
    else if (LINE_ENDERS.has(prevEnd) || LINE_STARTERS.has(line[0]) || LIST_ITEM.test(line)) out += '\n' + line;
    else out += ' ' + line;
  }
  return out;
}

const isWordLetter = (c: string) => LETTER.test(c);

export function suggestTitle(text: string, maxLength = 60): string {
  const first = text.split(/\r\n|\n|\r/).map(ktTrim).find((l) => l.length > 0);
  if (first === undefined) return 'Untitled';
  if (first.length <= maxLength) return first;
  const cut = first.slice(0, maxLength);
  const space = cut.lastIndexOf(' ');
  return (space > maxLength / 2 ? cut.slice(0, space) : cut).replace(/[,;: ]+$/, '') + '…';
}

export function isJustUrl(text: string): boolean {
  const t = text.trim();
  return t.length > 0 && !t.includes(' ') && !t.includes('\n') && (t.startsWith('http://') || t.startsWith('https://'));
}

/**
 * True when the text is a single YouTube video link (watch, youtu.be, shorts, live). Only a hint for the import
 * screen; the server validates the link itself. Mirrors ImportCleaner.isYouTubeUrl.
 */
export function isYouTubeUrl(text: string): boolean {
  const t = text.trim();
  return !/\s/.test(t) && /^(https?:\/\/)?((www|m|music)\.)?(youtube\.com\/(watch\?(.*&)?v=|shorts\/|live\/)|youtu\.be\/)[A-Za-z0-9_-]{11}([?&#/].*)?$/i.test(t);
}

// --- PhraseLocator.kt ---

const fold = (s: string) => s.normalize('NFD').replace(/\p{M}/gu, '');

/** Word tokens of [sentence] forming [phrase] in order, up to [maxGap] words apart; tightest match; or null. */
export function locatePhrase(sentence: Token[], phrase: string, maxGap = 3): Token[] | null {
  const want = tokenize(phrase).tokens.filter((t) => t.kind === 'WORD').map((t) => fold(t.normalized!));
  if (want.length === 0) return null;
  const words = sentence.filter((t) => t.kind === 'WORD');
  const keys = words.map((t) => fold(t.normalized!));
  let best: number[] | null = null;
  for (let start = 0; start < keys.length; start++) {
    if (keys[start] !== want[0]) continue;
    const picked = [start];
    let pos = start;
    for (const w of want.slice(1)) {
      let next = -1;
      for (let k = pos + 1; k <= Math.min(keys.length - 1, pos + 1 + maxGap); k++) {
        if (keys[k] === w) { next = k; break; }
      }
      if (next < 0) break;
      picked.push(next);
      pos = next;
    }
    if (picked.length === want.length && (best === null || picked[picked.length - 1] - picked[0] < best[best.length - 1] - best[0])) {
      best = picked;
    }
  }
  return best ? best.map((i) => words[i]) : null;
}
