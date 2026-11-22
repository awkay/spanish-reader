package net.awkay.spanishreader.gloss

import net.awkay.spanishreader.core.gloss.GlossCache
import net.awkay.spanishreader.core.gloss.LikelyMeanings
import net.awkay.spanishreader.core.gloss.StoredForm
import net.awkay.spanishreader.data.RoomGlossCache
import net.awkay.spanishreader.data.VocabRepository

/**
 * "What's on hand" for a word, from the device only (indexed queries, no network): its own cached glosses from any
 * sentence and vocabulary entry, plus related forms of the same lemma (word-family rules) and their glosses.
 */
class OnHandMeanings(private val vocab: VocabRepository, private val glosses: RoomGlossCache) {
    suspend fun forWord(written: String): LikelyMeanings {
        val key = GlossCache.formKey(written)
        val entry = vocab.get(key)
        val own = StoredForm(key, entry?.lemma, entry?.translation, glosses.glossesByForm(listOf(key))[key].orEmpty())
        val keys = LikelyMeanings.familyKeys(own)
        val entries = vocab.byLemmaOrForm(LikelyMeanings.lemmaSpellings(keys), keys, CANDIDATE_LIMIT).filter { it.form != key }
        // The lemma itself may have glosses without a vocabulary entry ("ir" glossed but never tapped).
        val bare = keys.filter { k -> k != key && entries.none { it.form == k } }
        val stored = glosses.glossesByForm(entries.map { it.form } + bare)
        val candidates = entries.map { StoredForm(it.form, it.lemma, it.translation, stored[it.form].orEmpty()) } +
            bare.mapNotNull { k -> stored[k]?.takeIf { it.isNotEmpty() }?.let { StoredForm(k, glosses = it) } }
        return LikelyMeanings.build(own, candidates)
    }

    private companion object {
        /** Rows read before the family filter; [LikelyMeanings.build] keeps at most MAX_RELATED_FORMS. */
        const val CANDIDATE_LIMIT = 40
    }
}
