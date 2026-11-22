// The KNOWN-word sheet's local data against an in-memory IndexedDB and a counting fetch stub: gathering what's on
// hand never touches the network; the explicit lookup goes to the server cache, then the AI, and caches the result.
import 'fake-indexeddb/auto';
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { sentenceHash } from '../src/core/hash.ts';
import { Status } from '../src/core/vocab.ts';
import * as db from '../src/db.ts';
import { cachedGloss, loadVocab, lookup, onHandMeanings, storedTranslation } from '../src/services.ts';

const calls: string[] = [];
globalThis.fetch = (async (input: string, init?: { body?: string }) => {
  const path = String(input);
  calls.push(path);
  const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200 });
  if (path === '/api/cache/get') return json({});
  if (path === '/api/cache/put') return json({ ok: true });
  if (path === '/api/ai') {
    const user = JSON.parse(init!.body!).user as string;
    const items = JSON.parse(user.split('\n')[1]).items as Array<{ id: string; form: string }>;
    const glosses = items.map((i) => ({ id: i.id, form: i.form, lemma: 'ir', partOfSpeech: 'verb', meaningInContext: 'went (here)' }));
    return json({ text: JSON.stringify({ glosses }) });
  }
  return new Response('{}', { status: 500 });
}) as typeof fetch;

async function putGloss(form: string, sentence: string, meaningInContext: string, storedAt: number, extra = {}) {
  const hash = await sentenceHash(sentence);
  await db.put('glosses', { key: db.glossKey(hash, form), formKey: form, hash, storedAt, gloss: { form, lemma: 'ir', partOfSpeech: 'verb', meaningInContext, ...extra } });
}

const entry = (form: string, status: number, lemma: string | null, translation: string | null, lastSeenMillis: number) =>
  ({ form, lemma, status, translation, contextSentence: null, firstSeenMillis: 1, lastSeenMillis, timesSeen: 1 });

test('on-hand meanings come from this form, related forms of its lemma and the lemma itself, without the network', async () => {
  await db.putAll('vocab', [
    entry('fue', Status.KNOWN, 'ir', 'went', 5),
    entry('fui', Status.LEVEL_1, 'ir', 'I went', 4),
    entry('vamos', Status.KNOWN, 'irse', null, 3),
    entry('casa', Status.KNOWN, 'casa', 'house', 2),
  ]);
  await loadVocab();
  await putGloss('fue', 'Ayer fue al mercado.', 'went', 1, { otherMeanings: ['left'] });
  await putGloss('fue', 'Él fue a casa.', 'Went.', 2);
  await putGloss('fui', 'Fui al cine.', 'I went', 3, { lemmaMeaning: 'to go' });
  await putGloss('ir', 'Quiero ir.', 'to go', 4);
  await putGloss('vamos', 'Nos vamos.', 'we are leaving', 5);
  calls.length = 0;

  const sentence = 'Luego fue a la playa.';
  assert.equal(await cachedGloss('Fue', sentence), null);
  assert.equal(await storedTranslation(sentence), null);
  const m = await onHandMeanings('Fue');
  assert.deepEqual(calls, []);
  assert.equal(m.lemma, 'ir');
  assert.equal(m.lemmaMeaning, 'to go');
  assert.deepEqual(m.meanings[0], { text: 'Went.', count: 2, forms: [] });
  assert.deepEqual(m.meanings.slice(1).map((x) => [x.text, x.forms]), [['I went', ['fui']], ['we are leaving', ['vamos']], ['to go', ['ir']]]);
  assert.deepEqual(m.otherMeanings, ['left']);
  assert.deepEqual(m.relatedForms, ['fui', 'vamos', 'ir']);

  // Nothing stored at all: an empty result, still no network.
  const none = await onHandMeanings('nada');
  assert.equal(none.meanings.length + none.otherMeanings.length, 0);
  assert.deepEqual(calls, []);

  // The explicit lookup: shared server cache first, then the AI; the result is cached for next time.
  const r = await lookup('fue', sentence);
  assert.ok('gloss' in r && r.gloss.meaningInContext === 'went (here)');
  assert.deepEqual(calls.slice(0, 2), ['/api/cache/get', '/api/ai']);
  assert.equal((await cachedGloss('fue', sentence))?.meaningInContext, 'went (here)');
});
