# spanish-reader

Personal Android app replicating the core of LingQ for **Latin American Spanish**. Single user (Tony), not published.
Current state and next steps: `docs/STATUS.md` — read it first.

## Product
- **Import** Spanish text: Android share sheet (primary), paste, .txt. Later: web article extraction, EPUB.
- **Listen mode**: natural Latin American Spanish TTS, current sentence highlighted, speed control, loop sentence,
  background playback with lock-screen controls.
- **Read mode**: paged text (~250 words/page). Words colored by status. Tap a word → bottom sheet with meaning
  *in this sentence*, lemma, grammar note (conjugation/clitics), status buttons.
- **Vocabulary**: every word tapped is auto-added; list filterable by status, with saved context sentence.

### Word status model (mirrors LingQ)
| Status | Highlight |
|---|---|
| NEW (0) — never seen | blue |
| LEVEL_1 (1) — just tapped | strong yellow |
| RECOGNIZED (2) / FAMILIAR (3) / LEARNED (4) | progressively fainter yellow |
| KNOWN (5) | none |
| IGNORED (-1) — names, numbers | none |

Rules:
- Tapping a NEW word sets it to LEVEL_1 and adds it to the vocabulary.
- **Finishing a page promotes every word still NEW on it to KNOWN.** Without this rule the page stays blue forever.
- Vocabulary is keyed by **lowercased written form** (`hablo` ≠ `hablas`, `sí` ≠ `si`). Lemma is stored for grouping.

## Architecture decisions (settled — don't relitigate)
- **Kotlin + Jetpack Compose**, native. Not a PWA/ClojureScript: listen mode needs reliable background audio.
- **Local-first**: Room/SQLite on device, no server. JSON backup/export later.
- **`:core`** = pure Kotlin/JVM module (no Android deps): tokenizer, sentences, pagination, status rules, glossing
  clients. All logic that can be unit-tested on the JVM lives here. **`:app`** = Android UI/DB/audio (not yet created).
- **Glossing is provider-pluggable** behind `Glosser`:
  - Default: **Ollama Cloud** with a large Kimi-class model, via the OpenAI-compatible client (`https://ollama.com/v1`).
    Model name must be a setting, never hard-coded — hosted model lineups change.
  - Same client serves local Ollama (`http://<host>:11434/v1`) and z.ai GLM.
  - `AnthropicGlosser` (Claude Haiku 4.5) as quality fallback.
  - **Pre-gloss on import**: batch-gloss all non-KNOWN words of a lesson in the background, cache results, so taps
    are instant and work offline. Live lookup only for cache misses.
  - Offline fallback (later): Spanish→English dictionary from Wiktionary (kaikki.org extract).
- **TTS**: cloud neural voice (Google Chirp HD or Azure Neural, es-MX/es-US) generated once per lesson and cached as
  MP3 with sentence timings; on-device Android TTS as fallback. Playback via Media3 `MediaSessionService`.
- API keys live in app settings (personal-only app; acceptable because it's never published).

## Build & test
- Gradle wrapper, Kotlin DSL, version catalog `gradle/libs.versions.toml`.
- `./gradlew :core:test` — JUnit 5; HTTP clients tested with OkHttp MockWebServer.
- Android module needs the Android SDK. In Claude Code cloud sessions the environment must allow
  `dl.google.com` and `maven.google.com`.

## Conventions
- No colors in shell scripts.
- Keep `:core` free of Android dependencies.
- Every change to logic in `:core` comes with tests; run them before pushing.
- Update `docs/STATUS.md` at the end of each work session.
