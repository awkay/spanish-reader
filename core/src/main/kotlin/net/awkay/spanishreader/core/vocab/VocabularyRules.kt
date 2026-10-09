package net.awkay.spanishreader.core.vocab

/** A word form the learner has encountered. [form] is the normalized form (see Tokenizer.normalize). */
data class VocabEntry(
    val form: String,
    val lemma: String? = null,
    val status: WordStatus,
    val translation: String? = null,
    val contextSentence: String? = null,
    val firstSeenMillis: Long,
    val lastSeenMillis: Long,
    val timesSeen: Int = 1,
)

/** Pure status-transition rules. Persistence layers map their records to/from [VocabEntry]. */
object VocabularyRules {

    /**
     * The learner tapped [form]. An absent or NEW word is auto-added at LEVEL_1, or at [inherited] (the status of
     * its word family, see [WordFamilies]) when it has one; anything else is returned unchanged.
     */
    fun onTap(
        entry: VocabEntry?, form: String, now: Long, contextSentence: String? = null, inherited: WordStatus? = null,
    ): VocabEntry = when {
        entry == null -> VocabEntry(
            form = form,
            status = inherited ?: WordStatus.LEVEL_1,
            contextSentence = contextSentence,
            firstSeenMillis = now,
            lastSeenMillis = now,
            timesSeen = 1,
        )

        entry.status == WordStatus.NEW -> entry.copy(
            status = inherited ?: WordStatus.LEVEL_1,
            contextSentence = entry.contextSentence ?: contextSentence,
            lastSeenMillis = now,
        )

        else -> entry
    }

    fun setStatus(entry: VocabEntry, status: WordStatus, now: Long): VocabEntry =
        if (entry.status == status) entry else entry.copy(status = status, lastSeenMillis = now)

    /**
     * Forms on a finished page that are still NEW (or have no status yet), in page order without repeats.
     * Ignored and in-progress words are left alone.
     */
    fun onPageFinished(pageWordForms: Collection<String>, currentStatuses: Map<String, WordStatus>): List<String> =
        pageWordForms.asSequence()
            .distinct()
            .filter { (currentStatuses[it] ?: WordStatus.NEW) == WordStatus.NEW }
            .toList()

    /**
     * Turning past a page: every word still NEW on it enters the vocabulary at LEVEL_1, with the sentence it
     * appeared in and any AI lemma/meaning from [details]. A word whose family the learner already has (see
     * [WordFamilies]) enters at that family's status instead ([inherited], by form); that is the only way a word
     * becomes KNOWN without the learner marking it.
     */
    fun applyPageFinished(
        pageWordForms: Collection<String>,
        entries: Map<String, VocabEntry>,
        now: Long,
        details: Map<String, WordDetail> = emptyMap(),
        inherited: Map<String, WordStatus> = emptyMap(),
    ): List<VocabEntry> =
        onPageFinished(pageWordForms, entries.mapValues { it.value.status }).map { form ->
            val d = details[form]
            val existing = entries[form]
            val status = inherited[form] ?: WordStatus.LEVEL_1
            existing?.copy(
                status = status,
                lemma = existing.lemma ?: d?.lemma,
                translation = existing.translation ?: d?.translation,
                contextSentence = existing.contextSentence ?: d?.contextSentence,
                lastSeenMillis = now,
            ) ?: VocabEntry(
                form = form, lemma = d?.lemma, status = status, translation = d?.translation,
                contextSentence = d?.contextSentence, firstSeenMillis = now, lastSeenMillis = now,
            )
        }

    /** The learner's explicit "these are all known": every word on the page still NEW becomes KNOWN. */
    fun markNewAsKnown(pageWordForms: Collection<String>, entries: Map<String, VocabEntry>, now: Long): List<VocabEntry> =
        onPageFinished(pageWordForms, entries.mapValues { it.value.status }).map { form ->
            entries[form]?.copy(status = WordStatus.KNOWN, lastSeenMillis = now)
                ?: VocabEntry(form = form, status = WordStatus.KNOWN, firstSeenMillis = now, lastSeenMillis = now)
        }
}

/** What is known about a word when it is added automatically: where it was seen and what the AI said it means. */
data class WordDetail(val contextSentence: String? = null, val lemma: String? = null, val translation: String? = null)
