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
- `app` — Android (Compose, Room, Media3, WorkManager; AGP 9 with built-in Kotlin). So far: Room database
  (`lessons`, `vocab` keyed by normalized form, `gloss_cache`), `VocabRepository` applying core `VocabularyRules`,
  `RoomGlossCache`, and a placeholder library screen. Room schemas are exported to `app/schemas/`.

## Building and testing

```
scripts/install-android-sdk.sh     # once per machine; writes local.properties
./gradlew :core:test               # pure JVM tests
./gradlew :app:testDebugUnitTest   # Room/DAO tests under Robolectric
./gradlew :app:assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17+ (built with JDK 21, emitting Java 17 bytecode) and Gradle 9.8 (wrapper).
