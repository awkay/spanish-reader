/// <reference lib="webworker" />
// Offline support: the app shell is cached per build; /api is never cached here (data lives in IndexedDB).
declare const BUILD: string;
declare const SHELL: string[];

const sw = self as unknown as ServiceWorkerGlobalScope;
const CACHE = 'shell-' + BUILD;

sw.addEventListener('install', (e: ExtendableEvent) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => sw.skipWaiting()));
});

sw.addEventListener('activate', (e: ExtendableEvent) => {
  e.waitUntil(caches.keys()
    .then((keys) => Promise.all(keys.filter((k) => k.startsWith('shell-') && k !== CACHE).map((k) => caches.delete(k))))
    .then(() => sw.clients.claim()));
});

sw.addEventListener('fetch', (e: FetchEvent) => {
  const url = new URL(e.request.url);
  if (e.request.method !== 'GET' || url.origin !== sw.location.origin || url.pathname.startsWith('/api/')) return;
  // Navigations: network first (so updates arrive), cached shell when offline.
  if (e.request.mode === 'navigate') {
    e.respondWith(fetch(e.request).catch(() => caches.match('./index.html').then((r) => r ?? Response.error())));
    return;
  }
  e.respondWith(caches.match(e.request).then((hit) => hit ?? fetch(e.request)));
});
