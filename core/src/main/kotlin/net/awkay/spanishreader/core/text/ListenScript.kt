package net.awkay.spanishreader.core.text

/** One spoken unit of listen mode: a sentence that contains at least one word. */
data class ListenSentence(val sentenceIndex: Int, val text: String, val pageIndex: Int)

object ListenScript {
    /** Speakable sentences of [text] in order, each tagged with the page (from [pages]) it appears on. */
    fun build(text: TokenizedText, pages: List<Page>): List<ListenSentence> {
        val pageOfSentence = HashMap<Int, Int>()
        for (page in pages) for (t in page.tokens) pageOfSentence.putIfAbsent(t.sentenceIndex, page.index)
        return text.sentenceRanges.indices.mapNotNull { s ->
            if (text.sentenceTokens(s).none { it.isWord }) null
            else ListenSentence(s, text.sentenceText(s), pageOfSentence[s] ?: 0)
        }
    }
}
