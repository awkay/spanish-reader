package net.awkay.spanishreader.core.text

data class Page(val index: Int, val tokens: List<Token>) {
    val wordCount: Int get() = tokens.count { it.isWord }
    val text: String get() = tokens.joinToString("") { it.text }
    val firstSentence: Int get() = tokens.first().sentenceIndex
    val lastSentence: Int get() = tokens.last().sentenceIndex
    val wordForms: List<String> get() = tokens.mapNotNull { it.normalized }
}

object Paginator {
    const val DEFAULT_WORDS_PER_PAGE = 250

    /**
     * Splits [text] into pages of roughly [wordsPerPage] words, breaking only between sentences.
     * A single sentence longer than the limit becomes its own oversized page.
     */
    fun paginate(text: TokenizedText, wordsPerPage: Int = DEFAULT_WORDS_PER_PAGE): List<Page> {
        require(wordsPerPage > 0) { "wordsPerPage must be positive" }
        val pages = ArrayList<Page>()
        var pageStart = -1
        var pageWords = 0
        for (range in text.sentenceRanges) {
            val words = range.count { text.tokens[it].isWord }
            if (pageStart >= 0 && pageWords > 0 && pageWords + words > wordsPerPage) {
                pages += Page(pages.size, text.tokens.subList(pageStart, range.first))
                pageStart = -1
                pageWords = 0
            }
            if (pageStart < 0) pageStart = range.first
            pageWords += words
        }
        if (pageStart >= 0) pages += Page(pages.size, text.tokens.subList(pageStart, text.tokens.size))
        return pages
    }
}
