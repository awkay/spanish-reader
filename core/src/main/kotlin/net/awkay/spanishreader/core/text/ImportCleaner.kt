package net.awkay.spanishreader.core.text

/** Cleans text arriving from the share sheet, clipboard or a .txt file before it becomes a lesson. */
object ImportCleaner {
    private val LINE_ENDERS = setOf('.', '!', '?', '…', ':', ';', '»', '”', '"', ')')
    private val LINE_STARTERS = setOf('—', '–', '-', '•', '*', '«', '“', '¿', '¡')
    private val INVISIBLE = Regex("[\\uFEFF\\u200B\\u200C\\u200D\\u2060\\u00AD]")
    private val INLINE_SPACE = Regex("[ \\t\\u00A0\\u2007\\u202F]+")
    private val LIST_ITEM = Regex("^\\d+[.)]\\s")

    /**
     * Normalizes line endings, strips invisible characters, trims lines and collapses runs of spaces.
     * Blank lines separate paragraphs (at most one blank line is kept).
     * With [joinWrappedLines], a single line break inside a paragraph is treated as hard wrapping and joined with a
     * space, unless the line ends a sentence or the next line looks like dialogue or a list item. A word hyphenated
     * across the break (`compu-` / `tadora`) is rejoined.
     */
    fun clean(raw: String, joinWrappedLines: Boolean = true): String {
        val lines = raw.replace("\r\n", "\n").replace('\r', '\n').replace(INVISIBLE, "")
            .split('\n')
            .map { it.replace(INLINE_SPACE, " ").trim() }

        val out = StringBuilder()
        var inParagraph = false
        for (line in lines) {
            if (line.isEmpty()) {
                inParagraph = false
                continue
            }
            if (!inParagraph) {
                if (out.isNotEmpty()) out.append("\n\n")
                out.append(line)
                inParagraph = true
                continue
            }
            val prevEnd = out.last()
            when {
                !joinWrappedLines -> out.append('\n').append(line)
                prevEnd == '-' && out.length >= 2 && out[out.length - 2].isLetter() && line.first().isLowerCase() -> {
                    out.setLength(out.length - 1)
                    out.append(line)
                }
                prevEnd in LINE_ENDERS || line.first() in LINE_STARTERS || LIST_ITEM.containsMatchIn(line) ->
                    out.append('\n').append(line)
                else -> out.append(' ').append(line)
            }
        }
        return out.toString()
    }

    /** First non-blank line, shortened at a word boundary to at most [maxLength] characters. */
    fun suggestTitle(text: String, maxLength: Int = 60): String {
        val first = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return "Untitled"
        if (first.length <= maxLength) return first
        val cut = first.substring(0, maxLength)
        val space = cut.lastIndexOf(' ')
        return (if (space > maxLength / 2) cut.substring(0, space) else cut).trimEnd(',', ';', ':', ' ') + "…"
    }

    /** True if [text] is only a link (browsers often share just the URL); article extraction isn't supported yet. */
    fun isJustUrl(text: String): Boolean {
        val t = text.trim()
        return t.isNotEmpty() && ' ' !in t && '\n' !in t && (t.startsWith("http://") || t.startsWith("https://"))
    }
}
