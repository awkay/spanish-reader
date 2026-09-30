# Status

_Last updated: 2026-09-30_

**All planned features are written and build into a debug APK, ready for testing on a device.**
Nothing has run on real hardware yet; the UI has only been exercised under Robolectric.

## Build / verify
```
scripts/install-android-sdk.sh          # each fresh cloud container
./gradlew :core:test                     # 95 tests (2 live tests skipped without env vars)
./gradlew :app:testDebugUnitTest         # 28 Robolectric tests incl. UI smoke test and v1→v2 migration
./gradlew :app:assembleDebug             # app/build/outputs/apk/debug/app-debug.apk
```
Live LLM check (not run by default):
`LIVE_GLOSS_BASE_URL=https://api.z.ai/api/coding/paas/v4 LIVE_GLOSS_API_KEY=… LIVE_GLOSS_MODEL=glm-5.3-flash ./gradlew :core:test --tests '*LiveGlosserTest*' --rerun`

## Features (app)
- **Import**: share sheet (`ACTION_SEND` text/plain, incl. shared .txt streams), "Open with" for .txt, paste button,
  file picker. Text is cleaned (`ImportCleaner`: CRLF, invisible chars, hard-wrap joining toggle, hyphen rejoin);
  .txt decoding handles BOMs, UTF-8 and Windows-1252. A bare URL gets a warning (article extraction not built).
- **Library**: lessons with word count, new/learning counts, % known, page progress, pre-gloss progress/failure;
  rename, delete, "Pre-gloss now".
- **Reader**: `HorizontalPager` of ~250-word pages (setting), words colored by status via `LinkAnnotation`,
  tap → bottom sheet (meaning in context, lemma, POS, grammar note, other meanings, idiom/phrase, pronounce button,
  status chips 1–4 / Known / Ignore). Status colors are drawn as separate rounded boxes hugging each word (not
  text backgrounds), so lines and neighbouring words stay apart. **Page rule (changed at Tony's request)**:
  turning past a page adds its still-blue words at LEVEL_1 with their sentence and any cached AI lemma/meaning;
  nothing becomes KNOWN automatically. A "Mark all N blue words Known" button on each page does that explicitly. **Follow-along audio in the reader**: player bar (prev / play-pause / next /
  loop); the spoken sentence is tinted on the colored page, the page scrolls to it and turns automatically
  (auto-turns apply the page rule like manual ones). Swiping away while playing stops following; a
  "Follow" button jumps back. Tapping a word pauses playback. Tapping a NEW word → LEVEL_1 + vocab entry with context sentence. Turning
  forward finishes the pages passed (NEW → KNOWN); "Finish lesson" on the last page. Position is saved. Text size ±.
- **Rich word sheet**: each gloss now carries structured `verb` (infinitive, tense, mood, person, number,
  how the form is built, why this form here), `clitics` (pronoun, role normalized to `CliticRole`, what it refers
  to, note), `roots` (Latin root / compound pattern), and `phraseMeaning`. The sheet shows Expression, Verb,
  Pronouns (verb split into base + color-coded attached pronouns; one color per role everywhere), Roots and Note
  cards. Old cached glosses still load and suggest Improve. A malformed `verb`/`clitics` field is dropped, not the
  whole gloss.
- **Improve answer** button in the sheet: re-asks with the "improve" model (setting; blank = Claude if an
  Anthropic key is set, else the usual model), sending the previous answer and asking for a corrected one; the
  result replaces the cached gloss.
- **Idioms**: a per-sentence phrase scan (`PhraseFinder`, 15 sentences per call, every provider) runs in the
  pre-gloss worker for the pages ahead, so idioms made of already-known words are found too; expressions named in
  a word's gloss are also recorded. Stored in `phrases` / `phrase_scans` (Room v2, auto-migration from v1,
  tested). The reader underlines expressions (`PhraseLocator` tolerates up to 3 words in between, ignores
  accents); the selected word is now bold; tapping any word of an expression shows it in the sheet.
- **Glossing**: `GlossService` = exact cache → live lookup (cached) → any cached gloss of the form (flagged).
  Providers: Ollama Cloud (default), Ollama local, z.ai GLM Coding Plan via Responses API or chat completions, z.ai pay-as-you-go (chat completions send `thinking: disabled`), Anthropic, other
  OpenAI-compatible; per-provider URL/model/key in settings; optional Claude fallback (`FallbackGlosser`);
  "Test connection" button. **Windowed pre-gloss** via WorkManager (`PreGlossWorker`, network constraint, retry
  with backoff, progress shown in library): at import the first N pages (setting "pages ahead", default 3;
  0 = whole lesson), then the reader keeps it N pages ahead as you turn pages. Runs per lesson are appended
  (`APPEND_OR_REPLACE`) and skip cached words. Library menu "Pre-gloss whole lesson" does everything at once.
