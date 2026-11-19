// Port of PhraseDecksTest.kt and PhrasePoolTest.kt (decks, prompts and orders come from the Kotlin fixture).
import assert from 'node:assert/strict';
import { test } from 'node:test';
import phrases from '../../fixtures/phrases.json' with { type: 'json' };
import {
  BATCH, DECKS, POOL_SIZE, PHRASE_REVIEW_SYSTEM, type PhraseProgress, TO_RETIRE, type Target, applyPhraseReview, builtInOrder, filled, fnv,
  hint, parsePhrases, phraseReviewPrompt, phraseUserPrompt, pool, recordPhrase, retired, scoreKey, session, shortfall, targets, writePhrases,
} from '../src/core/phrases.ts';

const P = phrases.pool;

test('decks come from the fixture; every card has one blank', () => {
  assert.ok(DECKS.questions.cards.length >= 60 && DECKS.verbs.cards.length >= 60);
  for (const deck of Object.values(DECKS)) for (const c of deck.cards) assert.equal(c.spanish.split('___').length, 2, c.id);
  const tener = DECKS.verbs.cards.find((c) => c.verb === 'tener')!;
  assert.equal(hint(tener), 'tener · present');
  assert.equal(filled(tener), 'Tengo dos hijos.');
  assert.equal(hint(DECKS.questions.cards[0]), null);
});

test('the hand-written order and the prompts match the Kotlin ones', () => {
  assert.equal(fnv(''), 2166136261);
  assert.equal(fnv('a'), 3826002220);
  assert.deepEqual(builtInOrder('questions').map((c) => c.id), P.order_questions);
  assert.deepEqual(builtInOrder('verbs').map((c) => c.id), P.order_verbs);
  const ts: Target[] = [{ verb: 'tener', tense: 'present' }, { verb: 'ir', tense: 'near-future' }, { verb: 'salir', tense: 'odd-tense' }];
  assert.equal(phraseUserPrompt('verbs', ts, ["I'm hungry.", 'Say "hi".'], true), P.sampleUserVerbs);
  assert.equal(phraseUserPrompt('questions', [{ questionWords: 'De dónde' }], []), P.sampleUserQuestions);
  assert.equal(phraseReviewPrompt('verbs', ts, [
    { n: 1, english: 'I have two cats.', spanish: '___ dos gatos.', answer: 'Tengo', note: 'a tip' },
    { n: 3, english: 'We leave.', spanish: '___.', answer: 'Salimos', note: null },
  ]), P.sampleReviewVerbs);
});

test('right counts up, wrong down but not below zero, and three retires', () => {
  let p = recordPhrase(undefined, 'say', 'q#1', true, 5);
  assert.deepEqual(p, { mode: 'say', cardId: 'q#1', score: 1, right: 1, wrong: 0, lastMillis: 5 });
  p = recordPhrase(p, 'say', 'q#1', false, 6);
  p = recordPhrase(p, 'say', 'q#1', false, 7);
  assert.equal(p.score, 0);
  for (let i = 0; i < TO_RETIRE - 1; i++) p = recordPhrase(p, 'say', 'q#1', true, 8);
  assert.ok(!retired(p));
  p = recordPhrase(p, 'say', 'q#1', true, 9);
  assert.ok(retired(p));
});

test('a retired sentence is replaced by the next AI-written one, then by the hand-written reserve', () => {
  const order = builtInOrder('questions');
  assert.deepEqual(pool('questions', 'say', [], new Map()), order.slice(0, POOL_SIZE));
  const done = (id: string): [string, PhraseProgress] => [scoreKey('say', id), { mode: 'say', cardId: id, score: TO_RETIRE, right: 3, wrong: 0, lastMillis: 0 }];
  const progress = new Map([done(order[0].id), done(order[1].id)]);
  const generated = [{ id: 'questions~1', english: 'New', spanish: '¿___?', answer: 'Qué' }];
  assert.deepEqual(pool('questions', 'say', generated, progress), [...order.slice(2, POOL_SIZE), generated[0], order[POOL_SIZE]]);
  assert.ok(pool('questions', 'listen', generated, progress).includes(order[0]));
  assert.equal(shortfall('questions', generated, progress), 1);
  assert.equal(shortfall('questions', [], new Map()), 0);
  const s = session(order.slice(0, POOL_SIZE), 'say', new Map([[scoreKey('say', order[0].id), { mode: 'say', cardId: order[0].id, score: 2, right: 2, wrong: 0, lastMillis: 0 }]]));
  assert.equal(s.length, POOL_SIZE);
  assert.equal(s[s.length - 1], order[0]);
});

