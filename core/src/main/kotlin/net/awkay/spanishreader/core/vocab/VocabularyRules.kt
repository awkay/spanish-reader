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
     * The learner tapped [form]. An absent or NEW word is auto-added at LEVEL_1; anything else is returned unchanged.
     */
    fun onTap(entry: VocabEntry?, form: String, now: Long, contextSentence: String? = null): VocabEntry = when {
        entry == null -> VocabEntry(
            form = form,
            status = WordStatus.LEVEL_1,
            contextSentence = contextSentence,
            firstSeenMillis = now,
            lastSeenMillis = now,
            timesSeen = 1,
        )

        entry.status == WordStatus.NEW -> entry.copy(
            status = WordStatus.LEVEL_1,
            contextSentence = entry.contextSentence ?: contextSentence,
            lastSeenMillis = now,
        )

        else -> entry
    }

    fun setStatus(entry: VocabEntry, status: WordStatus, now: Long): VocabEntry =
        if (entry.status == status) entry else entry.copy(status = status, lastSeenMillis = now)

    /**
     * LingQ's "paging moves to known" rule: returns the distinct forms on a finished page that are still NEW
     * (or have no status yet) and should become KNOWN. Ignored and in-progress words are left alone.
     */
    fun onPageFinished(pageWordForms: Collection<String>, currentStatuses: Map<String, WordStatus>): List<String> =
        pageWordForms.asSequence()
            .distinct()
            .filter { (currentStatuses[it] ?: WordStatus.NEW) == WordStatus.NEW }
            .toList()

    /** Applies [onPageFinished] and produces the KNOWN entries to upsert. */
    fun applyPageFinished(
        pageWordForms: Collection<String>,
        entries: Map<String, VocabEntry>,
        now: Long,
    ): List<VocabEntry> =
        onPageFinished(pageWordForms, entries.mapValues { it.value.status }).map { form ->
            entries[form]?.copy(status = WordStatus.KNOWN, lastSeenMillis = now)
                ?: VocabEntry(form = form, status = WordStatus.KNOWN, firstSeenMillis = now, lastSeenMillis = now)
        }
}
