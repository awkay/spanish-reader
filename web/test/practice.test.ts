// Port of VerbPracticeTest.kt, plus prompt checks against fixtures/prompts.json.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import prompts from '../../fixtures/prompts.json' with { type: 'json' };
import type { Gloss } from '../src/core/gloss.ts';
import {
  type PracticeSpec, SUBJECTS, TENSES, checkAnswer, checkUserPrompt, exerciseUserPrompt, generateExercise, knownWords, parseCheck,
  parseExercise, pickExercise, practiceVerbs, sameAnswer,
} from '../src/core/practice.ts';
import type { VocabEntry } from '../src/core/vocab.ts';

const entry = (form: string, status: number, lemma: string | null = null): VocabEntry =>
  ({ form, lemma, status, translation: null, contextSentence: null, firstSeenMillis: 0, lastSeenMillis: 0, timesSeen: 1 });
const verbGloss = (form: string, inf: string, meaning: string | null = null): Gloss =>
  ({ form, lemma: inf, partOfSpeech: 'verb', meaningInContext: 'x', verb: { infinitive: inf }, lemmaMeaning: meaning });

const entries = [
  entry('prepárate', 1, 'preparar'), entry('preparamos', 5, 'preparar'), entry('vamos', 3, 'ir'), entry('hablan', 0, 'hablar'),
  entry('comer', -1, 'comer'), entry('casa', 5, 'casa'), entry('aeropuerto', 1), entry('123', 5),
];
const glosses: Record<string, Gloss[]> = {
  'prepárate': [verbGloss('prepárate', 'prepararse')],
  preparamos: [verbGloss('preparamos', 'preparar', 'to prepare')],
  vamos: [verbGloss('vamos', 'ir', 'to go')],
  hablan: [verbGloss('hablan', 'hablar')],
  comer: [verbGloss('comer', 'comer')],
  casa: [{ form: 'casa', lemma: 'casa', partOfSpeech: 'noun', meaningInContext: 'house' }],
};
const glossesOf = (f: string) => glosses[f] ?? [];

function seeded(seed: number) {
  let s = seed + 1;
  return () => ((s = (s * 16807) % 2147483647) - 1) / 2147483646;
}

test('verbs come from exposed vocabulary with a verb gloss, grouped by plain infinitive', () => {
  const verbs = practiceVerbs(entries, glossesOf);
  assert.deepEqual(verbs.map((v) => v.infinitive), ['ir', 'preparar']);
  assert.deepEqual(verbs[1], { infinitive: 'preparar', meaning: 'to prepare', forms: ['prepárate', 'preparamos'] });
});

test('known words are exposed lemmas, deduplicated, without the excluded verb', () => {
  assert.deepEqual(new Set(knownWords(entries, new Set(['ir', 'vamos']))), new Set(['preparar', 'casa', 'aeropuerto']));
  assert.equal(knownWords(entries, new Set(), 2).length, 2);
});

test("pick avoids recent verbs and respects the tense's subjects", () => {
  const verbs = practiceVerbs(entries, glossesOf);
  for (let seed = 0; seed < 20; seed++) {
    const spec = pickExercise(verbs, entries, seeded(seed), ['ir'])!;
    assert.equal(spec.verb.infinitive, 'preparar');
    assert.ok(spec.tense.subjects.includes(spec.subject.id));
    assert.ok(!spec.knownWords.includes('preparar') && !spec.knownWords.includes('prepárate'));
  }
  const cmd = pickExercise(verbs, entries, seeded(3), [], [TENSES.find((t) => t.id === 'imperative')!])!;
  assert.notEqual(cmd.subject.id, 'YO');
  assert.equal(pickExercise([], entries), null);
});

