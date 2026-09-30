package net.awkay.spanishreader.core.text

import java.text.Normalizer
import java.util.Locale

object Tokenizer {
    val SPANISH: Locale = Locale.forLanguageTag("es")

    private val APOSTROPHES = setOf('\'', '’')
    private val HYPHENS = setOf('-', '‐', '‑')

    /** Tokenizes [text] and assigns sentence indices. Concatenating all token texts yields [text] exactly. */
    fun tokenize(text: String): TokenizedText {
        val raw = splitRaw(text)
        val sentenceIndices = SentenceSegmenter.assign(raw)
        val tokens = raw.mapIndexed { i, r ->
            Token(
                index = i,
                text = r.text,
                kind = r.kind,
                start = r.start,
                normalized = if (r.kind == TokenKind.WORD) normalize(r.text) else null,
                sentenceIndex = sentenceIndices[i],
            )
        }
        return TokenizedText(text, tokens)
    }

    /** Vocabulary key for a word: NFC, Spanish lowercase, typographic apostrophe folded. Accents are kept. */
    fun normalize(word: String): String =
        Normalizer.normalize(word, Normalizer.Form.NFC)
            .replace('’', '\'')
            .lowercase(SPANISH)

    internal data class RawToken(val text: String, val kind: TokenKind, val start: Int)

    internal fun splitRaw(text: String): List<RawToken> {
        val out = ArrayList<RawToken>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val start = i
            when {
                isWordChar(cp) -> {
                    i += Character.charCount(cp)
                    while (i < text.length) {
                        val c = text.codePointAt(i)
                        if (isWordChar(c)) {
                            i += Character.charCount(c)
                        } else if (isJoiner(c) && i + 1 < text.length && isWordChar(text.codePointAt(i + 1))) {
                            i += 1
                        } else {
                            break
                        }
                    }
                    out += RawToken(text.substring(start, i), TokenKind.WORD, start)
                }

                isSpace(cp) -> {
                    while (i < text.length && isSpace(text.codePointAt(i))) i += Character.charCount(text.codePointAt(i))
                    out += RawToken(text.substring(start, i), TokenKind.WHITESPACE, start)
                }

                Character.isDigit(cp) -> {
                    // Numbers like 1.500 or 3,14 stay one token.
                    i += 1
                    while (i < text.length) {
                        val c = text[i]
                        if (c.isDigit()) i++
                        else if ((c == '.' || c == ',') && i + 1 < text.length && text[i + 1].isDigit()) i++
                        else break
                    }
                    out += RawToken(text.substring(start, i), TokenKind.PUNCT, start)
                }

                cp == '.'.code -> {
                    while (i < text.length && text[i] == '.') i++
                    out += RawToken(text.substring(start, i), TokenKind.PUNCT, start)
                }

                else -> {
                    i += Character.charCount(cp)
                    // Keep combining marks with the base symbol so we never split a grapheme.
                    while (i < text.length && isMark(text.codePointAt(i))) i += Character.charCount(text.codePointAt(i))
                    out += RawToken(text.substring(start, i), TokenKind.PUNCT, start)
                }
            }
        }
        return out
    }

    private fun isMark(cp: Int): Boolean = when (Character.getType(cp)) {
        Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt() -> true
        else -> false
    }

    /** Letters plus combining marks (so decomposed "á" stays in one word). */
    private fun isWordChar(cp: Int): Boolean = Character.isLetter(cp) || isMark(cp)

    private fun isJoiner(cp: Int): Boolean = cp.toChar() in APOSTROPHES || cp.toChar() in HYPHENS

    private fun isSpace(cp: Int): Boolean = Character.isWhitespace(cp) || Character.isSpaceChar(cp)
}
