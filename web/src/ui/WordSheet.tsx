import { CLITIC_LABEL, type Gloss, cliticRole, lacksDetail, splitClitics, verbSummary } from '../core/gloss.ts';
import { STATUS_LABEL, Status } from '../core/vocab.ts';
import { setWordStatus } from '../services.ts';
import { CLITIC_COLOR, statusSwatch } from './colors.ts';
import type { PhraseSpan, Selection } from './Reader.tsx';

const CHOICES: Array<[number, string]> = [
  [Status.LEVEL_1, '1'], [Status.RECOGNIZED, '2'], [Status.FAMILIAR, '3'], [Status.LEARNED, '4'], [Status.KNOWN, 'Known ✓'], [Status.IGNORED, 'Ignore'],
];

function speak(text: string) {
  if (!('speechSynthesis' in window)) return;
  const u = new SpeechSynthesisUtterance(text);
  u.lang = 'es-MX';
  const voice = speechSynthesis.getVoices().find((v) => v.lang.replace('_', '-').startsWith('es-MX'))
    ?? speechSynthesis.getVoices().find((v) => v.lang.startsWith('es'));
  if (voice) u.voice = voice;
  speechSynthesis.cancel();
  speechSynthesis.speak(u);
}

export function WordSheet({ selection, status, phrases, onImprove, onClose }: {
  selection: Selection; status: number; phrases: PhraseSpan[]; onImprove: () => void; onClose: () => void;
}) {
  const { token, sentence, result } = selection;
  const gloss = result && 'gloss' in result ? result.gloss : null;
  const expressions = [...phrases.map((p) => [p.phrase, p.meaning] as [string, string])];
  if (gloss?.isIdiomOrPhrase && gloss.phrase && !expressions.some(([p]) => p.toLowerCase() === gloss.phrase!.toLowerCase())) {
    expressions.push([gloss.phrase, gloss.phraseMeaning ?? '']);
  }
  const seen = new Set<string>();
  return (
    <div class="sheet-backdrop" onClick={onClose}>
      <section class="sheet" onClick={(e) => e.stopPropagation()} role="dialog" aria-label={token.text}>
        <div class="row">
          <h2 class="grow word">{token.text}</h2>
          <button class="icon-btn" title="Pronounce" onClick={() => speak(token.text)}>🔊</button>
          {result && (selection.improving ? <span class="muted small">Improving…</span>
            : <button class="link" onClick={onImprove}>✨ Improve</button>)}
        </div>
        {selection.improveError && <p class="error small">Improve failed: {selection.improveError}</p>}
        {expressions.filter(([p]) => !seen.has(p.toLowerCase()) && seen.add(p.toLowerCase())).map(([p, m]) => (
          <div class="section" key={p}><div class="label">Expression</div><div><b>{p}</b>{m && ` — ${m}`}</div></div>
        ))}
        {!result && <p class="muted">Looking up…</p>}
        {result && 'error' in result && <p class="error">{result.error}</p>}
        {gloss && <GlossDetails gloss={gloss} written={token.text} fromOther={result !== null && 'gloss' in result && result.fromOtherSentence} />}
        <p class="sentence">“{sentence}”</p>
        {selection.translation === null && <p class="muted small">Translating…</p>}
        {typeof selection.translation === 'string' && <p class="translation">{selection.translation}</p>}
        {selection.translation && typeof selection.translation === 'object' && <p class="error small">{selection.translation.error}</p>}
        <div class="label">Status</div>
        <div class="chips">
          {CHOICES.map(([s, label]) => (
            <button key={s} class={'chip' + (status === s ? ' on' : '')} style={status === s ? { background: statusSwatch(s) } : undefined}
              onClick={() => setWordStatus(token.normalized!, s, sentence)} title={STATUS_LABEL[s]}>{label}</button>
          ))}
        </div>
      </section>
    </div>
  );
}

function GlossDetails({ gloss: g, written, fromOther }: { gloss: Gloss; written: string; fromOther: boolean }) {
  const sub = [g.lemma && g.lemma.toLowerCase() !== g.form.toLowerCase() ? `from ${g.lemma}` : null, g.partOfSpeech || null].filter(Boolean).join(' · ');
  return (
    <>
      {sub && <div class="muted">{sub}</div>}
      <div class="meaning">{g.meaningInContext}</div>
      {fromOther && <p class="error small">Couldn't reach the AI; this meaning is from another sentence.</p>}
      {lacksDetail(g) && <p class="small muted">Saved before detailed explanations existed — tap Improve for conjugation, pronouns and roots.</p>}
      {g.verb && (
        <div class="section">
          <div class="label">Verb</div>
          <div><b>{g.verb.infinitive}</b>{verbSummary(g.verb) && ` · ${verbSummary(g.verb)}`}</div>
          {g.verb.formation && <div class="small"><b>How it's built:</b> {g.verb.formation}</div>}
          {g.verb.whyThisForm && <div class="small"><b>Why this form:</b> {g.verb.whyThisForm}</div>}
        </div>
      )}
      {g.clitics && g.clitics.length > 0 && <Clitics written={written} gloss={g} />}
      {g.roots && <div class="section"><div class="label">Roots</div>{g.roots}</div>}
      {g.grammarNote && <div class="section"><div class="label">Note</div>{g.grammarNote}</div>}
      {g.otherMeanings && g.otherMeanings.length > 0 && <div class="small muted">Other meanings: {g.otherMeanings.join(', ')}</div>}
    </>
  );
}

function Clitics({ written, gloss }: { written: string; gloss: Gloss }) {
  const clitics = gloss.clitics ?? [];
  const split = splitClitics(written, clitics);
  const remaining = [...clitics];
  const attachedRoles = split.attached.map((p) => {
    const i = remaining.findIndex((c) => c.pronoun.toLowerCase() === p.toLowerCase());
    const c = i >= 0 ? remaining.splice(i, 1)[0] : null;
    return c ? cliticRole(c.role) : 'OTHER';
  });
  const attachedLeft = split.attached.map((p) => p.toLowerCase());
  return (
    <div class="section">
      <div class="label">Pronouns</div>
      {split.attached.length > 0 && (
        <div class="clitic-split">
          {split.base}
          {split.attached.map((p, i) => <> + <span class="tag" style={{ background: CLITIC_COLOR[attachedRoles[i]] + '59' }}>{p}</span></>)}
        </div>
      )}
      {clitics.map((c, i) => {
        const role = cliticRole(c.role);
        const k = attachedLeft.indexOf(c.pronoun.toLowerCase());
        const attached = k >= 0 && (attachedLeft.splice(k, 1), true);
        return (
          <div key={i}>
            <span class="tag" style={{ background: CLITIC_COLOR[role] + '59' }}>{c.pronoun}</span>{' '}
            <b>{role === 'OTHER' ? c.role : CLITIC_LABEL[role]}</b>
            {c.refersTo && ` → ${c.refersTo}`}
            {!attached && c.pronoun.toLowerCase() !== written.toLowerCase() && ' (before the verb)'}
            {c.note && <div class="small muted">{c.note}</div>}
          </div>
        );
      })}
    </div>
  );
}
