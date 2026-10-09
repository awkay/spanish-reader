package net.awkay.spanishreader.data

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import net.awkay.spanishreader.core.gloss.FoundPhrase
import net.awkay.spanishreader.core.gloss.Gloss
import net.awkay.spanishreader.core.gloss.GlossCache

/**
 * Persistent [GlossCache]. Unreadable rows (e.g. from an older Gloss schema) are treated as misses.
 * When [phrases] is given, an expression a gloss points out is also recorded for its sentence, so the reader can
 * underline it.
 */
class RoomGlossCache(
    private val dao: GlossDao,
    private val phrases: PhraseStore? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : GlossCache {
    private val format = Json { ignoreUnknownKeys = true }

    override suspend fun get(form: String, sentence: String): Gloss? =
        dao.get(GlossCache.formKey(form), GlossCache.sentenceHash(sentence))?.decode()

    override suspend fun getByForm(form: String): Gloss? = dao.latestByForm(GlossCache.formKey(form))?.decode()

    /** Emits whenever glosses are added or replaced. */
    fun changes(): Flow<Long?> = dao.observeLatest()

    /**
     * The AI lemma of each form in [sentenceByForm] (normalized form → a sentence it appears in), from the gloss for
     * that sentence when cached, else the most recent gloss of the form. Forms without a gloss are left out.
     */
    suspend fun lemmas(sentenceByForm: Map<String, String>): Map<String, String> {
        val byKey = sentenceByForm.keys.associateBy { GlossCache.formKey(it) }
        val rows = byKey.keys.chunked(MAX_BIND_ARGS).flatMap { dao.forForms(it) }.groupBy { it.formKey }
        val out = HashMap<String, String>()
        for ((key, glosses) in rows) {
            val form = byKey[key] ?: continue
            val hash = GlossCache.sentenceHash(sentenceByForm.getValue(form))
            val best = glosses.firstOrNull { it.sentenceHash == hash } ?: glosses.maxBy { it.storedAtMillis }
            val lemma = (best.decode() ?: glosses.firstNotNullOfOrNull { it.decode() })?.lemma?.trim()
            if (!lemma.isNullOrEmpty()) out[form] = lemma
        }
        return out
    }

    override suspend fun put(form: String, sentence: String, gloss: Gloss) {
        dao.upsert(
            GlossEntity(
                formKey = GlossCache.formKey(form),
                sentenceHash = GlossCache.sentenceHash(sentence),
                json = format.encodeToString(Gloss.serializer(), gloss),
                storedAtMillis = clock(),
            ),
        )
        val phrase = gloss.phrase?.trim()
        if (phrases != null && gloss.isIdiomOrPhrase && phrase != null && ' ' in phrase) {
            phrases.save(sentence, listOf(FoundPhrase(phrase, gloss.phraseMeaning.orEmpty())))
        }
    }

    private companion object {
        /** SQLite's historical SQLITE_MAX_VARIABLE_NUMBER on older Android versions. */
        const val MAX_BIND_ARGS = 900
    }

    private fun GlossEntity.decode(): Gloss? = runCatching { format.decodeFromString(Gloss.serializer(), json) }.getOrNull()
}
