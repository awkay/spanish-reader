// Port of PhraseDecksTest.kt (the decks themselves come from the Kotlin fixture).
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { DECKS, filled, hint, session } from '../src/core/phrases.ts';

test('decks come from the fixture; every card has one blank', () => {
  assert.ok(DECKS.questions.cards.length >= 60 && DECKS.verbs.cards.length >= 60);
  for (const deck of Object.values(DECKS)) for (const c of deck.cards) assert.equal(c.spanish.split('___').length, 2, c.id);
  const tener = DECKS.verbs.cards.find((c) => c.verb === 'tener')!;
  assert.equal(hint(tener), 'tener · present');
  assert.equal(filled(tener), 'Tengo dos hijos.');
  assert.equal(hint(DECKS.questions.cards[0]), null);
});

test('a session is the whole deck shuffled', () => {
  const s = session('questions');
  assert.equal(s.length, DECKS.questions.cards.length);
  assert.deepEqual(new Set(s.map((c) => c.id)), new Set(DECKS.questions.cards.map((c) => c.id)));
});
