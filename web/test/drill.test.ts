// Port of VerbDrillTest.kt, plus the prompt against fixtures/prompts.json.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import prompts from '../../fixtures/prompts.json' with { type: 'json' };
import {
  COMMON_VERBS, type DrillCard, type DrillProgress, answerWords, applyReview, reviewUserPrompt, checkDrill, currentVerb, drillUserPrompt, filled, isLearned, mastered,
  parseCards, record, round, situations, withHelpers, writeCards,
} from '../src/core/drill.ts';
import { SUBJECTS, TENSES } from '../src/core/practice.ts';

const card: DrillCard = { id: 'ir#1', verb: 'ir', tense: 'preterite', subject: 'NOSOTROS', english: 'We went to the beach yesterday.', spanish: 'Ayer ___ a la playa.', answer: 'fuimos', also: [] };
const t = (id: string) => TENSES.find((x) => x.id === id)!;
const s = (id: string) => SUBJECTS.find((x) => x.id === id)!;
function seeded(seed: number) {
  let v = seed + 1;
  return () => ((v = (v * 16807) % 2147483647) - 1) / 2147483646;
}
const prog = (cardId: string, correct = 0, hidden = false): DrillProgress => ({ cardId, correct, misses: 0, lastMillis: 0, hidden });

test('common verbs and the prompt match Kotlin', () => {
  assert.equal(COMMON_VERBS[0].infinitive, 'ser');
  assert.equal(drillUserPrompt({ infinitive: 'ir', meaning: 'to go' }, [[t('preterite'), s('NOSOTROS')], [t('imperative'), s('TU')]], true), prompts.sampleDrillUser);
});

test('situations are ten distinct tense and subject pairs, mostly the basics', () => {
  for (let seed = 0; seed < 30; seed++) {
    const sits = situations(seeded(seed));
    assert.equal(sits.length, 10);
    assert.equal(new Set(sits.map(([a, b]) => `${a.id}|${b.id}`)).size, 10);
    assert.equal(sits.filter(([a]) => a.id === 'present').length, 3);
    assert.ok(sits.every(([a, b]) => a.subjects.includes(b.id)));
  }
});

test('cards are parsed from the reply, keeping only well-formed ones', () => {
  const sits: Array<[ReturnType<typeof t>, ReturnType<typeof s>]> = [[t('preterite'), s('NOSOTROS')], [t('present'), s('YO')], [t('present-perfect'), s('TU')]];
  const reply = '```json\n{"cards":[{"n":1,"english":"We went to the beach yesterday.","spanish":"Ayer ____ a la playa.","answer":"fuimos.","also":["fuimos"]},' +
    '{"n":2,"english":"I go.","spanish":"Voy.","answer":"voy"},{"n":3,"english":"You have eaten.","spanish":"Tú ___ mucho.","answer":" has  comido ","also":["habías comido"]}]}```';
  const cards = parseCards(reply, 'ir', sits);
  assert.deepEqual(cards.map((c) => c.id), ['ir#1', 'ir#3']);
  assert.deepEqual(cards[0], card);
  assert.equal(cards[1].answer, 'has comido');
  assert.deepEqual(cards[1].also, ['habías comido']);
  assert.equal(filled(cards[0]), 'Ayer fuimos a la playa.');
  assert.throws(() => parseCards('no cards', 'ir', sits));
});

test('helper verbs in front of the blank move into the answer', () => {
  const perfect = { ...card, tense: 'present-perfect', spanish: 'Esta semana he ___ muy ocupado.', answer: 'sido' };
  assert.deepEqual(withHelpers(perfect), { ...perfect, spanish: 'Esta semana ___ muy ocupado.', answer: 'he sido' });
  const start = { ...card, tense: 'near-future', spanish: 'Voy a  ___ maestro.', answer: 'ser', also: ['ser'] };
  assert.deepEqual(withHelpers(start), { ...start, spanish: '___ maestro.', answer: 'Voy a ser', also: ['voy a ser'] });
  const after = { ...card, tense: 'near-future', spanish: 'Mañana ___ a ir al restaurante.', answer: 'vas' };
  assert.deepEqual(withHelpers(after), { ...after, spanish: 'Mañana ___ al restaurante.', answer: 'vas a ir' });
  const notInf = { ...card, spanish: 'Ayer ___ a la playa.' };
  assert.deepEqual(withHelpers(notInf), notInf);
  assert.deepEqual(withHelpers(card), card);
  const chave = { ...card, spanish: 'La chave ___ aquí.' };
  assert.deepEqual(withHelpers(chave), chave);
});

test("answers must be the exact form, but case, spacing and end punctuation don't matter", () => {
  assert.equal(checkDrill(card, ' Fuimos. '), 'correct');
  assert.equal(checkDrill(card, '¡. fuimos !'), 'correct');
  assert.equal(checkDrill({ ...card, answer: 'comió' }, 'comio'), 'accent');
  assert.equal(checkDrill(card, 'vamos'), 'wrong');
  assert.equal(checkDrill(card, ''), 'wrong');
  assert.equal(checkDrill({ ...card, answer: 'hubiera ido', also: ['hubiese ido'] }, 'hubiese  ido'), 'correct');
});

