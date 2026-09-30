package net.awkay.spanishreader.core.gloss

import net.awkay.spanishreader.core.text.Page
import net.awkay.spanishreader.core.text.Token
import net.awkay.spanishreader.core.text.TokenizedText
import net.awkay.spanishreader.core.vocab.WordStatus

/** Chooses which (word, sentence) pairs to gloss ahead of time when a lesson is imported. */
object PreGlossPlanner {
    /**
     * One request per distinct (form, sentence) for every word that is not KNOWN or IGNORED, keeping at most
     * [maxSentencesPerForm] sentences per form (the earliest ones). Further occurrences fall back to the
     * form-only cache entry or a live lookup. Request ids are unique.
     * [tokens] restricts planning to part of the text (see [window]); requests follow its order.
     */
    fun plan(
        text: TokenizedText,
        statuses: Map<String, WordStatus>,
        maxSentencesPerForm: Int = 3,
        tokens: List<Token> = text.tokens,
    ): List<GlossRequest> {
        require(maxSentencesPerForm > 0)
        val perForm = HashMap<String, MutableSet<String>>()
        val out = ArrayList<GlossRequest>()
        for (token in tokens) {
            val form = token.normalized ?: continue
            val status = statuses[form] ?: WordStatus.NEW
            if (status == WordStatus.KNOWN || status == WordStatus.IGNORED) continue
            val sentences = perForm.getOrPut(form) { LinkedHashSet() }
            if (sentences.size >= maxSentencesPerForm) continue
            val sentence = text.sentenceFor(token)
            val hash = GlossCache.sentenceHash(sentence)
            if (sentences.add(hash)) out += GlossRequest(form = token.text, sentence = sentence, id = "$form#${out.size}")
        }
        return out
    }

    /**
     * Tokens of [count] pages starting at [fromPage] (clamped to the lesson), so pre-glossing can follow the reader
     * instead of glossing a whole book chapter up front. [count] <= 0 means every page from [fromPage] on.
     */
    fun window(pages: List<Page>, fromPage: Int, count: Int): List<Token> {
        if (pages.isEmpty()) return emptyList()
        val from = fromPage.coerceIn(0, pages.lastIndex)
        val to = if (count <= 0) pages.size else (from + count).coerceAtMost(pages.size)
        return pages.subList(from, to).flatMap { it.tokens }
    }
}
