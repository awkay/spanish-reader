package net.awkay.spanishreader.core.text

import net.awkay.spanishreader.core.text.Tokenizer.RawToken

/**
 * Assigns a sentence index to each token.
 *
 * A sentence ends after `.`, `!`, `?`, `…` (or `...`) when followed by whitespace, a closing quote, or the end of text,
 * and at every line break. Trailing closing quotes and whitespace stay with the sentence they follow.
 * Exceptions: common abbreviations (Sr., Dra., etc.), single-letter initials, and `?`/`!`/`…` followed by a
 * lowercase word (dialogue such as `—¿Vienes? —preguntó ella.`).
 */
internal object SentenceSegmenter {
    private val TERMINALS = setOf(".", "!", "?", "…")
    private val CLOSERS = setOf("»", "”", "’", "\"", "'", ")", "]")
    private val SKIPPABLE_BEFORE_NEXT_WORD = setOf("—", "–", "-", "¿", "¡", "«", "“", "\"", "(", "'", "‘")

    val ABBREVIATIONS = setOf(
        "sr", "sra", "srta", "sres", "dr", "dra", "lic", "ing", "prof", "profa", "arq",
        "ud", "uds", "vd", "vds", "etc", "av", "avda", "pág", "págs", "núm", "tel", "aprox",
        "gral", "cap", "dpto", "depto", "cía", "ej", "fig", "vol", "ed", "mr", "mrs", "st",
    )

    fun assign(tokens: List<RawToken>): IntArray {
        val result = IntArray(tokens.size)
        var sentence = 0
        var pendingBreak = false
        var sentenceHasContent = false
        for ((i, t) in tokens.withIndex()) {
            if (pendingBreak && !(t.kind == TokenKind.WHITESPACE || isAttachedCloser(tokens, i))) {
                sentence++
                pendingBreak = false
                sentenceHasContent = false
            }
            result[i] = sentence
            if (t.kind != TokenKind.WHITESPACE) sentenceHasContent = true
            if (t.kind == TokenKind.WHITESPACE && '\n' in t.text && sentenceHasContent) pendingBreak = true
            if (isTerminal(t) && endsSentence(tokens, i)) pendingBreak = true
        }
        return result
    }

    private fun isTerminal(t: RawToken): Boolean =
        t.kind == TokenKind.PUNCT && (t.text in TERMINALS || (t.text.length > 1 && t.text.all { it == '.' }))

    private fun isAttachedCloser(tokens: List<RawToken>, i: Int): Boolean =
        tokens[i].text in CLOSERS && i > 0 && tokens[i - 1].kind != TokenKind.WHITESPACE

    private fun endsSentence(tokens: List<RawToken>, i: Int): Boolean {
        // Must be followed (after optional closers / more terminals) by whitespace or end of text.
        var j = i + 1
        while (j < tokens.size && (isTerminal(tokens[j]) || isAttachedCloser(tokens, j))) j++
        if (j < tokens.size && tokens[j].kind != TokenKind.WHITESPACE) return false

        val next = nextWordAfter(tokens, j)
        if (tokens[i].text == ".") {
            val prev = tokens.getOrNull(i - 1)
            if (prev != null && prev.kind == TokenKind.WORD) {
                val key = Tokenizer.normalize(prev.text)
                if (key == "etc") return next == null || next.text.first().isUpperCase()
                if (key in ABBREVIATIONS) return false
                if (prev.text.length == 1 && prev.text[0].isUpperCase()) return false
            }
        }
        // After ? ! or an ellipsis, a lowercase continuation means the sentence goes on (dialogue tags).
        return tokens[i].text == "." || next == null || !next.text.first().isLowerCase()
    }

    /** The next word after position [from], skipping spaces and dialogue/opening punctuation; null if a line break or other token intervenes. */
    private fun nextWordAfter(tokens: List<RawToken>, from: Int): RawToken? {
        var j = from
        while (j < tokens.size) {
            val t = tokens[j]
            when {
                t.kind == TokenKind.WORD -> return t
                t.kind == TokenKind.WHITESPACE && '\n' !in t.text -> j++
                t.kind == TokenKind.PUNCT && t.text in SKIPPABLE_BEFORE_NEXT_WORD -> j++
                else -> return null
            }
        }
        return null
    }
}
