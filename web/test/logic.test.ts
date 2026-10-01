// Ports of the Kotlin unit tests for parsing, clitics and vocabulary rules.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { cliticRole, parseGlosses, parseSentences, splitClitics, verbSummary } from '../src/core/gloss.ts';
import { Status, applyPageFinished, markNewAsKnown, onTap, type VocabEntry } from '../src/core/vocab.ts';

const req = { id: '1', form: 'observándome', sentence: 'Estaba observándome.' };

test('rich gloss parsing, fenced and with prose around it', () => {
  const text = 'Sure!\n```json\n{"glosses":[{"id":"1","form":"observándome","lemma":"observar","partOfSpeech":"verb","meaningInContext":"watching me",' +
    '"verb":{"infinitive":"observar","mood":"gerund","formation":"observ- + -ando"},"clitics":[{"pronoun":"me","role":"direct object","refersTo":"me"}],' +
    '"roots":"Latin observare",}]}\n```\nHope that helps';
  const g = parseGlosses(text, [req]).get('1')!;
  assert.equal(g.verb?.infinitive, 'observar');
  assert.equal(cliticRole(g.clitics![0].role), 'DIRECT_OBJECT');
  assert.equal(g.roots, 'Latin observare');
});

test('a malformed verb object only drops the verb', () => {
  const g = parseGlosses('{"glosses":[{"id":"1","form":"x","lemma":"x","partOfSpeech":"verb","meaningInContext":"m","verb":"gerund","clitics":[{"pronoun":"me","role":"direct object"}]}]}', [req]).get('1')!;
  assert.equal(g.verb, null);
  assert.equal(g.clitics!.length, 1);
});

test('no JSON at all is an error; unmatched ids are skipped', () => {
  assert.throws(() => parseGlosses('no idea', [req]));
  assert.equal(parseGlosses('{"glosses":[{"id":"9","form":"zzz","meaningInContext":"m"}]}', [req]).size, 0);
});

test('sentence analysis parsing drops single-word "phrases"', () => {
  const m = parseSentences('{"sentences":[{"id":"0","translation":"I will miss them.","phrases":[{"phrase":"echar de menos","meaning":"to miss"},{"phrase":"casa","meaning":"house"}]},{"id":"1","phrases":[]}]}', ['0', '1', '2']);
  assert.deepEqual(m.get('0'), { translation: 'I will miss them.', phrases: [{ phrase: 'echar de menos', meaning: 'to miss' }] });
  assert.deepEqual(m.get('1'), { translation: null, phrases: [] });
  assert.equal(m.has('2'), false);
});

test('clitic roles and splitting match Android', () => {
  assert.equal(cliticRole('indirect object (se replaces le)'), 'INDIRECT_OBJECT');
  assert.equal(cliticRole('accidental se'), 'ACCIDENTAL_SE');
  assert.equal(cliticRole('passive se'), 'IMPERSONAL_SE');
  const c = (...p: string[]) => p.map((pronoun) => ({ pronoun, role: 'x' }));
  assert.deepEqual(splitClitics('observándome', c('me')), { base: 'observándo', attached: ['me'] });
  assert.deepEqual(splitClitics('dáselo', c('se', 'lo')), { base: 'dá', attached: ['se', 'lo'] });
  assert.deepEqual(splitClitics('habla', c('la')), { base: 'habla', attached: [] });
  assert.deepEqual(splitClitics('olvidó', c('se', 'me')), { base: 'olvidó', attached: [] });
  assert.equal(verbSummary({ infinitive: 'ir', tense: 'preterite', mood: 'indicative', person: '3rd', number: 'singular' }), 'preterite indicative · 3rd person singular');
});

const e = (form: string, status: number): VocabEntry =>
  ({ form, lemma: null, status, translation: null, contextSentence: null, firstSeenMillis: 1, lastSeenMillis: 1, timesSeen: 1 });

test('page rule: unmarked words go in at level 1 with details, never Known', () => {
  const entries = new Map([['perro', e('perro', Status.RECOGNIZED)], ['ya', e('ya', Status.KNOWN)]]);
  const added = applyPageFinished(['el', 'perro', 'come', 'el', 'ya'], entries, 9, new Map([['come', { contextSentence: 'Él come.', lemma: 'comer', translation: 'eats' }]]));
  assert.deepEqual(added.map((x) => [x.form, x.status, x.translation]), [['el', 1, null], ['come', 1, 'eats']]);
  assert.deepEqual(markNewAsKnown(['el', 'perro'], entries, 9).map((x) => [x.form, x.status]), [['el', 5]]);
  assert.equal(onTap(undefined, 'gato', 5, 'El gato.').status, Status.LEVEL_1);
  assert.equal(onTap(e('gato', Status.FAMILIAR), 'gato', 5, null).status, Status.FAMILIAR);
});
