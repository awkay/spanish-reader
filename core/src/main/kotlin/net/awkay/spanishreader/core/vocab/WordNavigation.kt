package net.awkay.spanishreader.core.vocab

import net.awkay.spanishreader.core.text.Token

enum class NavDirection { NEXT, PREVIOUS }

/**
 * Word-by-word reading: the sheet's Next/Prev buttons move to the nearest highlighted word on the same page.
 * The web app ports this rule (web/src/core); keep the two identical.
 */
object WordNavigation {

    /**
     * The token index ([Token.index]) of the nearest word after/before [currentIndex] within [pageTokens] whose status
     * is highlighted: NEW (absent from [statuses] counts as NEW) or LEVEL_1..LEARNED. KNOWN, IGNORED and non-word
     * tokens are skipped. The current word's own status doesn't matter. Returns null at the page edge (the caller
     * never turns the page) or when [currentIndex] isn't on this page.
     */
    fun step(
        pageTokens: List<Token>,
        statuses: Map<String, WordStatus>,
        currentIndex: Int,
        direction: NavDirection,
    ): Int? {
        val pos = pageTokens.indexOfFirst { it.index == currentIndex }
        if (pos < 0) return null
        val positions = when (direction) {
            NavDirection.NEXT -> (pos + 1 until pageTokens.size)
            NavDirection.PREVIOUS -> (pos - 1 downTo 0)
        }
        for (i in positions) {
            val t = pageTokens[i]
            val form = t.normalized ?: continue
            if (t.isWord && (statuses[form] ?: WordStatus.NEW).isHighlighted) return t.index
        }
        return null
    }
}
