package net.awkay.spanishreader.core.gloss

import net.awkay.spanishreader.core.text.Tokenizer
import java.security.MessageDigest
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/** Glosses keyed by normalized form + sentence hash, with a form-only fallback (e.g. for offline use). */
interface GlossCache {
    suspend fun get(form: String, sentence: String): Gloss?

    /** Any cached gloss for [form], regardless of sentence; the most recently stored one where possible. */
    suspend fun getByForm(form: String): Gloss?

    suspend fun put(form: String, sentence: String, gloss: Gloss)

    companion object {
        fun formKey(form: String): String = Tokenizer.normalize(form.trim())

        /** SHA-256 hex of the sentence with whitespace collapsed; stable across trivial formatting differences. */
        fun sentenceHash(sentence: String): String {
            val canonical = Normalizer.normalize(sentence.trim().replace(Regex("\\s+"), " "), Normalizer.Form.NFC)
            return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }
}

class InMemoryGlossCache : GlossCache {
    private val exact = ConcurrentHashMap<Pair<String, String>, Gloss>()
    private val byForm = ConcurrentHashMap<String, Gloss>()

    override suspend fun get(form: String, sentence: String): Gloss? =
        exact[GlossCache.formKey(form) to GlossCache.sentenceHash(sentence)]

    override suspend fun getByForm(form: String): Gloss? = byForm[GlossCache.formKey(form)]

    override suspend fun put(form: String, sentence: String, gloss: Gloss) {
        val key = GlossCache.formKey(form)
        exact[key to GlossCache.sentenceHash(sentence)] = gloss
        byForm[key] = gloss
    }

    val size: Int get() = exact.size
}

/** Serves exact cache hits and forwards only the misses to [delegate], caching its successes. */
class CachingGlosser(private val delegate: Glosser, private val cache: GlossCache) : Glosser {
    override suspend fun gloss(requests: List<GlossRequest>): List<GlossResult> {
        val cached = requests.associate { it.id to cache.get(it.form, it.sentence) }
        val misses = requests.filter { cached[it.id] == null }
        val fresh = if (misses.isEmpty()) emptyMap() else delegate.gloss(misses).associateBy { it.id }
        for (r in misses) {
            (fresh[r.id] as? GlossResult.Success)?.let { cache.put(r.form, r.sentence, it.gloss) }
        }
        return requests.map { r -> cached[r.id]?.let { GlossResult.Success(r.id, it) } ?: fresh.getValue(r.id) }
    }
}
