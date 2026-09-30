package net.awkay.spanishreader.data

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

    private fun GlossEntity.decode(): Gloss? = runCatching { format.decodeFromString(Gloss.serializer(), json) }.getOrNull()
}
