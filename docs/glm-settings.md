# GLM 5.3 Flash settings on z.ai's Responses API — 2026-11-22

A one-off experiment on the setup Tony uses: `glm-5.3-flash` through `OpenAiResponsesGlosser`
(`https://api.z.ai/api/v1/responses`). The questions were whether better settings exist than the app's current ones,
and whether the app should cap answer length. It ran the same five jobs and the same app prompts and parsers as the
[model bake-off](model-bakeoff.md):

- 14 tricky glosses
- 5 sentence translations
- estar drill cards (write and review)
- say-it cards for both decks (write and review)
- 2 chat replies and 4 corrections

The harness was a temporary, uncommitted test that rewrote effort, cap and temperature on each request. It went
through the app's own client, timeouts and retry policy. Data: `bakeoff/glm-settings/`.

**Decision (Tony): stay on 5.3 Flash at `low` effort.** Full GLM 5.3 was dropped from the plan ("flash is good
enough"), and the remaining high/default runs were stopped once the picture was clear.

## What the app sends today

- **Effort:** `reasoning.effort: "low"`. This is hard-coded in `OpenAiResponsesGlosser`, and the server sends the
  same.
- **Temperature:** 0.2, or 0.7 for the chat partner.
- **Format:** `text.format: json_object`.
- **Length cap:** none, until this change (see below).
- **Timeouts:** OkHttp read 120 s and call 180 s.
- **Retries:** `RetryPolicy` retries 429, 5xx and **any I/O error, timeouts included**, up to 3 times. Without a
  cap, a looping reply could therefore be billed up to four times.

## What z.ai actually does with these settings

z.ai doesn't document its Responses API; docs.z.ai only lists the endpoint. So I probed it directly:

- **Effort.**
  - `none`, `minimal`, `low` and `medium` are accepted. The first three give 0 to a few dozen reasoning tokens;
    `medium` gives about 3k per run.
  - `high`, `xhigh`, an omitted field and even an unknown value (`bogus`) all switch on full thinking.
  - Omitting `reasoning` is *not* "no reasoning". It is the most thinking: 17k reasoning tokens per run.
- **Length cap.** `max_output_tokens` is honoured, and **reasoning tokens count toward it**.
  - When the cap is hit, the reply comes back `status: "incomplete"` with
    `incomplete_details.reason: "max_output_tokens"`, holding partial text or no message at all.
  - With `high` effort and a cap of 300, every token went to reasoning and no text came back.
- **Temperature: ignored.**
  - At `temperature: 0`, five identical requests gave five different answers, at both low and high effort.
  - `temperature: 5` (out of range) was accepted without an error.
  - The chat-completions endpoint behaves the same way.
  - So the app's 0.2 and 0.7 make no difference with GLM, and the "temp 1.0" runs below are effectively more
    `low` runs.

## Results

Each row is one setting, run 2–4 times. Quality was scored by a fresh grader per setting, with the answers
anonymised, using `bakeoff/judging-rubric.md`. I then checked each verdict against my own reading. The page cost is
the 14-word gloss call ÷ 14 × 150 words, at z.ai's pay-as-you-go prices (Flash: $0.15 per million input tokens,
$0.03 cached, $0.50 output). On the GLM Coding Plan these calls use plan quota instead of money.

| Setting | Quality /10 | Why | Median call | Total per run | Gloss 14 words | Output tokens per run (reasoning) | Largest reply | Cost per run | Est. per page |
|---|---|---|---|---|---|---|---|---|---|
| **low (app today)** | **7–8** | Two graders, 4 runs: 7 and 8. Solid lemmas, clitics, idioms and corrections; a few false notes per run | 3.7 s | 86, 92 s | 37, 35 s | 6.1k (0.6k) | 2.7k | $0.0046 | $0.016 |
| low + temperature 1.0 | (8) | Same as low: temperature is ignored | 3.3 s | 71, 79 s | 22, 32 s | 5.8k (0.4k) | 2.6k | $0.0044 | $0.015 |
| none | 6 (≈7 without the bug) | Same output as low; one drill job failed on an app bug, now fixed | 4.9 s | 81–126 s | 30–44 s | 6.1k (0.3k) | 2.8k | $0.0046 | $0.016 |
| medium | 7 | No better than low; say-it writing twice as slow | 5.0 s | 130, 136 s | 49, 45 s | 9.2k (2.8k) | 3.5k | $0.0062 | $0.020 |
| high | 6 | Slower and no better. One gloss reply dropped words and mislabelled another (app bug, now fixed) | 9.9 s | 276, 302 s | 100, 96 s | 20.8k (14.3k) | 6.6k | $0.012 | $0.039 |
| effort omitted (z.ai default) | not graded | Maximum thinking; slowest of all | 11.1 s | 347 s | 112 s | 23.4k (17.3k) | 8.2k | $0.013 | $0.046 |
| low, cap 4k / 8k | as low | Cap never reached (largest reply 2.7k) | 3.7 s | 73–93 s | 21–38 s | 6.1k | 2.7k | $0.0046 | $0.016 |
| low, cap 2k, **old code** | – | Cap too small: 12 of 14 glosses lost (see below) | 4.7 s | 107 s | 55, 53 s | 7.6k | 2.0k (cut off) | $0.0056 | – |