test('verb targets are distinct verbs and tenses', () => {
  const t = targets('verbs');
  assert.equal(t.length, BATCH);
  assert.equal(new Set(t.map((x) => JSON.stringify(x))).size, BATCH);
  assert.ok(targets('questions').every((x) => 'questionWords' in x && P.questionWords.includes(x.questionWords)));
});

test('parsing keeps well-formed cards whose answer is the target', () => {
  const ts: Target[] = ['Cuánto', 'Dónde', 'Qué', 'Cómo', 'Quién'].map((w) => ({ questionWords: w }));
  const text = `{"cards":[
    {"n":1,"english":"How much is the bread?","spanish":"¿__ cuesta el pan?","answer":"cuánto","note":" "},
    {"n":2,"english":"Where is the bank?","spanish":"¿___ está el banco?","answer":"Adónde"},
    {"n":3,"english":"What's this?","spanish":"¿Qué es esto?","answer":"Qué"},
    {"n":4,"english":"What is this?","spanish":"¿___ es esto?","answer":"Qué"},
    {"n":5,"english":"Who is it?","spanish":"¿___ es?","answer":"Quién.","note":"quién = who"}]}`;
  assert.deepEqual(parsePhrases(text, ts, ['WHO is it']), [{ n: 1, english: 'How much is the bread?', spanish: '¿___ cuesta el pan?', answer: 'cuánto', note: null }]);
  const verb = parsePhrases('{"cards":[{"n":1,"english":"I\'ve already eaten.","spanish":"Ya he ___.","answer":"comido"}]}', [{ verb: 'comer', tense: 'present-perfect' }], []);
  assert.equal(verb[0].spanish, 'Ya ___.');
  assert.equal(verb[0].answer, 'he comido');
});

test('the review fixes, rejects or keeps cards', () => {
  const ts: Target[] = [{ questionWords: 'Qué' }, { questionWords: 'Cuál' }, { questionWords: 'Dónde' }];
  const drafts = [
    { n: 1, english: "What's up?", spanish: '¿___ pasa?', answer: 'Qué', note: 'a tip' },
    { n: 2, english: 'Which one?', spanish: '¿___?', answer: 'Cuál', note: null },
    { n: 3, english: 'Where?', spanish: '¿___?', answer: 'Dónde', note: null },
  ];
  const out = applyPhraseReview('{"cards":[{"n":1,"ok":true,"english":"What\'s going on?"},{"n":2,"ok":false},{"n":3,"ok":true,"note":null}]}', ts, drafts, []);
  assert.deepEqual(out, [{ ...drafts[0], english: "What's going on?" }, drafts[2]]);
});

test('the writer numbers new cards after the shared ones and uses the reviewer', async () => {
  const writes: string[] = [];
  const cards = await writePhrases(
    async (_s, user) => {
      writes.push(user);
      const words = [...user.matchAll(/"questionWords":"([^"]+)"/g)].map((m) => m[1]);
      return JSON.stringify({ cards: words.map((w, i) => ({ n: i + 1, english: `Sentence ${i + 1}`, spanish: `¿___ ${i + 1}?`, answer: w })) });
    },
    'questions', ['Old one'], 20, Math.random,
    async (system) => {
      assert.equal(system, PHRASE_REVIEW_SYSTEM);
      return '{"cards":[{"n":1,"ok":false},{"n":2,"ok":true}]}';
    },
  );
  assert.deepEqual(cards.map((c) => c.id), ['questions~21']);
  assert.equal(cards[0].english, 'Sentence 2');
  assert.ok(writes[0].includes('Old one'));
});
