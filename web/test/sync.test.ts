// Port of SyncTest.kt.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { type BackupLesson, fingerprint, hasLocalChanges, replacePlan, snapshot } from '../src/core/sync.ts';
import type { VocabEntry } from '../src/core/vocab.ts';

const a: BackupLesson = { title: 'A', text: 'Hola.', createdAtMillis: 1, currentPage: 2 };
const b: BackupLesson = { title: 'B', text: 'Adiós.', createdAtMillis: 2, currentPage: 0, videoId: 'abcdefghijk', sourceUrl: 'https://youtu.be/abcdefghijk' };
const v = (form: string, status: number, lemma: string | null, last: number): VocabEntry =>
  ({ form, lemma, status, translation: null, contextSentence: null, firstSeenMillis: 1, lastSeenMillis: last, timesSeen: 1 });
const v1 = v('hola', 5, 'hola', 2);
const v2 = v('adiós', 1, null, 3);

test('snapshot order and fingerprint ignore row order and export time', async () => {
  const s1 = snapshot([b, a], [v1, v2], 10);
  assert.deepEqual(s1.lessons.map((l) => l.title), ['A', 'B']);
  assert.deepEqual(s1.vocab.map((x) => x.form), ['adiós', 'hola']);
  assert.equal(await fingerprint(s1), await fingerprint(snapshot([a, b], [v2, v1], 99)));
  assert.notEqual(await fingerprint(s1), await fingerprint(snapshot([{ ...a, currentPage: 3 }, b], [v1, v2], 10)));
  assert.notEqual(await fingerprint(s1), await fingerprint(snapshot([a, b], [v1, { ...v2, status: 2 }], 10)));
});

test('replace plan keeps matching lessons, inserts new ones and deletes the rest', () => {
  const local: Array<[number, BackupLesson]> = [[1, { ...a, currentPage: 0 }], [2, { title: 'C', text: 'Gone.', createdAtMillis: 3, currentPage: 0 }], [3, a]];
  const plan = replacePlan(local, [{ ...a, title: ' A ', currentPage: 5 }, b, b]);
  assert.deepEqual(plan.update, [[1, { ...a, title: ' A ', currentPage: 5 }]]);
  assert.deepEqual(plan.insert, [b]);
  assert.deepEqual(plan.delete, [3, 2]);
});

test('local changes are those since the last sync', async () => {
  const s = snapshot([a], [v1], 1);
  const state = { revision: 4, fingerprint: await fingerprint(s), atMillis: 1, direction: 'down' as const };
  assert.equal(await hasLocalChanges(state, s), false);
  assert.equal(await hasLocalChanges(state, { ...s, vocab: [{ ...v1, status: 4 }] }), true);
  assert.equal(await hasLocalChanges(null, s), true);
  assert.equal(await hasLocalChanges(null, snapshot([], [], 1)), false);
});