- **Listen**: shared app-scoped engine `LessonAudio` (used by the reader and the listen screen) driving a Media3
  `MediaSessionService` + ExoPlayer playlist of one audio file per sentence (background playback,
  lock-screen/notification controls). Audio is synthesized sentence-by-sentence and cached content-addressed in
  `filesDir/tts` (`SentenceAudioCache`). Engines: on-device Android TTS (default; accent es-MX/es-US/… and voice
  picker) or Google Cloud TTS (API key + voice name, MP3). Current sentence highlighted and auto-scrolled; tap a
  sentence to jump; prev/next/replay, loop sentence (repeat-one), speed 0.5–2× (persisted).
- **Vocabulary**: filter chips (Learning, 1–4, Known, Ignored, All) with counts, search (form/lemma/meaning),
  expand for context sentence, change status, forget word.
- **Settings**: glossing, pre-gloss, words per page, text size, TTS engine/voice, test voice, clear audio/gloss
  caches, **JSON backup export/restore** (restore merges: newer `lastSeen` wins, nothing deleted).

## Verified against real services
- z.ai (key from Tony, not stored in the repo): **recommended setup is provider "z.ai GLM Coding Plan (Responses
  API)"** = `https://api.z.ai/api/v1/responses` (`OpenAiResponsesGlosser`, `reasoning.effort=low`, JSON output).
  It serves the model actually requested (glm-4.6 stays glm-4.6) and is the fastest route: ~7–8 s for a 3-word
  batch. `/api/v1/chat/completions` returns 403 for this key; only `/responses` works there.
- The supplied key is a **GLM Coding Plan** key. It works only with
  `https://api.z.ai/api/coding/paas/v4` (provider preset "z.ai GLM Coding Plan"); the pay-as-you-go
  `/api/paas/v4` returns 429 "Insufficient balance", and v2/v3 paths don't exist (404).
  The plan also works on z.ai's Anthropic-compatible endpoint (provider "Anthropic Claude", base URL
  `https://api.z.ai/api/anthropic`, model `glm-4.6`; also `open.bigmodel.cn/api/anthropic`), verified live,
  but it is slower (~25 s vs ~16 s for a 3-word batch) because thinking stays on.
  The coding endpoint reroutes models for this key: glm-4.6/4.7 → glm-5.3-flash, glm-5.1 → glm-5.3 (see the
  `model` field in responses), so configure `glm-5.3-flash` or `glm-5.3` directly. z.ai's plan notice
  (docs.z.ai/devpack/notice/usage-revision) distinguishes Legacy V1 / Legacy V2 / credits plans but documents no
  separate endpoint for legacy plans.
  `glm-5.3-flash` and `glm-4.6` both produce excellent glosses (clitics in `dáselo`, idiom `echar de menos`);
  ~2 s/word with thinking disabled, ~7–9 s for a 3-word batch.
- Ollama Cloud, Anthropic and Google Cloud TTS have not been tried with real keys.

## Releases
- GitHub Actions (`.github/workflows/release.yml`) builds and tests on every push to `main` and publishes the APK
  as Release `build-<run number>`; versionCode = run number. Signed with the committed `app/debug.keystore`
  (same key as the APKs built in the first sessions), so releases install as updates.
- `ReaderScreenshotTest` renders the reader to `$SCREENSHOT_DIR/reader.png` (Robolectric native graphics) when
  that env var is set; skipped otherwise.

## Known issues / unverified
- Never installed on a device. Listen mode (MediaController/ExoPlayer/TTS file synthesis) has no automated test.
- Audio synthesis runs in the app-scoped `LessonAudio`, so it survives leaving screens, but not the app process
  being killed while in the background (the service keeps playing what is queued). Moving it into
  `PlaybackService` would make it fully robust.
- On-device TTS WAV cache is large (~100 MB for a 5,000-word chapter); clear it in Settings.
- On-device TTS output format is assumed to be WAV (true for Google's engine).
- z.ai returns HTTP 429 for "insufficient balance" (code 1113); it is retried like a rate limit before failing.
- Every newline still ends a sentence when "Join wrapped lines" is off.
- No test proves the glosser concurrency limit of 2 holds.
- Room schema v2; schemas in `app/schemas/` are also debug assets for the Robolectric migration test. Every
  future change needs a migration + test.
- Richer answers cost more output tokens: a 6-word batch took ~19–26 s on z.ai (Responses API). Model quality
  varies: glm-4.6 once claimed "observándome" needs no accent (wrong); that is what Improve is for.
- Idiom underlines appear only for pages the pre-gloss worker has scanned (pre-gloss must be on).
- Robolectric (SDK 36) on JDK 21 needs `--add-opens java.base/jdk.internal.access` (set in `app/build.gradle.kts`).
- Maven Central intermittently returns 429 through the cloud proxy; just rerun Gradle.

## Next
1. Install on a phone (`adb install app/build/outputs/apk/debug/app-debug.apk`) and test end to end,
   especially follow-along audio (never run on a device).
2. Move synthesis into `PlaybackService`; save the listening position.
3. Web article extraction for shared URLs; EPUB import.
4. Offline dictionary fallback (kaikki.org Wiktionary extract).
5. Azure Neural TTS option; LingQ-style daily stats.
