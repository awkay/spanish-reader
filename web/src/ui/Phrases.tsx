import { useEffect, useRef, useState } from 'preact/hooks';
import { BLANK, DECKS, type DeckId, type PhraseCard, type PhraseMode, filled, hint, session } from '../core/phrases.ts';
import { pageAudio } from '../player.ts';

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
 */
export function Phrases({ deck, mode }: { deck: DeckId; mode: PhraseMode }) {
  const [cards, setCards] = useState(() => session(deck));
  const [i, setI] = useState(0);
  const [shown, setShown] = useState(false);
  const [audio, setAudio] = useState<'idle' | 'loading' | 'playing'>('idle');
  const [error, setError] = useState<string | null>(null);
  // One element for the whole session: once a tap has played it, iOS lets it play again.
  const el = useRef<HTMLAudioElement>(new Audio());
  const card = cards[i];
  const title = `${mode === 'listen' ? 'Listen' : 'Say it'}: ${DECKS[deck].title}`;

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
          <p class="english">That's all {cards.length}. ¡Muy bien!</p>
          <button class="primary block" onClick={() => { setCards(session(deck)); setI(0); }}>Go again ›</button>
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
        {!shown
          ? <button class="primary block" onClick={() => setShown(true)}>{mode === 'say' ? 'Show me' : 'Show answer'}</button>
          : <button class="primary block" onClick={next}>{mode === 'say' ? 'Continue ›' : 'Next ›'}</button>}
        {mode === 'listen' && !shown && <button class="link" onClick={next}>Next ›</button>}
      </main>
    </div>
  );
}
