import { useState } from 'preact/hooks';
import { normalize } from '../core/text.ts';
import { STATUS_LABEL, Status, isLearning } from '../core/vocab.ts';
import { forgetWord, setWordStatus, vocab as vocabObs } from '../services.ts';
import { statusSwatch } from './colors.ts';
import { useObservable } from './hooks.ts';

const FILTERS: Array<[string, (s: number) => boolean]> = [
  ['Learning', isLearning], ['1', (s) => s === 1], ['2', (s) => s === 2], ['3', (s) => s === 3], ['4', (s) => s === 4],
  ['Known', (s) => s === Status.KNOWN], ['Ignored', (s) => s === Status.IGNORED], ['All', () => true],
];

export function Vocabulary() {
  const vocab = useObservable(vocabObs);
  const [filter, setFilter] = useState(0);
  const [query, setQuery] = useState('');
  const [open, setOpen] = useState<string | null>(null);
  const all = [...vocab.values()].sort((a, b) => b.lastSeenMillis - a.lastSeenMillis);
  const q = normalize(query.trim());
  const shown = all.filter((e) => FILTERS[filter][1](e.status) &&
    (!q || e.form.includes(q) || (e.lemma && normalize(e.lemma).includes(q)) || e.translation?.toLowerCase().includes(q)));
  return (
    <div class="screen">
      <header class="bar"><a class="icon-btn" href="#/">←</a><h1>Vocabulary</h1></header>
      <main class="content">
        <input type="search" placeholder="Search word, lemma or meaning" value={query} onInput={(e) => setQuery((e.target as HTMLInputElement).value)} />
        <div class="chips scroll">
          {FILTERS.map(([label, f], i) => (
            <button key={label} class={'chip' + (filter === i ? ' on' : '')} onClick={() => setFilter(i)}>
              {label} {all.filter((e) => f(e.status)).length}
            </button>
          ))}
        </div>
        {shown.length === 0 && <p class="muted">No words here yet. Tap words while reading to add them.</p>}
        {shown.slice(0, 500).map((e) => (
          <div class="vocab-row" key={e.form} onClick={() => setOpen(open === e.form ? null : e.form)}>
            <div class="row">
              <span class="dot" style={{ background: statusSwatch(e.status) }} />
              <b>{e.form}</b>
              {e.lemma && e.lemma !== e.form && <span class="small muted">({e.lemma})</span>}
              <span class="small muted grow right">{STATUS_LABEL[e.status]}</span>
            </div>
            {e.translation && <div class="indent">{e.translation}</div>}
            {open === e.form && (
              <div class="indent" onClick={(ev) => ev.stopPropagation()}>
                {e.contextSentence && <p class="sentence small">“{e.contextSentence}”</p>}
                <div class="chips">
                  {[1, 2, 3, 4, 5, -1].map((s) => (
                    <button key={s} class={'chip' + (e.status === s ? ' on' : '')} onClick={() => setWordStatus(e.form, s)}>{STATUS_LABEL[s]}</button>
                  ))}
                  <button class="link" onClick={() => forgetWord(e.form)}>Forget word</button>
                </div>
              </div>
            )}
          </div>
        ))}
      </main>
    </div>
  );
}
