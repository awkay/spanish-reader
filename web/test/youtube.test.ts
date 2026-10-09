// YouTube lessons against a stubbed server: link detection, the import job, and page audio from the recording.
import 'fake-indexeddb/auto';
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { isYouTubeUrl } from '../src/core/text.ts';
import { pageAudio } from '../src/player.ts';
import { importYouTube, lessons, shareLesson } from '../src/services.ts';

const calls: string[] = [];
let polls = 0;
let shared: unknown = null;

globalThis.fetch = (async (input: string, init?: { method?: string; body?: string }) => {
  const path = String(input);
  const method = init?.method ?? 'GET';
  calls.push(`${method} ${path}`);
  const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status });
  const job = { id: 'j1', videoId: 'dQw4w9WgXcQ' };
  if (path === '/api/youtube' && method === 'POST') return json(200, { ...job, status: 'queued' });
  if (path === '/api/youtube/jobs/j1') {
    polls++;
    return json(200, polls < 2 ? { ...job, status: 'transcribing', title: 'Charla', detail: 'Transcribing part 1 of 2' }
      : { ...job, status: 'done', title: 'Charla', lessonId: 'L1' });
  }
  if (path === '/api/lessons/L1') {
    return json(200, { id: 'L1', title: 'Charla', text: 'Hola, amigos.', createdAt: 1, source: 'youtube',
      videoId: 'dQw4w9WgXcQ', sourceUrl: 'https://www.youtube.com/watch?v=dQw4w9WgXcQ' });
  }
  if (path === '/api/lessons' && method === 'GET') return json(200, []);
  if (path === '/api/lessons' && method === 'POST') {
    shared = JSON.parse(init!.body!);
    return json(200, { id: 'L2', title: 'Charla', createdAt: 2, words: 2 });
  }
  if (path === '/api/youtube/dQw4w9WgXcQ/audio') return json(200, { audio: '/api/audio/a.mp3', timings: [[150, 1650]], voice: 'original' });
  if (path === '/api/tts') return json(200, { audio: '/api/audio/b.mp3', timings: [[0, 900]], voice: 'piper' });
  if (path.startsWith('/api/audio/')) return new Response(new Blob(['mp3']), { status: 200 });
  return json(500, { error: 'unexpected ' + path });
}) as typeof fetch;

test('YouTube links are recognized; other links and text are not', () => {
  for (const ok of ['https://www.youtube.com/watch?v=dQw4w9WgXcQ', 'https://youtu.be/dQw4w9WgXcQ?si=x', 'youtube.com/shorts/dQw4w9WgXcQ',
    'https://m.youtube.com/watch?feature=share&v=dQw4w9WgXcQ', '  https://www.youtube.com/live/dQw4w9WgXcQ  ']) {
    assert.ok(isYouTubeUrl(ok), ok);
  }
  for (const no of ['https://example.com/watch?v=dQw4w9WgXcQ', 'https://www.youtube.com/playlist?list=PL1', 'Hola amigos',
    'mira https://youtu.be/dQw4w9WgXcQ', 'https://youtu.be/short']) {
    assert.ok(!isYouTubeUrl(no), no);
  }
});

test('import polls the job, adds the lesson with its video, and page audio comes from the recording', async () => {
  const progress: string[] = [];
  const l = await importYouTube('https://youtu.be/dQw4w9WgXcQ', (p) => progress.push(p), 1);
  assert.equal(l.videoId, 'dQw4w9WgXcQ');
  assert.equal(l.sourceUrl, 'https://www.youtube.com/watch?v=dQw4w9WgXcQ');
  assert.equal(l.sharedId, 'L1');
  assert.ok(lessons.get().some((x) => x.id === l.id));
  assert.ok(progress.includes('Charla — Transcribing part 1 of 2'), progress.join(' | '));

  const video = await pageAudio(['Hola, amigos.'], l.videoId);
  assert.deepEqual(video.timings, [[150, 1650]]);
  const tts = await pageAudio(['Hola, amigos.']);
  assert.deepEqual(tts.timings, [[0, 900]], 'the same sentences as a text lesson must not reuse the video audio');
  assert.ok(calls.includes('POST /api/youtube/dQw4w9WgXcQ/audio'));

  // Sharing the device copy again keeps the video.
  await shareLesson(l);
  assert.equal((shared as { videoId?: string }).videoId, 'dQw4w9WgXcQ');
});
