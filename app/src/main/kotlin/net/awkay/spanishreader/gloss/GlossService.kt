package net.awkay.spanishreader.gloss

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.awkay.spanishreader.core.gloss.CachingGlosser
import net.awkay.spanishreader.core.gloss.Gloss
import net.awkay.spanishreader.core.gloss.GlossCache
import net.awkay.spanishreader.core.gloss.GlossRequest
import net.awkay.spanishreader.core.gloss.GlossResult
import net.awkay.spanishreader.core.gloss.Glosser
import net.awkay.spanishreader.core.gloss.GlosserFactory
import net.awkay.spanishreader.core.gloss.GlosserConfig
import net.awkay.spanishreader.core.gloss.SentenceAnalyzer
import net.awkay.spanishreader.data.PhraseStore
import net.awkay.spanishreader.data.SettingsRepository
import okhttp3.OkHttpClient

sealed interface LookupResult {
    /** [fromOtherSentence]: the live lookup failed and this is a cached gloss of the same form in another sentence. */
    data class Found(val gloss: Gloss, val fromOtherSentence: Boolean = false) : LookupResult

    data class Failed(val message: String) : LookupResult
}

/** Tap-time glossing: exact cache hit, else live lookup (cached), else any cached gloss of the form. */
class GlossService(
    private val settings: SettingsRepository,
    private val cache: GlossCache,
    private val factory: (GlosserConfig, GlosserConfig?) -> Glosser = { p, f -> GlosserFactory.create(p, f, sharedHttp) },
    private val sentenceFactory: (GlosserConfig) -> SentenceAnalyzer = { GlosserFactory.createSentenceAnalyzer(it, sharedHttp) },
    private val sentences: PhraseStore? = null,
) {
    /** The sentence translator / idiom finder for the primary provider, or null when glossing isn't configured. */
    suspend fun sentenceAnalyzer(): SentenceAnalyzer? {
        val primary = settings.current().primaryGlosser
        return if (primary.isComplete) sentenceFactory(primary) else null
    }

    /**
     * The English translation of [sentence]: stored one if any, else asks the model (which also yields the sentence's
     * idioms) and stores both. Failure carries a message for the UI.
     */
    suspend fun translate(sentence: String): Result<String> {
        sentences?.translation(sentence)?.let { return Result.success(it) }
        val analyzer = sentenceAnalyzer()
            ?: return Result.failure(IllegalStateException("Glossing is not configured. Set it up in Settings."))
        val analysis = analyzer.analyze(listOf(sentence)).single()
        val translation = analysis?.translation
            ?: return Result.failure(IllegalStateException("The model didn't return a translation. Try again."))
        sentences?.save(sentence, analysis.phrases)
        sentences?.saveTranslation(sentence, translation)
        sentences?.markScanned(listOf(sentence))
        return Result.success(translation)
    }

    /**
     * "Improve answer": asks again with the stronger model from settings, showing the model the answer the learner
     * found unhelpful. A good result replaces the cached gloss.
     */
    suspend fun improve(form: String, sentence: String, previous: Gloss?): LookupResult {
        val config = settings.current().improveGlosser
        val problems = config.problems()
        if (problems.isNotEmpty()) return LookupResult.Failed(problems.joinToString("\n") + "\nSet it up in Settings.")
        val glosser = try {
            factory(config, null)
        } catch (e: IllegalArgumentException) {
            return LookupResult.Failed(e.message ?: "Glossing is not configured")
        }
        return when (val r = glosser.gloss(listOf(GlossRequest(form, sentence, previous = previous))).single()) {
            is GlossResult.Success -> {
                cache.put(form, sentence, r.gloss)
                LookupResult.Found(r.gloss)
            }
            is GlossResult.Failure -> LookupResult.Failed(r.error)
        }
    }

    private val lock = Mutex()
    private var built: Triple<GlosserConfig, GlosserConfig?, Glosser>? = null

    /** The configured glosser wrapped with the persistent cache, or an error message if settings are incomplete. */
    suspend fun cachingGlosser(): Result<Glosser> = lock.withLock {
        val s = settings.current()
        val primary = s.primaryGlosser
        val fallback = s.fallbackGlosser
        built?.let { (p, f, g) -> if (p == primary && f == fallback) return@withLock Result.success(g) }
        val problems = primary.problems()
        if (problems.isNotEmpty()) return@withLock Result.failure(IllegalStateException(problems.joinToString("\n") + "\nSet it up in Settings."))
        val glosser = CachingGlosser(factory(primary, fallback), cache)
        built = Triple(primary, fallback, glosser)
        Result.success(glosser)
    }

    suspend fun lookup(form: String, sentence: String): LookupResult {
        cache.get(form, sentence)?.let { return LookupResult.Found(it) }
        val error = cachingGlosser().fold(
            onSuccess = { g ->
                when (val r = g.gloss(listOf(GlossRequest(form, sentence))).single()) {
                    is GlossResult.Success -> return LookupResult.Found(r.gloss)
                    is GlossResult.Failure -> r.error
                }
            },
            onFailure = { it.message ?: "Glossing is not configured" },
        )
        cache.getByForm(form)?.let { return LookupResult.Found(it, fromOtherSentence = true) }
        return LookupResult.Failed(error)
    }

    companion object {
        val sharedHttp: OkHttpClient get() = GlosserFactory.defaultHttpClient
    }
}
