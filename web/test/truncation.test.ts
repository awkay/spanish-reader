// A gloss batch whose answer the server reports as cut off at its length limit is asked for in halves.
import 'fake-indexeddb/auto';
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { glossBatch } from '../src/services.ts';

const sizes: number[] = [];
globalThis.fetch = (async (input: string, init?: { body?: string }) => {
  if (String(input) !== '/api/ai') return new Response('{}', { status: 200 });
  const user = JSON.parse(init!.body!).user as string;
  const items = JSON.parse(user.split('\n')[1]).items as Array<{ id: string; form: string }>;
  sizes.push(items.length);
  // Anything over 2 words is "too long"; a word named "loop" never fits.
  if (items.length > 2 || items.some((i) => i.form === 'loop')) {
    return new Response(JSON.stringify({ error: 'cut off', truncated: true }), { status: 502 });
  }
  const glosses = items.map((i) => ({ id: i.id, form: i.form, lemma: i.form, partOfSpeech: 'noun', meaningInContext: `m-${i.form}` }));
  return new Response(JSON.stringify({ text: JSON.stringify({ glosses }) }), { status: 200 });
}) as typeof fetch;

const reqs = (...forms: string[]) => forms.map((form, i) => ({ id: String(i), form, sentence: `Frase con ${form}.` }));

test('a cut-off batch is split until the halves fit', async () => {
  sizes.length = 0;
  const got = await glossBatch(reqs('a', 'b', 'c', 'd', 'e'));
  assert.deepEqual([...got.values()].map((g) => g.meaningInContext).sort(), ['m-a', 'm-b', 'm-c', 'm-d', 'm-e']);
  assert.deepEqual(sizes, [5, 3, 2, 1, 2]);
});

test('a single word that is cut off is retried once, then left out', async () => {
  sizes.length = 0;
  const got = await glossBatch(reqs('a', 'loop'));
  assert.deepEqual([...got.keys()], ['0']);
  assert.deepEqual(sizes, [2, 1, 1, 1]);
});