test('progress counts up, a miss takes one back, six learns a card', () => {
  let p: DrillProgress | undefined;
  for (let i = 0; i < 5; i++) p = record(p, 'ir#1', true, 1);
  assert.equal(isLearned(p), false);
  p = record(p, 'ir#1', false, 2);
  assert.equal(p.correct, 4);
  assert.equal(p.misses, 1);
  p = record(record(p, 'ir#1', true, 3), 'ir#1', true, 3);
  assert.equal(isLearned(p), true);
  assert.equal(record(undefined, 'x', false, 1).correct, 0);
  assert.equal(isLearned(prog('x', 0, true)), true);
});

test('rounds take the least practiced open cards, and mastered verbs move the drill on', () => {
  const cards = [1, 2, 3, 4].map((i) => ({ ...card, id: `ir#${i}` }));
  const progress = new Map([['ir#1', prog('ir#1', 6)], ['ir#2', prog('ir#2', 3)], ['ir#4', prog('ir#4', 0, true)]]);
  assert.deepEqual(round(cards, progress, seeded(1)).map((c) => c.id), ['ir#3', 'ir#2']);
  const done = new Map([...progress, ['ir#2', prog('ir#2', 6)], ['ir#3', prog('ir#3', 7)]]);
  assert.deepEqual(new Set(round(cards, done, seeded(1)).map((c) => c.id)), new Set(['ir#1', 'ir#2', 'ir#3']));
  assert.equal(mastered(cards, done), true);
  assert.equal(mastered([], done), false);
  const sets: Record<string, DrillCard[]> = { ser: [{ ...card, id: 'ser#1', verb: 'ser' }] };
  assert.equal(currentVerb((v) => sets[v], new Map()).infinitive, 'ser');
  assert.equal(currentVerb((v) => sets[v], new Map([['ser#1', prog('ser#1', 6)]])).infinitive, 'estar');
  assert.deepEqual(answerWords({ ...card, answer: 'He comido' }), ['he', 'comido']);
});

const tenCards = JSON.stringify({ cards: Array.from({ length: 10 }, (_, i) => ({ n: i + 1, english: `E${i}`, spanish: `S ___ ${i}`, answer: `a${i}` })) });
function fake(...replies: string[]) {
  const users: string[] = [];
  return { users, complete: async (_s: string, u: string) => (users.push(u), replies.shift()!) };
}

test('the review fixes, drops and keeps cards', () => {
  const cards = [1, 2, 3].map((i) => ({ ...card, id: `ser#${i}`, verb: 'ser', answer: 'fue', spanish: 'Él ___ en la reunión.' }));
  const review = '{"cards":[{"n":1,"ok":true,"english":"He was a teacher.","spanish":"Él ___ maestro.","answer":"fue","also":[]},{"n":2,"ok":false},{"n":3,"ok":true}]}';
  const out = applyReview(review, cards);
  assert.deepEqual(out.map((c) => c.id), ['ser#1', 'ser#3']);
  assert.equal(out[0].spanish, 'Él ___ maestro.');
  assert.deepEqual(out[1], cards[2]);
  assert.throws(() => applyReview('nope', cards));
  const c2: DrillCard = { id: 'ser#2', verb: 'ser', tense: 'preterite', subject: 'EL', english: 'He was "there".', spanish: 'Él ___ allí.', answer: 'fue', also: ['x'] };
  assert.equal(reviewUserPrompt({ infinitive: 'ser', meaning: 'to be' }, [c2], true), prompts.sampleDrillReview);
});

test('the writer retries an unparseable reply once, then has the cards reviewed', async () => {
  const writer = fake('nothing', tenCards);
  const reviewer = fake(JSON.stringify({ cards: Array.from({ length: 10 }, (_, i) => (i === 3 ? { n: 4, ok: false } : { n: i + 1, ok: true })) }));
  const cards = await writeCards(writer.complete, { infinitive: 'ir', meaning: 'to go' }, seeded(2), reviewer.complete);
  assert.equal(cards.length, 9);
  assert.ok(!cards.some((c) => c.id === 'ir#4'));
  assert.ok(writer.users[1].endsWith('Reply with valid JSON only.'));
  assert.equal((await writeCards(fake(tenCards).complete, { infinitive: 'ir', meaning: 'to go' }, seeded(2), fake('x', 'y').complete)).length, 10);
  const rejectAll = JSON.stringify({ cards: Array.from({ length: 10 }, (_, i) => ({ n: i + 1, ok: false })) });
  await assert.rejects(writeCards(fake(tenCards).complete, { infinitive: 'ir', meaning: 'to go' }, seeded(2), fake(rejectAll).complete));
});