**Costs.** The figures use the token counts z.ai returned with each response, priced at its published rates; nothing
was estimated. I couldn't check them against the account's billing page: the usage endpoints weren't reachable from
this session. The whole experiment cost about $0.12 at pay-as-you-go prices (19 full runs plus a few probes).

**Speed.** Low and none are the same speed. Medium adds about 45%, high about 3×, and the default about 4×.
Variance between repeats was modest. One `none` run's chat job took 43 s instead of 11 s: a single slow call, not a
loop. No run looped or stalled.

## Mistakes by setting

Every setting makes the same three mistakes, so they come from the prompts, not the effort level:

- **"Espero que yo esté…"** on the present-subjunctive *yo* drill card. With the same subject, Spanish needs the
  infinitive: *Espero estar…*. The drill's situation list asks for that combination.
- ***Cuáles* + noun** ("¿Cuáles sabores…?") instead of *Qué*. This is colloquial and fine in Mexico, but it isn't
  the form to teach.
- **The tense in correction 2 is left alone.** "…la comida es muy buena" after *Ayer comí*; *estuvo* would be
  better.

**low** (both graders):
- *echar de menos* is called "the standard way to say 'to miss' in Latin America" in both runs graded as P. That's
  backwards: *extrañar* is the everyday Latin American verb. One temperature-1.0 run got this right.
- *observándome*: "no accent needed".
- *encaja*: an invented "to clash".
- *fue*: estar "would sound odd" with *increíble*. In fact *estuvo increíble* is very common in Mexico.
- Broken say-it cards:
  - "¿Cuáles de estos platillos es el más picante?" (should be *Cuál*).
  - "El camión sigue en llegar tarde" (should be *sigue llegando*).
  - A stray "(ustedes)" on *La reunión va a ser corta*.
  - "Creo que va a haber lluvia" on a card meant to drill *creer*.
- Chat replies are natural and Mexican, and the corrections are minimal and right in every run.

**none:** the same kinds of slips as low:
- *echar de menos* again.
- "¿Cuáles de estos cuartos es…?"
- "¿De quién carro usaste?"
- One drill job ended "Only 0 usable cards for estar": the review reply matched none of the cards. That was an
  app bug (below), not bad Spanish. The other three runs were fine.

**medium:**
- *echar de menos* in one run.
- An invented regional claim about the progressive.
- *echar* called "irregular".
- An invented *encajar los puños*.
- A *creer* card whose blank drills *ir*.
- "¿Cuáles de estos vestidos es…?"

**high:**
- Run 1's gloss reply left out three words. The app's retry asked again, which added 16 s on top of 84 s.
- In the same run, one item carried *encaja*'s id on *quedé*'s gloss, so *encaja* showed "I stayed". This is the
  parser bug fixed below.
- Other gloss mistakes:
  - *vino* (noun) "has final stress".
  - *echar de* = "to blame".
  - *fue* can be estar "in some regions".
- Card and chat mistakes:
  - A *creer* card answered *va a llover*.
  - "Me ___ , ¿verdad?" with a false note.
  - English "warm" against Spanish *se enfríe*.
  - "¿De qué te gusta más, rojo, verde o blanco?" in chat.
- More thinking did not mean fewer mistakes.

## App bugs found and fixed (with tests, Kotlin and web)

