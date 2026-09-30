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
- Build: Gradle 8.14.3 wrapper, Kotlin 2.4.20, JVM bytecode target 17. Repositories use
  `maven("https://maven.google.com")` instead of `google()` (dl.google.com was blocked in the first session).

## Known issues / unverified
- Glossers are only tested against MockWebServer — never against a real Ollama, z.ai, or Anthropic endpoint.
- Every newline ends a sentence. Hard-wrapped text (e.g. pasted from PDFs/emails) will split mid-sentence;
  consider joining single newlines at import.
- No test proves the concurrency limit of 2 holds.
- Kotlin 2.5 will require Gradle ≥ 8.14.4; wrapper not yet upgraded.

## Next (in order)
1. **Environment**: confirm the Android SDK can be installed (needs `dl.google.com` + `maven.google.com`).
   If `dl.google.com` now works, `google()` can replace the explicit maven.google.com repo.
2. **`:app` module skeleton**: Compose, Room, Media3; depends on `:core`. Must compile with `./gradlew :app:assembleDebug`.
3. **Room data layer**: lessons, vocabulary entries (keyed by form), gloss cache (Room-backed `GlossCache`).
4. **Import**: share-sheet intent (`ACTION_SEND` text/plain), paste, .txt.
5. **Reader screen**: paged text, status-colored words, tap → bottom sheet (gloss + status buttons), page-finished rule.
6. **Settings**: glosser provider, base URL, API key, model name (default Ollama Cloud, Kimi-class model).
7. **Pre-gloss on import** as a background job (WorkManager) using `CachingGlosser`.
8. **Listen mode**: on-device TTS (es-MX/es-US) first with sentence highlighting via Media3; cloud TTS + MP3 cache after.
9. Vocabulary screen, lesson stats in library, JSON backup/export.
