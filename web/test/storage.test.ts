// Services against an in-memory IndexedDB: lessons, vocabulary, page rule, backup (Android-compatible format).
import 'fake-indexeddb/auto';
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { paginate, tokenize } from '../src/core/text.ts';
import { Status } from '../src/core/vocab.ts';
import * as db from '../src/db.ts';
import {
  createLesson, exportBackup, finishPage, importBackup, lessons, loadLessons, loadVocab, markPageKnown, setWordStatus, tapWord, vocab,
} from '../src/services.ts';

test('lessons, taps, page rule and backup round trip', async () => {
  const l = await createLesson('', 'El perro come pan.\nLa casa es grande.', true);
  assert.equal(l.title, 'El perro come pan.');
  assert.equal(lessons.get().length, 1);

  await tapWord('perro', 'El perro come pan.');
  assert.equal(vocab.get().get('perro')?.status, Status.LEVEL_1);

  const text = tokenize(l.text);
  const [page] = paginate(text, 250);
  // A cached gloss is used for the auto-added word's meaning.
  const hash = (await import('../src/core/hash.ts')).sentenceHash;
  await db.put('glosses', { key: db.glossKey(await hash('El perro come pan.'), 'come'), formKey: 'come', hash: '', storedAt: 1,
    gloss: { form: 'come', lemma: 'comer', partOfSpeech: 'verb', meaningInContext: 'eats' } });
  await setWordStatus('es', Status.KNOWN);
  const added = await finishPage(text, page);
  assert.equal(added, 6); // el, come, pan, la, casa, grande
  assert.equal(vocab.get().get('come')?.translation, 'eats');
  assert.equal(vocab.get().get('come')?.status, Status.LEVEL_1);
  assert.equal(vocab.get().get('es')?.status, Status.KNOWN);
  assert.equal(await markPageKnown(page), 0); // nothing blue left

  const backup = JSON.parse(await exportBackup());
  assert.equal(backup.version, 1);
  assert.equal(backup.lessons[0].createdAtMillis, l.createdAt);
  assert.ok(backup.vocab.some((v: { form: string }) => v.form === 'perro'));

  // Restoring into the same data changes nothing; into an empty device restores everything.
  assert.equal(await importBackup(JSON.stringify(backup)), 'Added 0 lessons; updated 0 words.');
  await db.clear('lessons');
  await db.clear('vocab');
  await loadLessons();
  await loadVocab();
  assert.match(await importBackup(JSON.stringify(backup)), /Added 1 lessons; updated 8 words/);
  assert.equal(vocab.get().get('come')?.translation, 'eats');
  await assert.rejects(importBackup('nope'), /Not a Spanish Reader backup/);
});
