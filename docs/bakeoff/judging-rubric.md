# Grading one AI model's answers for a Spanish-learning app

You are a careful teacher of **Latin American Spanish** (Mexican usage is the reference) grading one anonymous AI
model's output. The learner is an English speaker, around A2–B1. Read every item yourself. Don't trust that
anything is right because it looks well formatted.

The answer file has five jobs:

1. **glosses**: 14 words, each tapped inside a sentence. The app shows `lemma`, `meaningInContext` (should be a short
   meaning of *this word in this sentence*, not a translation of the whole sentence), `grammarNote`, `verb` details,
   `clitics` (pronoun roles), `isIdiomOrPhrase`/`phrase`/`phraseMeaning`, `roots`, `otherMeanings`.
   Look for: wrong meaning in context, wrong lemma (fui=ir vs fue=ser, vino noun vs venir, como=comer), wrong tense,
   mood or person, wrong clitic roles (se me olvidó: se = accidental/impersonal se, me = indirect object; dáselo:
   se = IO replacing le, lo = DO), grammar notes that are false, misleading, filler or wrong about regional usage
   (e.g. in Latin America *extrañar* is the everyday verb for "to miss"; *me da pena* in Mexico = embarrassed),
   and idioms that are missed or invented.
2. **sentences**: 5 sentences, each with an English translation plus the idioms/fixed phrases it contains, each with
   a meaning. Look for wrong translations, missed idioms (sin embargo, darse cuenta, a lo mejor, hace…que, echar de
   menos), invented idioms in "El perro come pan", and wrong spans.
3. **drill**: lightning-drill cards for the verb *estar*. Each card has `english`, a Spanish sentence with `___`,
   and the `answer` that fills it, for a given tense/subject. The learner types the form after seeing the English.
   Look for: an answer that doesn't fit the blank, English that doesn't force that exact tense/person (or that
   would really need *ser*), unnatural or ungrammatical Spanish (e.g. "estar tarde", "espero que yo esté" where
   the infinitive is required), Spain-only usage, a missing card for a tense, and leftover glitches in the text.
   About 10 cards are expected.
4. **phrases**: "say it" cards. Deck 1 is question words (cuánto/a, de dónde, cuál/es, qué, cuándo, por qué, de quién, a
   qué hora); deck 2 is everyday verbs (`verb`/`tense` given). The learner sees the English, says the blank aloud,
   then sees the answer. Look for: an answer that isn't the word/verb the card is meant to drill, an answer that
   doesn't fit the blank, English that doesn't force that answer, ungrammatical or unnatural Spanish (e.g. "¿Cuáles
   de estos libros es…?"), missing opening ¿, Spain-only words (coger, vosotros, ordenador, zumo, etc.) and too few
   cards (about 10 per deck are expected). An `error` here means the job failed and produced nothing.
5. **chat**: a conversation partner. `reply1` and `reply2` are replies (Spanish + English per sentence) to a learner who
   mixed English into their Spanish. They should be short, natural Latin American Spanish, keep the conversation
   going and be at the learner's level. `corrections` are 4 learner sentences, minimally corrected:
   (1) "Ayer fui al playa con mi familia y we ate sándwiches." (spoken) needs a la playa + comimos;
   (2) "Ayer comí en un restaurante y el comida es muy bueno." needs la comida, buena (and arguably "estuvo/era",
   since it's past);
   (3) "Me gusta cocinar los fines de semana." is correct and should be left alone (ok=true, no changes);
   (4) "Yo tengo veinte años y vivo en Texas con mi esposa." is correct. Dropping "Yo" is a style polish, which is
   over-correction. Look for missed errors, over-correction, wrong or false explanations in `why`, and a bad
   `english`.

## What to produce

Write your verdict as JSON to the output path you were given, then reply with the same JSON:

```json
{
  "letter": "X",
  "scores": {"glosses": 0-10, "sentences": 0-10, "drill": 0-10, "phrases": 0-10, "chat": 0-10, "overall": 0-10},
  "oneLine": "one sentence a busy reader can use to compare this model with others",
  "mistakes": [
    {"job": "glosses|sentences|drill|phrases|chat", "item": "the word or sentence",
     "what": "what is wrong, quoting the model", "severity": "major|minor"}
  ],
  "strengths": ["short notes"]
}
```

"Major" means it would teach the learner something wrong or break the exercise; "minor" means awkward, terse or a
nit. Score 10 = what an expert teacher would write. Be strict and specific. List every mistake you find, not a
sample. Grade only this file. Don't look for, or at, any other files in the directory.
