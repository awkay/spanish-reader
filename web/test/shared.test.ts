// Shared library state against a stubbed server: refresh, delete, and a slow refresh racing a delete.
import 'fake-indexeddb/auto';
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createLesson, deleteShared, lessons, refreshShared, shared } from '../src/services.ts';

const summary = (id: string) => ({ id, title: id, createdAt: 1, words: 3 });
let server = [summary('a'), summary('b')];
let hold: Promise<void> | null = null;

globalThis.fetch = (async (input: string, init?: { method?: string }) => {
  const path = String(input);
  const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status });
  if (path === '/api/lessons' && (init?.method ?? 'GET') === 'GET') {
    const snapshot = [...server];
    if (hold) await hold;
    return json(200, snapshot);
  }
  const del = path.match(/^\/api\/lessons\/(.+)$/);
  if (del && init?.method === 'DELETE') {
    const before = server.length;
    server = server.filter((s) => s.id !== del[1]);
    return server.length < before ? json(200, { ok: true }) : json(404, { error: 'no such lesson' });
  }
  return json(500, { error: 'unexpected ' + path });
}) as typeof fetch;

const ids = () => shared.get().list?.map((s) => s.id);

test('refresh, delete racing a slow refresh, and the local copy becomes shareable again', async () => {
  await refreshShared();
  assert.deepEqual(ids(), ['a', 'b']);
  assert.equal(shared.get().loading, false);

  const local = await createLesson('A', 'Hola amigo.', true, 'a');

  let release!: () => void;
  hold = new Promise((r) => (release = r));
  const slow = refreshShared(); // sees [a, b], answers after the delete
  hold = null;
  await deleteShared('a');
  assert.deepEqual(ids(), ['b']);
  release();
  await slow;
  await new Promise((r) => setTimeout(r, 10)); // the follow-up refresh deleteShared starts
  assert.deepEqual(ids(), ['b'], 'a stale answer must not bring the deleted lesson back');
  assert.equal(lessons.get().find((l) => l.id === local.id)?.sharedId, null);

  server.push(summary('c'));
  await refreshShared();
  assert.deepEqual(ids(), ['b', 'c']);

  await deleteShared('zzz'); // already gone elsewhere: not an error
  assert.deepEqual(ids(), ['b', 'c']);
});
