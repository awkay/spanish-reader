import { useEffect, useRef, useState } from 'preact/hooks';
import { BLANK, DECKS, type DeckId, type PhraseCard, type PhraseMode, TO_RETIRE, filled, hint, scoreKey, session } from '../core/phrases.ts';
import { pageAudio } from '../player.ts';
import { answerPhrase, phrasePool, phraseScores, topUpPhrases } from '../services.ts';

/** "●●○" for a score of 2 out of the 3 that retire a sentence. */
const dots = (score: number) => '●'.repeat(Math.min(score, TO_RETIRE)) + '○'.repeat(Math.max(0, TO_RETIRE - score));

/** Has the AI write more sentences in the background when retirements left the deck short (best effort). */
const topUp = (deck: DeckId) => void topUpPhrases(deck).catch(() => {});

const newSession = (deck: DeckId, mode: PhraseMode) => session(phrasePool(deck, mode), mode, phraseScores.get());

const audioCache = new Map<string, Promise<Blob>>();
/** Object URLs of audio already fetched, so a tap can start it at once (iOS only plays audio started by a tap). */
const readyUrls = new Map<string, string>();

/** The sentence's audio (Piper on the server, kept in IndexedDB like page audio). */
function sentenceAudio(text: string): Promise<Blob> {
  let p = audioCache.get(text);
  if (!p) {
    p = pageAudio([text]).then((row) => {
      if (!readyUrls.has(text)) readyUrls.set(text, URL.createObjectURL(row.blob));
      return row.blob;
    });
    p.catch(() => audioCache.delete(text));
    audioCache.set(text, p);
  }
  return p;
}

function Sentence({ card, shown }: { card: PhraseCard; shown: boolean }) {
  const [before, after] = card.spanish.split(BLANK);
  return (
    <p class="spanish">
      {before}
      <span class={'blank' + (shown ? ' shown' : '')}>{shown ? card.answer : ' '.repeat(8)}</span>
      {after}
    </p>
  );
}

/**
 * Say it: English, then Spanish with the target words blank; say them out loud, then "Show me".
 * Listen: play the Spanish sentence, translate it in your head, then "Show answer".
 * Then "Got it" / "Missed it" scores the sentence; at TO_RETIRE it retires and a new one takes its place.
 */
export function Phrases({ deck, mode }: { deck: DeckId; mode: PhraseMode }) {
  const [cards, setCards] = useState(() => newSession(deck, mode));
  const [tally, setTally] = useState({ right: 0, wrong: 0, retired: 0 });
  const [flash, setFlash] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [i, setI] = useState(0);
  const [shown, setShown] = useState(false);
  const [audio, setAudio] = useState<'idle' | 'loading' | 'playing'>('idle');
  const [error, setError] = useState<string | null>(null);
  // One element for the whole session: once a tap has played it, iOS lets it play again.
  const el = useRef<HTMLAudioElement>(new Audio());
  const card = cards[i];
  const title = `${mode === 'listen' ? 'Listen' : 'Say it'}: ${DECKS[deck].title}`;

  useEffect(() => topUp(deck), [deck]);
  useEffect(() => {
    const a = el.current;
    a.onended = () => setAudio('idle');
    a.onpause = () => setAudio('idle');
    return () => a.pause();
  }, []);
  // Have the next sentence's audio ready before it's needed.
  useEffect(() => {
    if (mode !== 'listen') return;
    if (card) void sentenceAudio(filled(card)).catch(() => {});
    const next = cards[i + 1];
    if (next) void sentenceAudio(filled(next)).catch(() => {});
  }, [i, cards, mode]);

  const play = async () => {
    if (!card) return;
    const text = filled(card);
    setError(null);
    const a = el.current;
    try {
      let url = readyUrls.get(text);
      if (!url) {
        setAudio('loading');
        await sentenceAudio(text);
        url = readyUrls.get(text)!;
      }
      a.src = url;
      a.currentTime = 0;
      await a.play();
      setAudio('playing');
    } catch (e) {
      setAudio('idle');
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  const answer = async (right: boolean) => {
    if (!card || busy) return;
    setBusy(true);
    try {
      const a = await answerPhrase(mode, card, right);
      setTally((t) => ({ right: t.right + (right ? 1 : 0), wrong: t.wrong + (right ? 0 : 1), retired: t.retired + (a.retired ? 1 : 0) }));
      setFlash(a.retired ? 'Retired ✓ A new sentence will take its place.' : null);
      if (a.retired) topUp(deck);
      next();
    } finally {
      setBusy(false);
    }
  };

  const skip = () => {
    setFlash(null);
    next();
  };

  const next = () => {
    el.current.pause();
    setAudio('idle');
    setShown(false);
    setError(null);
    setI((x) => x + 1);
  };

  const exit = <a class="icon-btn" href="#/" title="Stop">✕</a>;

  if (!card) {
    return (
      <div class="screen">
        <header class="bar">{exit}<h1>{title}</h1></header>
        <main class="content phrases">
          {flash && <p class="small accent">{flash}</p>}
          <p class="english">That's all {cards.length}. ¡Muy bien!</p>
          <p>Got it: {tally.right} · Missed: {tally.wrong} · Retired: {tally.retired}</p>
          <button class="primary block" onClick={() => { setCards(newSession(deck, mode)); setI(0); setFlash(null); }}>Go again ›</button>
        </main>
      </div>
    );
  }

  const h = hint(card);
  return (
    <div class="screen">
      <header class="bar">
        {exit}
        <h1>{title}</h1>
        <span class="small muted grow right">{i + 1}/{cards.length}</span>
      </header>
      <main class="content phrases">
        {flash && <p class="small accent">{flash}</p>}
        {mode === 'say' ? (
          <>
            {h && <div class="small muted">{h}</div>}
            <p class="english">{card.english}</p>
            <Sentence card={card} shown={shown} />
          </>
        ) : (
          <>
            <button class="play-big" onClick={play} disabled={audio === 'loading'} title="Play">
              {audio === 'loading' ? '…' : audio === 'playing' ? '🔊' : '▶'}
            </button>
            {shown && (
              <>
                <Sentence card={card} shown />
                <p class="english">{card.english}</p>
                {h && <div class="small muted">{h}</div>}
              </>
            )}
          </>
        )}
        {shown && card.note && <p class="note small">{card.note}</p>}
        {error && <p class="error">{error}</p>}
        {!shown ? (
          <>
            <button class="primary block" onClick={() => setShown(true)}>{mode === 'say' ? 'Show me' : 'Show answer'}</button>
            <button class="link" onClick={skip}>Skip ›</button>
          </>
        ) : (
          <>
            <p class="rate-title">Did you get it? <span class="dots">{dots(phraseScores.get().get(scoreKey(mode, card.id))?.score ?? 0)}</span></p>
            <div class="rate">
              <button class="block" disabled={busy} onClick={() => answer(false)}>✗ Missed it</button>
              <button class="primary block" disabled={busy} onClick={() => answer(true)}>✓ Got it</button>
            </div>
            <p class="small muted">Got it adds one, missed it takes one off; at {TO_RETIRE} the sentence retires and a new one replaces it.</p>
          </>
        )}
      </main>
    </div>
  );
}
