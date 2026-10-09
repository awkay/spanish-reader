package net.awkay.spanishreader.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.awkay.spanishreader.core.vocab.VocabEntry
import net.awkay.spanishreader.core.gloss.GlossCache
import net.awkay.spanishreader.core.vocab.VocabularyRules
import net.awkay.spanishreader.core.vocab.WordDetail
import net.awkay.spanishreader.core.vocab.WordStatus

/**
 * Applies core [VocabularyRules] to the database. All forms must already be normalized (Tokenizer.normalize).
 * Forms absent from the table are NEW.
 */
class VocabRepository(
    private val db: AppDatabase,
    /** Where AI glosses live; words added by the page rule take their lemma and meaning from here. */
    private val glossCache: GlossCache? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.vocab()

    fun observeAll(): Flow<List<VocabEntry>> = dao.observeAll().map { rows -> rows.map { it.toEntry() } }

    fun observeByStatus(status: WordStatus): Flow<List<VocabEntry>> =
        dao.observeByStatus(status).map { rows -> rows.map { it.toEntry() } }

    /** Every stored form's status, for coloring text. Absent forms are NEW. */
    fun observeStatuses(): Flow<Map<String, WordStatus>> =
        dao.observeStatuses().map { rows -> rows.associate { it.form to it.status } }

    suspend fun get(form: String): VocabEntry? = dao.get(form)?.toEntry()

    /** Forgets [form] entirely; it becomes NEW again. */
    suspend fun delete(form: String) = dao.delete(form)

    /** Entries for the given forms; forms without a row are omitted (i.e. NEW). */
    suspend fun entries(forms: Collection<String>): Map<String, VocabEntry> =
        forms.distinct().chunked(MAX_BIND_ARGS).flatMap { dao.getAll(it) }.associate { it.form to it.toEntry() }

    suspend fun statuses(forms: Collection<String>): Map<String, WordStatus> =
        entries(forms).mapValues { it.value.status }

    /**
     * A NEW word was tapped: auto-add at LEVEL_1, or at [inherited], its word family's status (see WordFamilies).
     * Returns the resulting entry.
     */
    suspend fun tap(form: String, contextSentence: String?, inherited: WordStatus? = null): VocabEntry = db.withTransaction {
        val updated = VocabularyRules.onTap(dao.get(form)?.toEntry(), form, clock(), contextSentence, inherited)
        dao.upsert(VocabEntity.from(updated))
        updated
    }

    suspend fun setStatus(form: String, status: WordStatus, contextSentence: String? = null): VocabEntry =
        db.withTransaction {
            val now = clock()
            val existing = dao.get(form)?.toEntry()
                ?: VocabEntry(form, status = WordStatus.NEW, contextSentence = contextSentence, firstSeenMillis = now, lastSeenMillis = now)
            val updated = VocabularyRules.setStatus(existing, status, now)
            dao.upsert(VocabEntity.from(updated))
            updated
        }

    /**
     * Fills in lemma/translation from a gloss where they are still missing, without touching status (the first
     * gloss matches the saved context sentence). No-op for words without a row.
     */
    suspend fun annotate(form: String, lemma: String?, translation: String?) = db.withTransaction {
        dao.get(form)?.let {
            val updated = it.copy(lemma = it.lemma ?: lemma, translation = it.translation ?: translation)
            if (updated != it) dao.upsert(updated)
        }
    }

    /**
     * Turning past a page: its words still NEW enter the vocabulary at LEVEL_1 (only the learner marks words KNOWN),
     * with their sentence and, when pre-glossing got to them, the AI lemma and meaning. [pageWords] are
     * (normalized form, sentence) in page order. Words whose family the learner has enter at the family's status
     * ([inherited], by form). Returns the forms added.
     */
    suspend fun finishPage(
        pageWords: List<Pair<String, String>>, inherited: Map<String, WordStatus> = emptyMap(),
    ): List<String> {
        val forms = pageWords.map { it.first }
        val firstSentence = LinkedHashMap<String, String>()
        pageWords.forEach { (form, sentence) -> firstSentence.putIfAbsent(form, sentence) }
        val newForms = VocabularyRules.onPageFinished(forms, statuses(forms))
        // Gloss lookups happen outside the transaction; they only read the cache.
        val details = newForms.associateWith { form ->
            val sentence = firstSentence.getValue(form)
            val gloss = glossCache?.let { it.get(form, sentence) ?: it.getByForm(form) }
            WordDetail(sentence, gloss?.lemma, gloss?.meaningInContext)
        }
        return db.withTransaction {
            val added = VocabularyRules.applyPageFinished(forms, entries(forms), clock(), details, inherited)
            added.chunked(MAX_BIND_ARGS).forEach { dao.upsertAll(it.map(VocabEntity::from)) }
            added.map { it.form }
        }
    }

    /**
     * The learner's explicit "all the blue words here are known". Returns the forms marked. Their cached AI lemma is
     * stored too, so their spellings in later lessons count as the same family.
     */
    suspend fun markNewAsKnown(pageForms: Collection<String>): List<String> {
        val newForms = VocabularyRules.onPageFinished(pageForms, statuses(pageForms))
        val lemmas = newForms.associateWith { glossCache?.getByForm(it)?.lemma }
        return db.withTransaction {
            val marked = VocabularyRules.markNewAsKnown(pageForms, entries(pageForms), clock())
                .map { it.copy(lemma = it.lemma ?: lemmas[it.form]) }
            marked.chunked(MAX_BIND_ARGS).forEach { dao.upsertAll(it.map(VocabEntity::from)) }
            marked.map { it.form }
        }
    }

    /**
     * Gives entries saved without a lemma the one from their cached AI gloss, so they join their word family.
     * Safe to repeat; returns how many entries were filled in.
     */
    suspend fun backfillLemmas(): Int {
        val cache = glossCache ?: return 0
        val missing = dao.getAllOnce().filter { it.lemma == null }
        val filled = missing.mapNotNull { e -> cache.getByForm(e.form)?.lemma?.takeIf { it.isNotBlank() }?.let { e.copy(lemma = it) } }
        if (filled.isEmpty()) return 0
        return db.withTransaction {
            // Only rows still without a lemma, in case something annotated them meanwhile.
            val current = filled.map { it.form }.chunked(MAX_BIND_ARGS).flatMap { dao.getAll(it) }.associateBy { it.form }
            val updates = filled.mapNotNull { f -> current[f.form]?.takeIf { it.lemma == null }?.copy(lemma = f.lemma) }
            updates.chunked(MAX_BIND_ARGS).forEach { dao.upsertAll(it) }
            updates.size
        }
    }

    private companion object {
        /** SQLite's historical SQLITE_MAX_VARIABLE_NUMBER on older Android versions. */
        const val MAX_BIND_ARGS = 900
    }
}
