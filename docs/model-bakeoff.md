# Model bake-off — 2026-11-22

A one-off experiment: which AI model should do each job in the app? Every model ran the app's real prompts and
parsers through OpenRouter on five jobs:

- 14 tricky word glosses in context
- 5 sentence translations with idioms
- 10 lightning-drill cards for *estar* (write, then review)
- 20 new say-it sentences, 10 for each deck (write, then review)
- 2 conversation replies and 4 corrections

The harness was a temporary test and is no longer in the repo. The raw answers were kept only in the session
scratchpad. This directory keeps the results:

- `bakeoff/summary.json`: numbers per model
- `bakeoff/verdict-*.json`: each model's full list of mistakes
- `bakeoff/judging-rubric.md`: the grading guide

**How quality was judged.** Each model's answers went to a fresh grader with no context, written to read as a careful
Latin American Spanish teacher. The answers were anonymised (A–H), and every grader got the same rubric. Each grader
read every item. I then checked their verdicts against my own reading of all the answers. I dropped complaints
that don't apply to the app: the drill screen shows the tense and subject ("Present perfect · usted") above each card,
so "the English doesn't force usted/preterite" is not a fault for drill cards. The automatic checks (lemma = ir,
correction contains "a la playa", …) are a coarse first pass. Almost every model passes them, so they don't separate the
models.

**Caveats.**

- This is one run on a small set. Treat ±0.5 in the score as noise.
- Latency is through OpenRouter's providers. GLM straight from z.ai may be faster or slower.
- Every model ran with default reasoning settings. GLM 5.3 spends about 14k hidden reasoning tokens to gloss 14 words,
  and that is where its slowness and cost come from. Turning reasoning down might change its ranking. That wasn't tested.
- *qwen/qwen3.8-flash* had not finished after about 8 minutes (the others took 20 s – 7 min), so I stopped it and
  counted it as too slow to use.

## Ranking

The page cost assumes about 150 words glossed per 250-word page, since known words and repeats are skipped. It is the
cost of the 14-word gloss call ÷ 14 × 150. "Gloss 14 words" is how long that one call took. The app sends 20 words per
call, 2 calls at a time.

