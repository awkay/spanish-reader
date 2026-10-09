// Local storage in IndexedDB. Everything personal (lessons, vocabulary, statuses) lives only here; AI results and
// audio are cached here too so reading and listening work offline.
import type { FoundPhrase, Gloss } from './core/gloss.ts';

export interface Lesson {
  id: string;
  title: string;
  text: string;
  createdAt: number;
  currentPage: number;
  /** Server id when this lesson came from (or was shared to) the shared library. */
  sharedId?: string | null;
  /** YouTube video this lesson was transcribed from; its audio is cut from the original recording. */
  videoId?: string | null;
  sourceUrl?: string | null;
}

export interface GlossRow { key: string; formKey: string; hash: string; gloss: Gloss; storedAt: number }
export interface SentenceRow { hash: string; translation: string | null; phrases: FoundPhrase[]; scanned: boolean }
export interface AudioRow { key: string; blob: Blob; timings: Array<[number, number]>; storedAt: number }

const DB_NAME = 'spanish-reader';
const VERSION = 1;
const STORES = ['lessons', 'vocab', 'glosses', 'sentences', 'audio', 'settings'] as const;
type StoreName = (typeof STORES)[number];

let dbPromise: Promise<IDBDatabase> | null = null;

export function openDb(factory: IDBFactory = indexedDB): Promise<IDBDatabase> {
  if (dbPromise) return dbPromise;
  dbPromise = new Promise((resolve, reject) => {
    const req = factory.open(DB_NAME, VERSION);
    req.onupgradeneeded = () => {
      const db = req.result;
      db.createObjectStore('lessons', { keyPath: 'id' });
      db.createObjectStore('vocab', { keyPath: 'form' });
      const g = db.createObjectStore('glosses', { keyPath: 'key' });
      g.createIndex('formKey', 'formKey');
      db.createObjectStore('sentences', { keyPath: 'hash' });
      db.createObjectStore('audio', { keyPath: 'key' });
      db.createObjectStore('settings');
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
  return dbPromise;
}

/** For tests: forget the open connection. */
export function resetDbForTests() {
  dbPromise = null;
}

function wrap<T>(req: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

async function tx(store: StoreName, mode: IDBTransactionMode): Promise<IDBObjectStore> {
  return (await openDb()).transaction(store, mode).objectStore(store);
}

export async function get<T>(store: StoreName, key: IDBValidKey): Promise<T | undefined> {
  return wrap((await tx(store, 'readonly')).get(key)) as Promise<T | undefined>;
}

export async function getAll<T>(store: StoreName): Promise<T[]> {
  return wrap((await tx(store, 'readonly')).getAll()) as Promise<T[]>;
}

export async function getMany<T>(store: StoreName, keys: IDBValidKey[]): Promise<Array<T | undefined>> {
  if (keys.length === 0) return [];
  const s = await tx(store, 'readonly');
  return Promise.all(keys.map((k) => wrap(s.get(k)) as Promise<T | undefined>));
}

/** Writes all values in one transaction. */
export async function putAll(store: StoreName, values: unknown[], keys?: IDBValidKey[]): Promise<void> {
  if (values.length === 0) return;
  const db = await openDb();
  await new Promise<void>((resolve, reject) => {
    const t = db.transaction(store, 'readwrite');
    const s = t.objectStore(store);
    values.forEach((v, i) => (keys ? s.put(v, keys[i]) : s.put(v)));
    t.oncomplete = () => resolve();
    t.onerror = () => reject(t.error);
    t.onabort = () => reject(t.error);
  });
}

export const put = (store: StoreName, value: unknown, key?: IDBValidKey) => putAll(store, [value], key === undefined ? undefined : [key]);

export async function del(store: StoreName, key: IDBValidKey): Promise<void> {
  await wrap((await tx(store, 'readwrite')).delete(key));
}

export async function clear(store: StoreName): Promise<void> {
  await wrap((await tx(store, 'readwrite')).clear());
}

/** The most recently stored gloss of [formKey] in any sentence (offline fallback). */
export async function latestGlossForForm(formKey: string): Promise<GlossRow | undefined> {
  const rows = (await wrap((await tx('glosses', 'readonly')).index('formKey').getAll(formKey))) as GlossRow[];
  return rows.sort((a, b) => b.storedAt - a.storedAt)[0];
}

/** Every stored gloss of each of [formKeys], in one transaction. */
export async function glossesForForms(formKeys: string[]): Promise<Map<string, GlossRow[]>> {
  const index = (await tx('glosses', 'readonly')).index('formKey');
  const rows = await Promise.all(formKeys.map((k) => wrap(index.getAll(k)) as Promise<GlossRow[]>));
  return new Map(formKeys.map((k, i) => [k, rows[i]]));
}

export const glossKey = (hash: string, formKey: string) => `${hash}|${formKey}`;