1. **A gloss was saved under the wrong word.** The parser trusted an item's `id` even when its `form` named a
   different word. An id match now needs the form to agree, ignoring case and accents; otherwise the item goes to the
   word it names, or is left out so the retry asks again. Across all 228 glosses in this experiment, the only form
   differences besides this one were *Como*/*como* case differences.
2. **A review that matched no card wiped out the whole batch.**
   - Drill and say-it reviews whose items had no usable `"n"` returned an empty list. That read as "the reviewer
     rejected every card", so the job failed with "Only 0 usable cards" / "No usable sentences were written". The
     second message is the one the bake-off recorded for Claude Haiku.
   - Such a review now counts as unparseable, which already meant "keep the cards as written".
   - A reviewed card whose Spanish lost its blank is kept as written; only `"ok": false` rejects a card.
3. **Anthropic practice calls were capped at 1024 tokens.** `max_tokens` was 600 × items with a floor of 1024, and
   drill, say-it and chat calls count as one item. A batch of ten cards could be cut off. They now get the answer
   limit.

## Should the app cap answer length? **Yes: 16,000 tokens (now the default)**

The evidence:

- **Real answers are far below 16k.** The largest reply in any job:
  - low effort: 2.7k (the 14-word gloss call, about 190 tokens per word, so a full 20-word batch is about 4k)
  - medium: 3.5k
  - high: 6.6k
  - maximum thinking (effort omitted): 8.2k
  - That leaves 4× headroom at the setting Tony uses, and still 2× at maximum thinking.
- **Without a cap a loop is expensive.**
  - The bake-off's DeepSeek gloss request looped for 70k tokens until the 240 s timeout and was billed $0.085,
    about 30 normal calls.
  - In the app it could be worse: a call timeout is an I/O error, so `RetryPolicy` sends the request again up to
    3 more times.
  - A 16k cap stops a loop after at most 16k tokens. That's about $0.008 on Flash and roughly 30 s, well before the
    timeout.
- **A cap that's too small is worse than none.**
  - The 2k runs used the old code. The 14-word gloss reply was cut off. The parser kept the 2 glosses that had
    finished.
  - Then the retry, sent with the same cap, spent 1,999 of its 2,000 tokens reasoning. 12 of 14 words failed in
    both runs.
  - So the cap must leave room for reasoning, and a cut-off reply must not simply be resent whole.

**Implemented**:
- **Commits.** `cb2162a` (core, server and web) and `003d545` (Android setting).
- **Core option.** `GlossOptions.maxOutputTokens`, default 16,000. Each profile can override it: Settings → AI
  account → "Answer length limit, tokens" (blank = 16,000, 0 = no limit).
- **Sent to every provider, each under its own name:**
  - `max_output_tokens` on the Responses API
  - `max_tokens` on chat-completions (`max_completion_tokens` on api.openai.com)
  - `max_tokens` on Anthropic
- **A cut-off reply is detected.** `status: incomplete`, `finish_reason: length` or `stop_reason: max_tokens`
  becomes a `TruncatedResponseException`.
  - A cut-off gloss batch is asked for in halves instead of being resent whole.
  - A single word is retried once.
  - Practice and chat calls retry once and then show "The AI's answer was cut off at the 16000-token answer-length
    limit".
- **Server.** `SR_AI_MAX_OUTPUT_TOKENS` (default 16000, 0 = none). A cut-off reply returns HTTP 502 with
  `{"truncated": true}`, and the web client splits the batch the same way Android does.

## Recommendations

| Job | Model | Effort | Cap | Temperature |
|---|---|---|---|---|
| (a) Glossing (meanings, sentences) | glm-5.3-flash | **low** (as now) | 16k (default) | Leave as is: z.ai ignores it |
| (b) Drill and say-it writing and review | glm-5.3-flash | **low** | 16k | Leave as is |
| (c) Conversation (partner and corrections) | glm-5.3-flash | **low** | 16k | Leave as is (0.7 is ignored too) |

- **`low` is already the right setting**, and no app change is needed for effort.
  - `none` behaves the same on z.ai, so there's nothing to gain from it.
  - `medium` and `high` cost 1.5–3× the time and tokens with no quality gain. High actually made two mistakes low
    never made: dropped words and a mislabelled gloss.
  - Never omit the field: that means maximum thinking.
- **The remaining mistakes are content, not settings.**
  - Fix them in the prompts:
    - the same-subject subjunctive drill situation
    - the *echar de menos* / *extrañar* regional note
    - the *cuál* vs *qué* guidance before nouns
  - Or route "Improve answer" and card reviews to a stronger profile.
