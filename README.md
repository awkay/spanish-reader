# spanish-reader

A personal LingQ-style Android app for reading and listening to Latin American Spanish.
Words are colored by learning status (blue = new, yellows = learning, none = known/ignored);
tapping a word asks an LLM for its meaning in that specific sentence.

## Modules

- `core` — pure Kotlin/JVM, no Android dependencies (package `net.awkay.spanishreader.core`):
  - `text` — tokenizer (exact round-trip, Spanish-aware normalization), sentence segmentation, pagination.
  - `vocab` — `WordStatus` ladder, `VocabEntry`, pure `VocabularyRules` (tap auto-add, page-finished → known), `LessonStats`.
  - `gloss` — `Glosser` interface with a shared prompt, robust JSON extraction, batching, concurrency limiting and
    retry; `OpenAiCompatibleGlosser` (Ollama local/cloud, z.ai GLM, anything with `/chat/completions`),
    `AnthropicGlosser` (raw HTTP to the Messages API), `GlossCache` + `InMemoryGlossCache`, `CachingGlosser`.
- `app` — Android (Compose, Room, Media3). Pending: the Android SDK could not be downloaded in the environment
  this was bootstrapped in, so the module has not been added to `settings.gradle.kts` yet.

## Running tests

```
./gradlew :core:test
```

Requires JDK 17+ (built with JDK 21, emitting Java 17 bytecode).
