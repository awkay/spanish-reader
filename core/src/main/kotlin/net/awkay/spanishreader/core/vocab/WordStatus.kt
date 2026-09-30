package net.awkay.spanishreader.core.vocab

/**
 * LingQ-style word status. [code] is the stable persisted value.
 * NEW is shown blue; LEVEL_1..LEARNED are shown in yellows of decreasing intensity; KNOWN and IGNORED are not highlighted.
 */
enum class WordStatus(val code: Int, val label: String) {
    NEW(0, "New"),
    LEVEL_1(1, "Level 1"),
    RECOGNIZED(2, "Recognized"),
    FAMILIAR(3, "Familiar"),
    LEARNED(4, "Learned"),
    KNOWN(5, "Known"),
    IGNORED(-1, "Ignored");

    val isHighlighted: Boolean get() = code in 0..4

    /** Being learned: on the yellow part of the ladder. */
    val isLearning: Boolean get() = code in 1..4

    /** Highlight strength for learning statuses: 1.0 for LEVEL_1 down to 0.25 for LEARNED; 0 otherwise. */
    val highlightIntensity: Float get() = if (isLearning) (5 - code) / 4f else 0f

    /** One step up the 1..5 ladder. NEW enters at LEVEL_1; KNOWN and IGNORED stay put. */
    fun next(): WordStatus = when (this) {
        NEW -> LEVEL_1
        IGNORED, KNOWN -> this
        else -> fromCode(code + 1)
    }

    /** One step down the 1..5 ladder, never below LEVEL_1. NEW and IGNORED stay put. */
    fun previous(): WordStatus = when (this) {
        NEW, IGNORED, LEVEL_1 -> this
        else -> fromCode(code - 1)
    }

    companion object {
        fun fromCode(code: Int): WordStatus =
            entries.firstOrNull { it.code == code } ?: throw IllegalArgumentException("Unknown status code $code")
    }
}
