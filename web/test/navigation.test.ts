// Reader Next/Prev word navigation (same rule as the Android reader).
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { tokenize } from '../src/core/text.ts';
import { Status, nextHighlighted } from '../src/core/vocab.ts';

const tokens = tokenize('El perro, ya Juan come pan.').tokens;
const statuses = new Map<string, number>([['el', Status.KNOWN], ['ya', Status.KNOWN], ['juan', Status.IGNORED], ['come', Status.FAMILIAR]]);
const statusOf = (f: string) => statuses.get(f) ?? Status.NEW;
const at = (word: string) => tokens.findIndex((t) => t.text === word);
const word = (i: number | null) => (i === null ? null : tokens[i].text);

test('next skips known, ignored, punctuation and spaces; NEW and learning both count', () => {
  assert.equal(word(nextHighlighted(tokens, statusOf, at('El'), 1)), 'perro');
  assert.equal(word(nextHighlighted(tokens, statusOf, at('perro'), 1)), 'come');
  assert.equal(word(nextHighlighted(tokens, statusOf, at('come'), 1)), 'pan');
});

test('previous skips the same tokens', () => {
  assert.equal(word(nextHighlighted(tokens, statusOf, at('pan'), -1)), 'come');
  assert.equal(word(nextHighlighted(tokens, statusOf, at('come'), -1)), 'perro');
});

test('null at the page edges', () => {
  assert.equal(nextHighlighted(tokens, statusOf, at('pan'), 1), null);
  assert.equal(nextHighlighted(tokens, statusOf, at('perro'), -1), null);
  assert.equal(nextHighlighted(tokens, statusOf, tokens.length - 1, 1), null);
  assert.equal(nextHighlighted(tokens, statusOf, 0, -1), null);
});

test('starting from a word that is not highlighted', () => {
  assert.equal(word(nextHighlighted(tokens, statusOf, at('ya'), 1)), 'come');
  assert.equal(word(nextHighlighted(tokens, statusOf, at('Juan'), -1)), 'perro');
  assert.equal(word(nextHighlighted(tokens, statusOf, at(','), 1)), 'come');
});
