// Port of :core backup/Sync.kt: manual sync snapshots, the device fingerprint, and the lesson replace plan.
import { ktTrim } from './text.ts';
import type { VocabEntry } from './vocab.ts';

export interface BackupLesson {
  title: string; text: string; createdAtMillis: number; currentPage: number;
  videoId?: string | null; sourceUrl?: string | null; sharedId?: string | null;
}
export interface BackupFile { version: number; exportedAtMillis: number; lessons: BackupLesson[]; vocab: VocabEntry[] }
export interface SyncState { revision: number; fingerprint: string; atMillis: number; direction: 'up' | 'down' }
export interface SyncMeta { revision: number; updatedAt: number; device: string }
export interface LessonPlan<K> { update: Array<[K, BackupLesson]>; insert: BackupLesson[]; delete: K[] }

export const lessonKey = (title: string, text: string) => `${ktTrim(title)}\u0000${ktTrim(text)}`;

const cmp = (a: string | number, b: string | number) => (a < b ? -1 : a > b ? 1 : 0);

/** The state to push: lessons by creation time then title, vocabulary by form, so equal states encode equally. */
export function snapshot(lessons: BackupLesson[], vocab: VocabEntry[], exportedAtMillis: number): BackupFile {
  return {
    version: 1,
    exportedAtMillis,
    lessons: [...lessons].sort((a, b) => cmp(a.createdAtMillis, b.createdAtMillis) || cmp(a.title, b.title) || cmp(a.text, b.text)),
    vocab: [...vocab].sort((a, b) => cmp(a.form, b.form)),
  };
}

/** Identifies a device's state, ignoring when it was exported and row order. Only compared on the same device. */
export async function fingerprint(state: BackupFile): Promise<string> {
  const s = snapshot(state.lessons, state.vocab, 0);
  const canonical = JSON.stringify({
    lessons: s.lessons.map((l) => [l.title, l.text, l.createdAtMillis, l.currentPage ?? 0, l.videoId ?? null, l.sourceUrl ?? null, l.sharedId ?? null]),
    vocab: s.vocab.map((v) => [v.form, v.lemma ?? null, v.status, v.translation ?? null, v.contextSentence ?? null, v.firstSeenMillis, v.lastSeenMillis, v.timesSeen ?? 1]),
  });
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(canonical));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

/** Lessons are matched by title and text; local lessons the snapshot doesn't have are deleted. */
export function replacePlan<K>(local: Array<[K, BackupLesson]>, incoming: BackupLesson[]): LessonPlan<K> {
  const byKey = new Map<string, K>();
  const del: K[] = [];
  for (const [id, l] of local) {
    const key = lessonKey(l.title, l.text);
    if (byKey.has(key)) del.push(id);
    else byKey.set(key, id);
  }
  const update: Array<[K, BackupLesson]> = [];
  const insert: BackupLesson[] = [];
  const seen = new Set<string>();
  for (const l of incoming) {
    const key = lessonKey(l.title, l.text);
    if (seen.has(key)) continue;
    seen.add(key);
    const id = byKey.get(key);
    if (id !== undefined) {
      byKey.delete(key);
      update.push([id, l]);
    } else insert.push(l);
  }
  del.push(...byKey.values());
  return { update, insert, delete: del };
}

/** Sync down would discard local changes: the state differs from right after the last sync (or never synced). */
export async function hasLocalChanges(state: SyncState | null | undefined, local: BackupFile): Promise<boolean> {
  if (!state) return local.lessons.length > 0 || local.vocab.length > 0;
  return (await fingerprint(local)) !== state.fingerprint;
}
