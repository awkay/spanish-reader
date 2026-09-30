package net.awkay.spanishreader.core.vocab

import net.awkay.spanishreader.core.text.TokenizedText

/** Per-lesson vocabulary breakdown. Counts are over unique word forms; absent forms count as NEW. */
data class LessonStats(
    val totalWords: Int,
    val uniqueWords: Int,
    val countByStatus: Map<WordStatus, Int>,
) {
    fun count(status: WordStatus): Int = countByStatus[status] ?: 0

    /** Percentage (0..100) of unique words with [status]. */
    fun percent(status: WordStatus): Double =
        if (uniqueWords == 0) 0.0 else count(status) * 100.0 / uniqueWords

    val newCount: Int get() = count(WordStatus.NEW)
    val learningCount: Int get() = WordStatus.entries.filter { it.isLearning }.sumOf(::count)
    val knownPercent: Double get() = percent(WordStatus.KNOWN)

    companion object {
        fun compute(wordForms: List<String>, statuses: Map<String, WordStatus>): LessonStats {
            val unique = wordForms.toSet()
            val counts = unique.groupingBy { statuses[it] ?: WordStatus.NEW }.eachCount()
            return LessonStats(
                totalWords = wordForms.size,
                uniqueWords = unique.size,
                countByStatus = WordStatus.entries.associateWith { counts[it] ?: 0 },
            )
        }

        fun compute(text: TokenizedText, statuses: Map<String, WordStatus>): LessonStats =
            compute(text.tokens.mapNotNull { it.normalized }, statuses)
    }
}
