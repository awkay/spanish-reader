# Status

_Last updated: 2026-09-30_

## Done
- **`:core` module** (pure Kotlin/JVM, package `net.awkay.spanishreader.core`). `./gradlew :core:test` → 51 tests, all passing.
  - `text/`: `Tokenizer` (lossless; WORD/PUNCT/WHITESPACE; normalized = NFC + Spanish-locale lowercase, accents kept),
    `SentenceSegmenter`, `Paginator` (~250 words/page, breaks only at sentence boundaries).
  - `vocab/`: `WordStatus` (NEW/LEVEL_1/RECOGNIZED/FAMILIAR/LEARNED/KNOWN/IGNORED), `VocabEntry`,
    `VocabularyRules` (tap → LEVEL_1 auto-add; page finished → remaining NEW become KNOWN), `LessonStats`.
  - `gloss/`: `Glosser` interface; `OpenAiCompatibleGlosser` (Ollama local/cloud, z.ai GLM) and `AnthropicGlosser`
    (Haiku 4.5, raw HTTP) on a shared `LlmGlosser` base with batching (20/request), concurrency limit (2),
    retry/backoff on 429/5xx honoring Retry-After, one retry on malformed JSON. `GlossCache` + `InMemoryGlossCache`,
    `CachingGlosser` (only sends cache misses).
- **Environment**: `dl.google.com` and `maven.google.com` both reachable from cloud sessions now.
  `scripts/install-android-sdk.sh` installs cmdline-tools + `platforms;android-37.0` + build-tools 36 into
  `~/android-sdk` and writes `local.properties` (gitignored). Must be rerun in each fresh cloud container.
- **Build**: Gradle wrapper upgraded 8.14.3 → **9.8.0** (AGP 9 requires Gradle 9; also clears the Kotlin 2.5 issue).
  AGP 9.4.1 with built-in Kotlin (no `kotlin-android` plugin), KSP 2.3.12, Kotlin 2.4.20.
- **`:app` module skeleton** (`net.awkay.spanishreader`): compileSdk 37 (required by Compose BOM 2026.09.00),
  targetSdk 36, minSdk 26. Compose/Material3, Room 2.8.5, Media3 1.11.1, WorkManager 2.12 wired in.
  `./gradlew :app:assembleDebug` builds a debug APK. `SpanishReaderApp` holds the DB and repositories;
  `MainActivity` shows a placeholder `LibraryScreen` listing lessons.
- **Room data layer** (`app/.../data/`), schema v1 exported to `app/schemas/`:
  - `lessons` (id, title, text, created_at, current_page), `vocab` (PK = normalized form; status stored as
    `WordStatus.code` via TypeConverter; indexed by status and lemma), `gloss_cache` (PK form_key + sentence_hash,
    gloss stored as JSON).
  - `VocabRepository`: `tap`, `setStatus`, `annotate` (lemma/translation from gloss, status untouched),
    `finishPage` (page-finished rule, transactional, chunked under SQLite's bind-variable limit), `statuses`, flows.
  - `RoomGlossCache` implements core `GlossCache`; corrupt/old-schema rows are treated as misses.
  - `./gradlew :app:testDebugUnitTest` → 13 Robolectric tests, all passing.

## Known issues / unverified
- Glossers are only tested against MockWebServer — never against a real Ollama, z.ai, or Anthropic endpoint.
- Every newline ends a sentence. Hard-wrapped text (e.g. pasted from PDFs/emails) will split mid-sentence;
  consider joining single newlines at import.
- No test proves the concurrency limit of 2 holds.
- APK never installed on a device/emulator; UI is untested.
- Robolectric (SDK 36) on JDK 21 needs `--add-opens java.base/jdk.internal.access` (set in `app/build.gradle.kts`).
- Maven Central intermittently returns 429 through the cloud proxy; just rerun the Gradle command.
- Repositories still use `maven("https://maven.google.com")` rather than `google()`; either works now.

## Next (in order)
1. **Import**: share-sheet intent (`ACTION_SEND` text/plain) → create lesson (title = first line/sentence),
   paste screen, .txt via `ACTION_OPEN_DOCUMENT`. Consider joining hard-wrapped single newlines here.
2. **Reader screen**: tokenize + paginate lesson text, status-colored words (statuses via `VocabRepository.statuses`),
   tap → bottom sheet (gloss via `CachingGlosser` + `RoomGlossCache`, status buttons), page turn → `finishPage`,
   persist `current_page`.
3. **Settings**: glosser provider, base URL, API key, model name (default Ollama Cloud, Kimi-class model);
   DataStore Preferences. Build the `Glosser` from settings.
4. **Pre-gloss on import** as a background job (WorkManager) using `CachingGlosser`.
5. **Listen mode**: on-device TTS (es-MX/es-US) first with sentence highlighting via Media3; cloud TTS + MP3 cache after.
6. Vocabulary screen, lesson stats in library, JSON backup/export.
