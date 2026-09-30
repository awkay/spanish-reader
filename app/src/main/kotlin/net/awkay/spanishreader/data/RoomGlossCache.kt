package net.awkay.spanishreader.data

import kotlinx.serialization.json.Json
import net.awkay.spanishreader.core.gloss.Gloss
import net.awkay.spanishreader.core.gloss.GlossCache

/** Persistent [GlossCache]. Unreadable rows (e.g. from an older Gloss schema) are treated as misses. */
class RoomGlossCache(
    private val dao: GlossDao,
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
    }

    private fun GlossEntity.decode(): Gloss? = runCatching { format.decodeFromString(Gloss.serializer(), json) }.getOrNull()
}
