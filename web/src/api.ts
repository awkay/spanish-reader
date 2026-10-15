// Calls to the Go server (same origin; the session is an HttpOnly cookie).
import type { FoundPhrase, Gloss } from './core/gloss.ts';
import type { DrillCard } from './core/drill.ts';
import type { BackupFile, SyncMeta } from './core/sync.ts';

export class ApiError extends Error {
  readonly status: number;
  /** The error response's JSON body. */
  readonly data: Record<string, unknown>;
  constructor(status: number, message: string, data: Record<string, unknown> = {}) {
    super(message);
    this.status = status;
    this.data = data;
  }
}

export class NotSignedIn extends ApiError {}

let onUnauthorized: () => void = () => {};
export const setUnauthorizedHandler = (f: () => void) => (onUnauthorized = f);

async function call<T>(method: string, path: string, body?: unknown): Promise<T> {
  let resp: Response;
  try {
    resp = await fetch(path, {
      method,
      credentials: 'same-origin',
      headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new ApiError(0, "Can't reach the server (offline?)");
  }
  if (resp.status === 401 && path !== '/api/login') {
    onUnauthorized();
    throw new NotSignedIn(401, 'Not signed in');
  }
  const data = await resp.json().catch(() => ({}));
  if (!resp.ok) throw new ApiError(resp.status, (data as { error?: string }).error ?? `HTTP ${resp.status}`, data as Record<string, unknown>);
  return data as T;
}

export interface SessionInfo { ok: boolean; tts: boolean; voice: string; youtube?: boolean; user?: string }
export interface SharedLessonSummary {
  id: string; title: string; createdAt: number; words: number; sharedBy?: string; source?: string; videoId?: string;
}
export interface SharedLesson {
  id: string; title: string; text: string; createdAt: number; sharedBy?: string; source?: string; sourceUrl?: string; videoId?: string;
}
export interface YouTubeJob {
  id: string; videoId: string; status: 'queued' | 'downloading' | 'transcribing' | 'done' | 'error';
  detail?: string; title?: string; lessonId?: string; error?: string;
}
type PageAudioResponse = { audio: string; timings: Array<[number, number]>; voice: string };
export interface SentenceData {
  hash: string;
  translation?: string;
  phrases?: FoundPhrase[];
  scanned?: boolean;
  glosses?: Record<string, Gloss>;
}

export const api = {
  login: (code: string, username: string) => call<{ token: string; user: string }>('POST', '/api/login', { code, username }),
  session: () => call<SessionInfo>('GET', '/api/session'),
  ai: (system: string, user: string, items: number, improve = false) =>
    call<{ text: string }>('POST', '/api/ai', { system, user, items, improve }).then((r) => r.text),
  tts: (sentences: string[]) => call<PageAudioResponse>('POST', '/api/tts', { sentences }),
  videoAudio: (videoId: string, sentences: string[]) =>
    call<PageAudioResponse>('POST', `/api/youtube/${encodeURIComponent(videoId)}/audio`, { sentences }),
  youtubeImport: (url: string) => call<YouTubeJob>('POST', '/api/youtube', { url }),
  youtubeJob: (id: string) => call<YouTubeJob>('GET', `/api/youtube/jobs/${encodeURIComponent(id)}`),
  lessons: () => call<SharedLessonSummary[]>('GET', '/api/lessons'),
  lesson: (id: string) => call<SharedLesson>('GET', `/api/lessons/${encodeURIComponent(id)}`),
  shareLesson: (lesson: { title: string; text: string; sharedBy?: string; videoId?: string; sourceUrl?: string; source?: string; sentences: SentenceData[] }) =>
    call<SharedLessonSummary>('POST', '/api/lessons', lesson),
  deleteShared: (id: string) => call<{ ok: boolean }>('DELETE', `/api/lessons/${encodeURIComponent(id)}`),
  cacheGet: (hashes: string[]) => call<Record<string, SentenceData>>('POST', '/api/cache/get', { hashes }),
  drillGet: (verb: string) => call<{ verb: string; cards: DrillCard[] }>('GET', `/api/drill/${encodeURIComponent(verb)}`),
  drillPut: (verb: string, cards: DrillCard[]) => call<{ verb: string; cards: DrillCard[] }>('PUT', `/api/drill/${encodeURIComponent(verb)}`, { cards }),
  syncMeta: () => call<SyncMeta>('GET', '/api/sync/meta'),
  syncGet: () => call<SyncMeta & { snapshot: BackupFile | null }>('GET', '/api/sync'),
  syncPut: (body: { baseRevision: number; force: boolean; device: string; snapshot: BackupFile }) => call<SyncMeta>('PUT', '/api/sync', body),
  cachePut: (sentences: SentenceData[]) => call<{ ok: boolean }>('POST', '/api/cache/put', { sentences }),
  audio: async (path: string): Promise<Blob> => {
    const resp = await fetch(path, { credentials: 'same-origin' });
    if (!resp.ok) throw new ApiError(resp.status, `Audio download failed (HTTP ${resp.status})`);
    return resp.blob();
  },
};
