// Page audio: one MP3 per page from the server (Piper), saved in IndexedDB for offline listening, played through a
// single <audio> element so iOS keeps playing with the screen locked. Sentence timings drive the highlight and
// "loop sentence".
import { api } from './api.ts';
import { sentenceHash } from './core/hash.ts';
import * as db from './db.ts';
import type { AudioRow } from './db.ts';
import { Observable, settings } from './services.ts';

export interface PageAudioRequest {
  lessonTitle: string;
  pageIndex: number;
  /** Set for YouTube lessons: the page audio is cut from the original recording instead of synthesized. */
  videoId?: string | null;
  /** Speakable sentences of the page, in order, with their tokenizer sentence index. */
  sentences: Array<{ index: number; text: string }>;
}

export interface PlayerState {
  pageIndex: number | null;
  /** Tokenizer sentence index being spoken (or last spoken). */
  sentenceIndex: number | null;
  playing: boolean;
  loading: boolean;
  loop: boolean;
  error: string | null;
}

const audioKey = async (sentences: string[], videoId?: string | null) =>
  sentenceHash((videoId ? `youtube:${videoId}\u0000` : 'audio\u0000') + sentences.join('\u0000'));

/** Fetches (or reads from IndexedDB) the page's MP3 and timings: Piper, or the original recording of a video. */
export async function pageAudio(sentences: string[], videoId?: string | null): Promise<AudioRow> {
  const key = await audioKey(sentences, videoId);
  const cached = await db.get<AudioRow>('audio', key);
  if (cached) return cached;
  const r = videoId ? await api.videoAudio(videoId, sentences) : await api.tts(sentences);
  const blob = await api.audio(r.audio);
  const row: AudioRow = { key, blob, timings: r.timings, storedAt: Date.now() };
  await db.put('audio', row);
  return row;
}

export class Player {
  readonly state = new Observable<PlayerState>({ pageIndex: null, sentenceIndex: null, playing: false, loading: false, loop: false, error: null });
  private el: HTMLAudioElement;
  private req: PageAudioRequest | null = null;
  private timings: Array<[number, number]> = [];
  private url: string | null = null;
  private current = 0; // index into req.sentences
  /** Called when a page's audio ends, so the reader can turn the page and hand over the next one. */
  onPageEnded: (pageIndex: number) => void = () => {};

  constructor(el: HTMLAudioElement = new Audio()) {
    this.el = el;
    this.el.preload = 'auto';
    this.el.addEventListener('timeupdate', () => this.tick());
    this.el.addEventListener('play', () => this.patch({ playing: true }));
    this.el.addEventListener('pause', () => this.patch({ playing: false }));
    this.el.addEventListener('ended', () => {
      this.patch({ playing: false });
      if (this.req) this.onPageEnded(this.req.pageIndex);
    });
    settings.subscribe((s) => (this.el.playbackRate = s.speed));
    this.setupMediaSession();
  }

  private patch(p: Partial<PlayerState>) {
    this.state.set({ ...this.state.get(), ...p });
  }

  /** Loads a page and plays from sentence [from] (index into req.sentences). */
  async play(req: PageAudioRequest, from = 0) {
    if (req.sentences.length === 0) {
      this.onPageEnded(req.pageIndex);
      return;
    }
    const same = this.req && this.req.pageIndex === req.pageIndex && this.req.lessonTitle === req.lessonTitle && this.url;
    this.req = req;
    if (!same) {
      this.patch({ loading: true, error: null, pageIndex: req.pageIndex, sentenceIndex: req.sentences[from]?.index ?? null });
      try {
        const row = await pageAudio(req.sentences.map((s) => s.text), req.videoId);
        if (this.req !== req) return; // superseded while loading
        if (this.url) URL.revokeObjectURL(this.url);
        this.url = URL.createObjectURL(row.blob);
        this.timings = row.timings;
        this.el.src = this.url;
      } catch (e) {
        this.patch({ loading: false, error: (e as Error).message });
        return;
      }
    }
    this.seekSentence(from);
    this.el.playbackRate = settings.get().speed;
    try {
      await this.el.play();
    } catch (e) {
      this.patch({ error: (e as Error).message });
    }
    this.patch({ loading: false });
    this.updateMetadata();
  }

  /** Downloads a page's audio ahead of time (no playback). */
  prefetch(sentences: string[], videoId?: string | null) {
    if (sentences.length) pageAudio(sentences, videoId).catch(() => {});
  }

  pause() {
    this.el.pause();
  }

  resume() {
    if (this.req) this.el.play().catch((e) => this.patch({ error: (e as Error).message }));
  }

  get loadedPage(): number | null {
    return this.req?.pageIndex ?? null;
  }

  private seekSentence(i: number) {
    const t = this.timings[Math.max(0, Math.min(i, this.timings.length - 1))];
    this.current = Math.max(0, Math.min(i, this.timings.length - 1));
    this.el.currentTime = t ? t[0] / 1000 : 0;
    this.patch({ sentenceIndex: this.req?.sentences[this.current]?.index ?? null });
  }

  next() {
    if (!this.req) return;
    if (this.current + 1 < this.timings.length) this.seekSentence(this.current + 1);
    else this.onPageEnded(this.req.pageIndex);
  }

  previous() {
    if (!this.req) return;
    // Within the first second of a sentence, go to the previous one; otherwise restart this one.
    const t = this.timings[this.current];
    const into = this.el.currentTime * 1000 - (t?.[0] ?? 0);
    this.seekSentence(into < 1000 ? this.current - 1 : this.current);
  }

  toggleLoop() {
    this.patch({ loop: !this.state.get().loop });
  }

  /** Jumps to a sentence (tokenizer index) of the loaded page. Returns false if it's not on this page. */
  playSentence(sentenceIndex: number): boolean {
    if (!this.req) return false;
    const i = this.req.sentences.findIndex((s) => s.index === sentenceIndex);
    if (i < 0) return false;
    this.seekSentence(i);
    this.resume();
    return true;
  }

  private tick() {
    const ms = this.el.currentTime * 1000;
    const s = this.state.get();
    const cur = this.timings[this.current];
    if (s.loop && cur && ms >= cur[1]) {
      this.el.currentTime = cur[0] / 1000;
      return;
    }
    let i = this.timings.findIndex(([, end], k) => ms < end + (k + 1 < this.timings.length ? (this.timings[k + 1][0] - end) : 0));
    if (i < 0) i = this.timings.length - 1;
    if (i !== this.current || s.sentenceIndex === null) {
      this.current = i;
      this.patch({ sentenceIndex: this.req?.sentences[i]?.index ?? null });
      this.updateMetadata();
    }
  }

  private updateMetadata() {
    if (!('mediaSession' in navigator) || !this.req) return;
    const text = this.req.sentences[this.current]?.text ?? '';
    navigator.mediaSession.metadata = new MediaMetadata({ title: text, artist: this.req.lessonTitle, album: `Page ${this.req.pageIndex + 1}` });
  }

  private setupMediaSession() {
    if (typeof navigator === 'undefined' || !('mediaSession' in navigator)) return;
    const ms = navigator.mediaSession;
    ms.setActionHandler('play', () => this.resume());
    ms.setActionHandler('pause', () => this.pause());
    ms.setActionHandler('nexttrack', () => this.next());
    ms.setActionHandler('previoustrack', () => this.previous());
  }
}
