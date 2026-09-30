package net.awkay.spanishreader.core.text

import java.text.Normalizer

/** Finds the words of an expression (e.g. "echar de menos") inside a sentence, allowing a few words in between. */
object PhraseLocator {
    /**
     * Word tokens of [sentence] that make up [phrase], in order, or null if it isn't there. Consecutive phrase words
     * may be separated by at most [maxGap] other words ("me echa mucho de menos"). Matching ignores case and accents.
     * The tightest match wins.
     */
    fun locate(sentence: List<Token>, phrase: String, maxGap: Int = 3): List<Token>? {
        val want = Tokenizer.tokenize(phrase).words.map { fold(it.normalized!!) }
        if (want.isEmpty()) return null
        val words = sentence.filter { it.isWord }
        val keys = words.map { fold(it.normalized!!) }
        var best: List<Int>? = null
        for (start in keys.indices) {
            if (keys[start] != want[0]) continue
            val picked = mutableListOf(start)
            var pos = start
            for (w in want.drop(1)) {
                val next = (pos + 1..minOf(keys.lastIndex, pos + 1 + maxGap)).firstOrNull { keys[it] == w } ?: break
                picked += next
                pos = next
            }
            if (picked.size == want.size && (best == null || picked.last() - picked.first() < best.last() - best.first())) best = picked
        }
        return best?.map { words[it] }
    }

    private fun fold(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
}