test('prompts match the Kotlin fixtures', () => {
  const spec: PracticeSpec = {
    verb: { infinitive: 'ir', meaning: 'to go', forms: ['vamos'] },
    tense: TENSES.find((t) => t.id === 'past-near-future')!,
    subject: SUBJECTS.find((s) => s.id === 'NOSOTROS')!,
    knownWords: ['casa', 'aeropuerto', '"día"'],
  };
  const ex = { english: 'We were going to go to the airport.', spanish: 'Íbamos a ir al aeropuerto.', verbForm: null, verbMeaning: null };
  assert.equal(exerciseUserPrompt(spec), prompts.samplePracticeUser);
  assert.equal(exerciseUserPrompt({ ...spec, verb: { ...spec.verb, meaning: null } }, true), prompts.samplePracticeUserNoMeaningRetry);
  assert.equal(checkUserPrompt(spec, ex, ['Vamos al aeropuerto', 'Ibamos al aeropuerto'], 'Íbamos a ir al aeropuerto'), prompts.samplePracticeCheck);
  assert.equal(checkUserPrompt(spec, { ...ex, verbForm: 'íbamos a ir' }, [], 'x'), prompts.samplePracticeCheckFirst);
  assert.equal(checkUserPrompt(spec, { ...ex, verbForm: 'íbamos a ir' }, ['y'], 'x'), prompts.samplePracticeCheckSecond);
  assert.equal(checkUserPrompt(spec, ex, ['y'], 'x'), prompts.samplePracticeCheckSecondNoForm);
});

test('replies are parsed leniently', () => {
  const ex = parseExercise('Sure!\n```json\n{"english":"We were going to go.","spanish":"Íbamos a ir.","verb_form":"íbamos a ir"}\n```');
  assert.equal(ex.spanish, 'Íbamos a ir.');
  assert.equal(ex.verbForm, 'íbamos a ir');
  assert.equal(ex.verbMeaning, null);
  const wrong = parseCheck('{"correct":"false","feedback":"Check the tense.","hint":"Use the imperfect of ir."}');
  assert.equal(wrong.correct, false);
  assert.equal(wrong.hint, 'Use the imperfect of ir.');
  assert.equal(parseCheck('{"correct":true,"feedback":"Good","hint":"x"}').hint, null);
  assert.throws(() => parseExercise('{"english":"no spanish"}'));
});

test('same answer ignores case, punctuation and spacing but not accents', () => {
  assert.ok(sameAnswer('íbamos a ir al aeropuerto', '¡Íbamos a ir al  aeropuerto!'));
  assert.ok(!sameAnswer('ibamos a ir al aeropuerto', 'Íbamos a ir al aeropuerto.'));
  assert.ok(!sameAnswer('  ', ''));
});

const spec: PracticeSpec = {
  verb: { infinitive: 'ir', meaning: 'to go', forms: ['vamos'] }, tense: TENSES.find((t) => t.id === 'preterite')!,
  subject: SUBJECTS[0], knownWords: ['casa'],
};
const ex = { english: 'I went home yesterday.', spanish: 'Ayer fui a casa.', verbForm: null, verbMeaning: null };

function fake(...replies: string[]) {
  const users: string[] = [];
  return { users, complete: async (_s: string, u: string) => (users.push(u), replies.shift()!) };
}

test('ai retries an unparseable reply once, and skips the call for the model answer', async () => {
  const f = fake('no json here', '{"english":"I went home yesterday.","spanish":"Ayer fui a casa."}');
  assert.deepEqual(await generateExercise(f.complete, spec), ex);
  assert.ok(f.users[1].endsWith('Reply with valid JSON only.'));
  const none = fake();
  assert.ok((await checkAnswer(none.complete, spec, ex, [], 'ayer fui a casa')).correct);
  assert.equal(none.users.length, 0);
});

test('the last try gets no hint', async () => {
  const reply = '{"correct":false,"feedback":"Wrong tense.","hint":"fui"}';
  assert.equal((await checkAnswer(fake(reply).complete, spec, ex, ['a'], 'Voy a casa')).hint, 'fui');
  assert.equal((await checkAnswer(fake(reply).complete, spec, ex, ['a', 'b'], 'Voy a casa')).hint, null);
  const echo = '{"correct":false,"feedback":"No.","hint":"Say where the problem is and which rule applies."}';
  assert.equal((await checkAnswer(fake(echo).complete, spec, ex, [], 'Voy a casa')).hint, null);
});
