package net.awkay.spanishreader.core.text

enum class TokenKind {
    /** A run of letters, possibly with internal apostrophes/hyphens. The only kind that is vocabulary. */
    WORD,

    /** Punctuation, digits, symbols: anything that is neither a word nor whitespace. */
    PUNCT,

    /** Spaces, tabs, newlines. */
    WHITESPACE,
}

/**
 * A slice of the original text. [start] is the char offset into the source, so
 * `source.substring(start, end)` == [text].
 */
data class Token(
    val index: Int,
    val text: String,
    val kind: TokenKind,
    val start: Int,
    /** Lookup key for vocabulary; only set for [TokenKind.WORD]. */
    val normalized: String?,
    val sentenceIndex: Int,
) {
    val end: Int get() = start + text.length
    val isWord: Boolean get() = kind == TokenKind.WORD
}

/** A tokenized document: the token list plus sentence lookups. */
class TokenizedText(val source: String, val tokens: List<Token>) {

    /** Token index ranges (end exclusive) for each sentence, in order. */
    val sentenceRanges: List<IntRange> = buildList {
        var start = 0
        for (i in 1..tokens.size) {
            if (i == tokens.size || tokens[i].sentenceIndex != tokens[start].sentenceIndex) {
                add(start until i)
                start = i
            }
        }
    }

    val sentenceCount: Int get() = sentenceRanges.size

    val words: List<Token> get() = tokens.filter { it.isWord }

    fun sentenceTokens(sentenceIndex: Int): List<Token> {
        val range = sentenceRanges[sentenceIndex]
        return tokens.subList(range.first, range.last + 1)
    }

    /** The sentence's text with surrounding whitespace trimmed. */
    fun sentenceText(sentenceIndex: Int): String =
        sentenceTokens(sentenceIndex).joinToString("") { it.text }.trim()

    fun sentenceFor(token: Token): String = sentenceText(token.sentenceIndex)

    fun sentences(): List<String> = sentenceRanges.indices.map(::sentenceText)
}
