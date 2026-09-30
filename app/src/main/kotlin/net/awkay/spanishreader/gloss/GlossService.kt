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
) {
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
