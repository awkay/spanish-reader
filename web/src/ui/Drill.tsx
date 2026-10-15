import { useEffect, useRef, useState } from 'preact/hooks';
import {
  COMMON_VERBS, type CommonVerb, type DrillCard, TO_LEARN, cardSubjectLabel, currentVerb, filled, isLearned, mastered, round,
  tenseLabel,
} from '../core/drill.ts';
import { answerDrill, cardsFor, drillCards, drillProgress, hideDrillCard } from '../services.ts';
import { useObservable } from './hooks.ts';

type Feedback = { verdict: 'correct' | 'accent' | 'wrong'; learned: boolean } | null;

const errorText = (e: unknown) => (e instanceof Error ? e.message : String(e));

/** Lightning verb drill: type just the verb form, one try, as fast as you can. */
export function Drill() {
  const sets = useObservable(drillCards);
  const progress = useObservable(drillProgress);
  const [verb, setVerb] = useState<CommonVerb | null>(null);
  const [queue, setQueue] = useState<DrillCard[]>([]);
  const [i, setI] = useState(0);
  const [score, setScore] = useState(0);
  const [learnedNow, setLearnedNow] = useState(0);
  const [answer, setAnswer] = useState('');
  const [feedback, setFeedback] = useState<Feedback>(null);
  const [loading, setLoading] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const input = useRef<HTMLInputElement>(null);

  const cardsOf = (v: string) => sets.get(v);
  const current = currentVerb(cardsOf, progress);

  const start = async (v: CommonVerb) => {
    setError(null);
    setLoading(sets.has(v.infinitive) ? null : `Writing cards for ${v.infinitive}…`);
    try {
      const cards = await cardsFor(v);
      setVerb(v);
      setQueue(round(cards, drillProgress.get()));
      setI(0);
      setScore(0);
      setLearnedNow(0);
      setAnswer('');
      setFeedback(null);
      // Get the next verb's cards ready while this one is drilled.
      const next = COMMON_VERBS[COMMON_VERBS.findIndex((x) => x.infinitive === v.infinitive) + 1];
      if (next) void cardsFor(next).catch(() => {});
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(null);
    }
  };

  const card = queue[i];
  const finished = verb !== null && i >= queue.length;

  useEffect(() => {
    if (card && !feedback) input.current?.focus();
  }, [card, feedback]);

  const advance = () => {
    setFeedback(null);
    setAnswer('');
    setI((x) => x + 1);
  };

  const submit = async () => {
    if (!card || feedback) return;
    const r = await answerDrill(card, answer);
    setFeedback(r);
    if (r.verdict === 'correct') {
      setScore((s) => s + 1);
      if (r.learned) setLearnedNow((n) => n + 1);
      setTimeout(advance, r.learned ? 1200 : 650);
    }
  };

  // Enter checks the answer, or moves on after a miss. Listened for on the document: after an answer the input is
  // gone, and focus with it.
  const onKey = useRef<(e: KeyboardEvent) => void>(() => {});
  onKey.current = (e: KeyboardEvent) => {
    if (e.key !== 'Enter' || !card || (e.target as HTMLElement | null)?.tagName === 'BUTTON') return;
    e.preventDefault();
    if (feedback && feedback.verdict !== 'correct') advance();
    else if (!feedback) void submit();
  };
  useEffect(() => {
    const f = (e: KeyboardEvent) => onKey.current(e);
    document.addEventListener('keydown', f);
    return () => document.removeEventListener('keydown', f);
  }, []);

  const verbProgress = (v: string) => {
    const cards = sets.get(v);
    if (!cards) return null;
    return { learned: cards.filter((c) => isLearned(progress.get(c.id))).length, total: cards.length };
  };

  if (verb && card) {
    const p = progress.get(card.id);
    const [before, after] = card.spanish.split('___');
    return (
      <div class="screen">
        <header class="bar">
          <button class="icon-btn" onClick={() => setVerb(null)}>←</button>
          <h1>{verb.infinitive} <span class="muted small">{verb.meaning}</span></h1>
          <span class="small muted grow right">{i + 1}/{queue.length} · ✓{score}</span>
        </header>
        <main class="content drill">
          <div class="small muted">{tenseLabel(card)} · {cardSubjectLabel(card)}</div>
          <p class="english">{card.english}</p>
          <p class="spanish">
            {before}
            <span class={'blank ' + (feedback ? feedback.verdict : '')}>{feedback ? card.answer : ' '.repeat(8)}</span>
            {after}
          </p>
          {!feedback && (
            <input ref={input} type="text" lang="es" autoCapitalize="none" autoCorrect="off" spellcheck={false} autoComplete="off"
              placeholder={`${verb.infinitive} →`} value={answer}
              onInput={(e) => setAnswer((e.target as HTMLInputElement).value)} />
          )}
          {feedback?.verdict === 'correct' && <p class="ok big">✓ {feedback.learned ? 'Learned!' : ''}</p>}
          {feedback && feedback.verdict !== 'correct' && (
            <>
              <p class="error">{feedback.verdict === 'accent' ? 'Check the accent: ' : '✗ '}<b>{card.answer}</b>
                {answer.trim() && <span class="muted"> (you typed {answer.trim()})</span>}</p>
              <p class="small muted">{filled(card)}</p>
              <button class="primary block" onClick={advance}>Next ›</button>
            </>
          )}
          <div class="dots" title={`${p?.correct ?? 0} of ${TO_LEARN} right`}>
            {Array.from({ length: TO_LEARN }, (_, k) => <span key={k} class={k < (p?.correct ?? 0) ? 'on' : ''} />)}
          </div>
          {!feedback && (
            <div class="row spread">
              <button class="primary" disabled={!answer.trim()} onClick={() => void submit()}>Check</button>
              <button class="link" onClick={async () => { await hideDrillCard(card); advance(); }}>Bad card</button>
            </div>
          )}
        </main>
      </div>
    );
  }

  if (finished && verb) {
    const vp = verbProgress(verb.infinitive);
    const isMastered = mastered(sets.get(verb.infinitive) ?? [], progress);
    const next = isMastered ? currentVerb(cardsOf, progress) : verb;
    return (
      <div class="screen">
        <header class="bar"><button class="icon-btn" onClick={() => setVerb(null)}>←</button><h1>Round done</h1></header>
        <main class="content drill">
          <p class="english">{score} of {queue.length} right</p>
          {learnedNow > 0 && <p>{learnedNow} card{learnedNow > 1 ? 's' : ''} learned this round.</p>}
          {vp && <p class="muted">{verb.infinitive}: {vp.learned} of {vp.total} cards learned{isMastered ? ' — mastered!' : ''}</p>}
          <button class="primary block" onClick={() => start(next)}>
            {isMastered ? `Next verb: ${next.infinitive} ›` : 'Another round ›'}
          </button>
          {loading && <p class="muted">{loading}</p>}
          {error && <p class="error">{error}</p>}
        </main>
      </div>
    );
  }

  const cp = verbProgress(current.infinitive);
  return (
    <div class="screen">
      <header class="bar"><a class="icon-btn" href="#/">←</a><h1>Lightning verbs</h1></header>
      <main class="content drill">
        <p class="small muted">
          Type just the verb form for each sentence. One try each; a card is learned after {TO_LEARN} right answers (a miss
          takes one back), and learned forms go into your vocabulary as Known. Master a verb's cards to move on.
        </p>
        <div class="note">
          <div class="verb"><b>{current.infinitive}</b> <span class="muted">— {current.meaning}</span></div>
          <div class="small muted">{cp ? `${cp.learned} of ${cp.total} cards learned` : 'new verb'}</div>
        </div>
        <button class="primary block" disabled={loading !== null} onClick={() => start(current)}>⚡ Start</button>
        {loading && <p class="muted">{loading}</p>}
        {error && <p class="error">{error}</p>}
        <details>
          <summary>All verbs</summary>
          {COMMON_VERBS.map((v) => {
            const vp = verbProgress(v.infinitive);
            const done = mastered(sets.get(v.infinitive) ?? [], progress);
            return (
              <div key={v.infinitive} class="vocab-row" onClick={() => loading === null && start(v)}>
                <div class="row">
                  <b>{v.infinitive}</b><span class="small muted">{v.meaning}</span>
                  <span class="small muted grow right">{done ? '✓ mastered' : vp ? `${vp.learned}/${vp.total}` : ''}</span>
                </div>
              </div>
            );
          })}
        </details>
      </main>
    </div>
  );
}