| # | Model | Quality /10 | Why | Auto checks | Median call | Total | Gloss 14 words | Run cost | Est. cost / page |
|---|---|---|---|---|---|---|---|---|---|
| 1 | z-ai/glm-5.3 (current) | **9** | Best Spanish overall, with correct lemmas, clitics and regional notes; very slow | 28/28 | 7.2 s | 412 s | 111 s | $0.097 | $0.21 |
| 2 | google/gemini-3.8-flash | **8.5** | Nearly as accurate, Mexico-aware, full decks; about 5× faster than GLM at glossing | 28/28 | 10.0 s | 128 s | 20 s | $0.080 | $0.12 |
| 3 | z-ai/glm-5.3-flash | **8.5** | Very close to GLM 5.3 at a sixth of the cost; one gender slip in chat; drill writing slow | 28/28 | 6.4 s | 212 s | 41 s | $0.013 | $0.06 |
| 4 | deepseek/deepseek-v4.1-flash | **8** | Clean, but says *echar de menos* is "the standard LatAm way"; one gloss call stalled and was billed about $0.10 anyway | 28/28 | 12.5 s | 415 s | 271 s | $0.11 billed* | $0.01 if nothing stalls |
| 5 | google/gemini-3.5-flash-lite | **7** | By far the fastest; terse notes, wrong *se me olvidó* clitics, every question card missing the opening ¿ | 27/28 | 1.4 s | 21 s | 6 s | $0.017 | $0.06 |
| 6 | anthropic/claude-haiku-5.5 | **5** | Good drill and chat, but the say-it job produced nothing and several grammar notes are false | 26/27 | 3.9 s | 77 s | 30 s | $0.010 | $0.04 |
| 7 | mistralai/mistral-small-2603 | **5** | Fast, cheap, excellent chat; drill and say-it cards are broken (wrong ser/estar, blanks that aren't the verb) | 27/28 | 1.9 s | 34 s | 13 s | $0.005 | $0.02 |
| 8 | openai/gpt-5.4-nano | **4.5** | vino → lemma *venir*; "meanings" are whole-sentence translations; only 3 question cards | 27/28 | 2.5 s | 40 s | 16 s | $0.010 | $0.04 |
| – | qwen/qwen3.8-flash | – | Didn't finish in about 8 min | – | – | – | – | – | – |

\* **Checked against the OpenRouter bill.** The whole session cost $0.355, which matches the bill, and every other
model's recorded cost matches its line on the bill within rounding. DeepSeek's doesn't: the harness recorded $0.008, but
the bill shows $0.11. The difference is the stalled gloss call. The harness timed out after 180 s and retried, so it never
saw a cost. The provider still billed the abandoned call, about $0.10, which is roughly 500k output tokens at list price.
It most likely ran to its token limit. The app would pay for the same thing, because a client timeout doesn't stop
billing. Qwen's partial run cost $0.02.

Chat is not what separates them. Every model fixed *al playa → a la playa*, *we ate → comimos* and *el comida → la
comida … buena*. Every model also left both correct sentences alone and kept "Yo", so none over-corrected. Gemini 3.5
Flash Lite and GPT-5.4 nano kept the present "es" after "Ayer comí…". That is acceptable, but weaker.

## Mistakes, by model

**z-ai/glm-5.3**
- *encaja*: otherMeanings invents "to cash (a check)". That would be *cobrar*.
- Say-it: "The doctor is seeing patients" with the answer *está recibiendo*. Nothing in the English leads to *recibir*.
- Small notes: it says the accent "shifts stress" in *dáselo*, when the word has just become esdrújula. It gives "describing
  a state, so it is ser" for *fue*. It gives *quedar con* = arrange to meet, which is Spain usage.

**google/gemini-3.8-flash**
- Missed *hace … que* as a phrase, though the translation was right.
- *se me olvidó*: says *se* "refers to the key". The role (accidental se) is right.
- Small notes: *quedar* = "meet up" is the Spain sense. "Which are your favorite tacos?" is unnatural English. The
  chat reply has a comma splice ("¡Qué bien, los sándwiches…!").

**z-ai/glm-5.3-flash**
- Chat reply: "La playa con la familia siempre es **divertido**". That's a gender agreement slip, the same kind of error the
  corrections job exists to fix.
- *encaja en* is flagged as an idiom. Two drill cards are both "está".
- Drill writing took 97 s, and say-it took 50 s.

**deepseek/deepseek-v4.1-flash**
- *echar de menos*: "is the standard Latin American way to say 'to miss'". That's backwards: *extrañar* is the everyday
  LatAm verb, and the note never mentions it.
- *me da pena* hedges with "or: it makes me sad" and doesn't say that in Mexico it means embarrassed.
- Chat: "Suena como un día…" is a calque (natural Spanish: *suena a*). "El pozole … sobre todo con un buen caldo" is an
  empty remark.
- The gloss job took 271 s: the first call stalled and had to be retried. The stalled call was still billed, about $0.10, so
  DeepSeek ended up costing more than any other model in the run.

**google/gemini-3.5-flash-lite**
- *se me olvidó*: only *me*, mislabelled "accidental se". The phrase is given as *olvidarse de*, which is not this
  construction.
- Every question-word card lacks the opening ¿ ("___ paciencia tiene usted con él?").
- It teaches "use cuáles before plural nouns" (*¿Cuáles restaurantes…?*), when the standard is *qué* + noun.
- Many grammar notes are empty. There are only 9 drill cards and 9 verb cards.

**anthropic/claude-haiku-5.5**
- The say-it job failed outright: "No usable sentences were written". This may be a format the
  app's parser rejects rather than bad Spanish. I didn't check further.
- False notes:
  - *como*: "Written with a capital letter here, so it does not mean 'as'".
  - *dáselo*: "In voseo regions the command would be 'dá' or 'dásela'".
  - *quedé*: "spelling change in the first person".
- *echar de menos*: it calls *extrañar* only "also widely used".

**mistralai/mistral-small-2603**
- *dáselo*: "In Latin America, 'tú' commands use '-s' ending". That's false: the *s* is the clitic *se*. It also
  misses *me* (IO) in *se me olvidó*.
- *fue*: "Ser is used for permanent or temporary states".
- Drill cards:
  - *Nosotros vamos a estar tarde para la película*: should be *llegar tarde*.
  - *Tú estabas muy tímido de niño*: a trait, so it needs *ser*.
  - *Espero que yo esté listo*: same subject, so *Espero estar listo*.
- Say-it cards:
  - *¿Cuáles de estos libros es tuyo?*: should be *Cuál*.
  - "What time does the pharmacy close?" with the answer *Qué*.
  - Verb cards whose blank is *ayer* / *anoche*: "Yo pagué la cuenta … ___ pasado" = *anoche*.
  - *van a ser muy felices con el regalo*: should be *estar*.
- Chat itself was excellent.

**openai/gpt-5.4-nano**
- *vino* (wine): lemma **venir**.
- *meaningInContext* is a translation of the whole sentence ("Give it to him tomorrow.") rather than the word.
- *pena* = "anxiety/sadness". *echar* is tagged "future".
- Only 3 question-word cards, among them "¿De dónde ___ tus llaves?" with the answer *De dónde*, where the answer is
  already in the sentence.
- Verb cards are mislabelled: verb *creer* with the answer *voy a comprar*, and verb *ser* with *voy a estar*.
- Drill: *Espero que yo esté mejor*, *Tú estabas tranquilo … cuando eras niño*, plus text glitches ("Mañana, Nosotros",
  "today, formal").

## Recommendations

- **(a) Word glossing: google/gemini-3.8-flash.** It is within half a point of GLM 5.3. It glossed 14 words in 20 s
  where GLM 5.3 took 111 s, and costs about $0.12 per page against $0.21.
  - If you'd rather stay on the z.ai plan, **glm-5.3-flash** is nearly as accurate, at 41 s and about $0.06 per page
    through OpenRouter. On your plan it may cost nothing extra.
  - Avoid DeepSeek. It looks cheapest at about $0.01 per page, but one stalled call was billed about $0.10, more than
    ten times its whole recorded run. It also has a wrong regional note.
  - Avoid gpt-5.4-nano and mistral-small for glossing.
- **(b) Writing and reviewing drill and say-it cards: z-ai/glm-5.3** if you can live with the wait. This runs in the
  background, and its cards were the cleanest. But writing one say-it batch took 4½ minutes.
  **google/gemini-3.8-flash** is the practical choice: near-equal cards in about 20 s for the drill and 50 s for
  say-it.
  - Don't use Mistral, nano or Haiku here. Haiku's say-it output didn't parse at all.
- **(c) Conversation: google/gemini-3.5-flash-lite.** It took 4.8 s for the whole chat job (two replies and four
  corrections), with natural, short, Mexican-flavoured replies and correct, minimal corrections.
  - **mistral-small-2603** is equally good at chat and cheaper. It's fine for this one job even though its cards are bad.
  - Gemini 3.8 Flash took 27 s for the same job, which is too slow for a spoken back-and-forth.
