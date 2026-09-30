package net.awkay.spanishreader.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import net.awkay.spanishreader.core.gloss.FoundPhrase
import net.awkay.spanishreader.core.gloss.GlossCache

/** Idioms and fixed expressions per sentence, keyed like the gloss cache (hash of the sentence text). */
class PhraseStore(
    private val dao: PhraseDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun save(sentence: String, phrases: List<FoundPhrase>) {
        if (phrases.isEmpty()) return
        val hash = GlossCache.sentenceHash(sentence)
        val now = clock()
        dao.upsert(phrases.map { PhraseEntity(hash, it.phrase.trim(), it.meaning.trim(), now) })
    }

    suspend fun forSentence(sentence: String): List<FoundPhrase> =
        dao.forSentence(GlossCache.sentenceHash(sentence)).map { FoundPhrase(it.phrase, it.meaning) }

    /** The subset of [sentences] never sent to the phrase scan. */
    suspend fun unscanned(sentences: Collection<String>): List<String> {
        val byHash = sentences.distinct().associateBy(GlossCache::sentenceHash)
        val done = byHash.keys.chunked(MAX_BIND_ARGS).flatMap { dao.scanned(it) }.toSet()
        return byHash.filterKeys { it !in done }.values.toList()
    }

    suspend fun markScanned(sentences: Collection<String>) {
        val now = clock()
        dao.markScanned(sentences.distinct().map { PhraseScanEntity(GlossCache.sentenceHash(it), now) })
    }

    /** Phrases for [sentences], keyed by sentence text; updates as new phrases are found. */
    fun observe(sentences: List<String>): Flow<Map<String, List<FoundPhrase>>> {
        val textByHash = sentences.distinct().associateBy(GlossCache::sentenceHash)
        if (textByHash.isEmpty()) return flowOf(emptyMap())
        val flows = textByHash.keys.chunked(MAX_BIND_ARGS).map { dao.observeFor(it) }
        return combine(flows) { chunks ->
            chunks.asList().flatten()
                .groupBy { textByHash.getValue(it.sentenceHash) }
                .mapValues { (_, rows) -> rows.map { FoundPhrase(it.phrase, it.meaning) } }
        }
    }

    suspend fun clear() {
        dao.clearPhrases()
        dao.clearScans()
    }

    private companion object {
        const val MAX_BIND_ARGS = 900
    }
}
