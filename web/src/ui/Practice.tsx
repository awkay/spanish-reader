import { useEffect, useRef, useState } from 'preact/hooks';
import {
  type AnswerCheck, MAX_TRIES, type PracticeSpec, type PracticeVerb, type VerbExercise, checkAnswer, generateExercise, pickExercise,
  sameAnswer, subjectLabel,
} from '../core/practice.ts';
import { complete, loadPracticeVerbs, vocab } from '../services.ts';

interface Try { answer: string; check: AnswerCheck }

const errorText = (e: unknown) => (e instanceof Error ? e.message : String(e));

/** Verb practice: a verb you've met, a random tense and subject; translate the AI's English sentence in 3 tries. */
export function Practice() {
  const [verbs, setVerbs] = useState<PracticeVerb[] | null>(null);
  const [spec, setSpec] = useState<PracticeSpec | null>(null);
  const [exercise, setExercise] = useState<VerbExercise | null>(null);
  const [tries, setTries] = useState<Try[]>([]);
  const [answer, setAnswer] = useState('');
  const [busy, setBusy] = useState(false);
  const [gaveUp, setGaveUp] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const recent = useRef<string[]>([]);
  const input = useRef<HTMLTextAreaElement>(null);

  const next = async (vs: PracticeVerb[] | null = verbs) => {
    if (!vs) return;
    const s = pickExercise(vs, [...vocab.get().values()], Math.random, recent.current);
    setSpec(s);
    setExercise(null);
    setTries([]);
    setAnswer('');
    setGaveUp(false);
    setError(null);
    if (!s) return;
    recent.current = [s.verb.infinitive, ...recent.current].slice(0, Math.min(5, vs.length - 1));
    setBusy(true);
    try {
      setExercise(await generateExercise(complete, s));
      setTimeout(() => input.current?.focus(), 0);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  };

  useEffect(() => {
    loadPracticeVerbs().then((vs) => {
      setVerbs(vs);
      next(vs);
    }, (e) => setError(errorText(e)));
  }, []);

  const solved = tries.some((t) => t.check.correct);
  const done = solved || gaveUp || tries.length >= MAX_TRIES;

  const submit = async () => {
    const a = answer.trim();
    if (!spec || !exercise || !a || busy || done) return;
    setBusy(true);
    setError(null);
    try {
      const check = await checkAnswer(complete, spec, exercise, tries.map((t) => t.answer), a);
      setTries([...tries, { answer: a, check }]);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  };

  const meaning = spec?.verb.meaning ?? exercise?.verbMeaning;
  const lastAnswer = tries[tries.length - 1]?.answer;

  return (
    <div class="screen">
      <header class="bar"><a class="icon-btn" href="#/">←</a><h1>Verb practice</h1></header>
      <main class="content practice">
        {verbs === null && !error && <p class="muted">Loading your verbs…</p>}
        {verbs !== null && verbs.length === 0 && (
          <p class="muted">No verbs yet. Verbs you tap or read past (with their meaning looked up) show up here.</p>
        )}
        {spec && (
          <>
            <div class="note">
              <div class="verb"><b>{spec.verb.infinitive}</b>{meaning && <span class="muted"> — {meaning}</span>}</div>
              <div class="small">{spec.tense.label} <span class="muted">(e.g. {spec.tense.example})</span> · {subjectLabel(spec.subject)}</div>
            </div>
            {busy && !exercise && <p class="muted">Writing a sentence…</p>}
            {exercise && (
              <>
                <p class="english">{exercise.english}</p>
                {tries.map((t, i) => (
                  <div key={i} class="try">
                    <div><span class={t.check.correct ? 'ok' : 'error'}>{t.check.correct ? '✓' : '✗'}</span> {t.answer}</div>
                    <div class="small">{t.check.feedback}</div>
                    {t.check.hint && !done && i === tries.length - 1 && <div class="small hint">Hint: {t.check.hint}</div>}
                    {t.check.hint && (done || i < tries.length - 1) && <div class="small muted">Hint: {t.check.hint}</div>}
                  </div>
                ))}
                {!done && (
                  <>
                    <textarea ref={input} lang="es" rows={2} autocapitalize="sentences" spellcheck={false}
                      placeholder={`En español… (try ${tries.length + 1} of ${MAX_TRIES})`} value={answer}
                      onInput={(e) => setAnswer((e.target as HTMLTextAreaElement).value)}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter' && !e.shiftKey) {
                          e.preventDefault();
                          submit();
                        }
                      }} />
                    <div class="row">
                      <button class="primary" disabled={busy || !answer.trim()} onClick={submit}>{busy ? 'Checking…' : 'Check'}</button>
                      <button class="link" disabled={busy} onClick={() => setGaveUp(true)}>Show answer</button>
                    </div>
                  </>
                )}
                {done && (
                  <div class="note">
                    {solved
                      ? (lastAnswer && !sameAnswer(lastAnswer, exercise.spanish)
                        ? <div>Correct! Another way to say it: <b>{exercise.spanish}</b></div>
                        : <div class="ok">Correct!</div>)
                      : <div>Answer: <b>{exercise.spanish}</b></div>}
                    {exercise.verbForm && <div class="small muted">Verb: {exercise.verbForm}</div>}
                  </div>
                )}
              </>
            )}
            {error && <p class="error">{error}</p>}
            {(done || error) && <button class="primary block" disabled={busy} onClick={() => next()}>Next verb ›</button>}
            {!done && !error && exercise && <button class="link" disabled={busy} onClick={() => next()}>Skip this one</button>}
          </>
        )}
      </main>
    </div>
  );
}
