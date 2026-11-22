package net.awkay.spanishreader.core.gloss

import kotlinx.serialization.Serializable
import net.awkay.spanishreader.core.vocab.WordFamilies
import java.text.Normalizer

/**
 * One stored spelling and what the device holds for it: its vocabulary lemma and saved translation (null when it has
 * no vocabulary entry) and its cached glosses from any sentence, most recently stored first.
 */
@Serializable
data class StoredForm(
    val form: String,
    val lemma: String? = null,
    val translation: String? = null,
    val glosses: List<Gloss> = emptyList(),
)

/**
 * A meaning the device already has for a word. [count] is how many sentences (glosses, or a saved translation that
 * no gloss gave) said it. [forms] is empty for the tapped word's own meanings; for a meaning that only related forms
 * of the same lemma gave, it lists them ("fui", "fue").
 */
@Serializable
data class LikelyMeaning(val text: String, val count: Int = 1, val forms: List<String> = emptyList()) {
    val related: Boolean get() = forms.isNotEmpty()
}

/**
 * "What's on hand" for a tapped word, built locally from stored glosses and vocabulary without asking the AI: shown
 * for a KNOWN word whose sentence has no gloss yet. [meanings] holds the word's own meanings (most frequent first),
 * then the meanings only its related forms gave; [otherMeanings] are the glosses' other dictionary senses not
 * already listed. [relatedForms] are the related spellings that contributed something shown.
 */
@Serializable
data class LikelyMeanings(
    val lemma: String? = null,
    val lemmaMeaning: String? = null,
    val partsOfSpeech: List<String> = emptyList(),
    val meanings: List<LikelyMeaning> = emptyList(),
    val otherMeanings: List<String> = emptyList(),
    val relatedForms: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = meanings.isEmpty() && otherMeanings.isEmpty()

    companion object {
        const val MAX_OWN = 5
        const val MAX_RELATED = 3
        const val MAX_OTHER = 5
        const val MAX_POS = 3

        /** Related spellings considered, in the order given (callers pass the most recently seen first). */
        const val MAX_RELATED_FORMS = 8

        private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
        private val INFINITIVE = Regex("[aeií]r$")

        /** Comparison key for a meaning: NFC, lowercase, punctuation and spacing folded ("Bank." = "bank"). */
        fun meaningKey(text: String?): String? {
            val t = text ?: return null
            val k = Normalizer.normalize(t, Normalizer.Form.NFC).lowercase().replace(NON_WORD, " ").trim()
            return k.ifEmpty { null }
        }

        /**
         * The word families [own] belongs to, as [WordFamilies.normalizeLemma] keys: its vocabulary lemma, the lemmas
         * of its glosses, and its own form (a tapped `hablar` gathers `hablo`).
         */
        fun familyKeys(own: StoredForm): Set<String> = buildSet {
            WordFamilies.normalizeLemma(own.lemma)?.let(::add)
            WordFamilies.normalizeLemma(own.form)?.let(::add)
            own.glosses.forEach { g -> WordFamilies.normalizeLemma(g.tidy().lemma)?.let(::add) }
        }

        /**
         * Spellings a stored lemma column may hold for [keys], for an indexed `lemma IN (...)` query: as is,
         * capitalized, and infinitives with a reflexive `se`. Results still go through [isRelated].
         */
        fun lemmaSpellings(keys: Collection<String>): Set<String> = buildSet {
            for (k in keys) {
                val forms = if (INFINITIVE.containsMatchIn(k)) listOf(k, k + "se") else listOf(k)
                for (f in forms) {
                    add(f)
                    add(f.replaceFirstChar { it.uppercaseChar() })
                }
            }
        }

        /** Whether [candidate]'s vocabulary entry (by its lemma or own form) belongs to one of [keys]. */
        private fun entryRelated(candidate: StoredForm, keys: Set<String>): Boolean =
            WordFamilies.normalizeLemma(candidate.lemma) in keys || WordFamilies.normalizeLemma(candidate.form) in keys

        /** Whether [candidate] is the same word family as [keys]: by its entry, or by one of its glosses' lemmas. */
        fun isRelated(candidate: StoredForm, keys: Set<String>): Boolean =
            entryRelated(candidate, keys) || candidate.glosses.any { WordFamilies.normalizeLemma(it.tidy().lemma) in keys }

        /**
         * Aggregates [own] and the [candidates] that are related forms (same lemma rules as word families; at most
         * [MAX_RELATED_FORMS], in the order given). A related form contributes only its glosses whose lemma is in the
         * family. A saved translation counts only when none of that form's glosses already said it (it is usually
         * copied from the first one).
         */
        fun build(own: StoredForm, candidates: List<StoredForm>): LikelyMeanings {
            val keys = familyKeys(own)
            val ownKey = meaningKey(own.form)
            val ownGlosses = own.glosses.map { it.tidy() }
            val related = candidates
                .filter { meaningKey(it.form) != ownKey && isRelated(it, keys) }
                .take(MAX_RELATED_FORMS)
                .map { c -> c to c.glosses.map { it.tidy() }.filter { WordFamilies.normalizeLemma(it.lemma) in keys } }

            val ownTally = Tally()
            ownGlosses.forEach { ownTally.add(it.meaningInContext) }
            if (!ownTally.has(own.translation)) ownTally.add(own.translation)

            val relatedTally = Tally()
            for ((c, glosses) in related) {
                val said = glosses.mapNotNull { meaningKey(it.meaningInContext) }.toSet()
                for (g in glosses) if (!ownTally.has(g.meaningInContext)) relatedTally.add(g.meaningInContext, c.form)
                if (entryRelated(c, keys) && meaningKey(c.translation) !in said && !ownTally.has(c.translation)) {
                    relatedTally.add(c.translation, c.form)
                }
            }

            val otherTally = Tally()
            val sources = ownGlosses.map { it to null } + related.flatMap { (c, gs) -> gs.map { it to c.form } }
            for ((g, form) in sources) for (m in g.otherMeanings) {
                if (!ownTally.has(m) && !relatedTally.has(m)) otherTally.add(m, form)
            }

            val ownShown = ownTally.ranked().take(MAX_OWN).map { LikelyMeaning(it.display(), it.count) }
            val relatedItems = relatedTally.ranked().take(MAX_RELATED)
            val otherItems = otherTally.ranked().take(MAX_OTHER)
            val contributed = (relatedItems + otherItems).flatMap { it.forms }.toSet()

            val relatedGlosses = related.flatMap { it.second }
            val lemma = pick(ownGlosses.map { it.lemma })
                ?: own.lemma?.trim()?.ifEmpty { null }
                ?: pick(relatedGlosses.map { it.lemma })
                ?: pick(related.map { it.first.lemma })
            val lemmaKey = WordFamilies.normalizeLemma(lemma)
            fun lemmaMeaningOf(gs: List<Gloss>): String? {
                val t = Tally()
                gs.filter { WordFamilies.normalizeLemma(it.lemma) == lemmaKey }.forEach { t.add(it.lemmaMeaning) }
                return t.ranked().firstOrNull()?.display()
            }
            fun posOf(gs: List<Gloss>): List<String> {
                val t = Tally()
                gs.forEach { t.add(it.partOfSpeech) }
                return t.ranked().take(MAX_POS).map { it.display() }
            }

            return LikelyMeanings(
                lemma = lemma,
                lemmaMeaning = lemmaMeaningOf(ownGlosses) ?: lemmaMeaningOf(relatedGlosses),
                partsOfSpeech = posOf(ownGlosses).ifEmpty { posOf(relatedGlosses) },
                meanings = ownShown + relatedItems.map { LikelyMeaning(it.display(), it.count, it.forms.toList()) },
                otherMeanings = otherItems.map { it.display() },
                relatedForms = related.map { it.first.form }.filter { it in contributed },
            )
        }

        /** The most frequent of [values] by lemma key (first seen wins a tie), in its most common spelling. */
        private fun pick(values: List<String?>): String? {
            val t = Tally(WordFamilies::normalizeLemma)
            values.forEach { t.add(it) }
            return t.ranked().firstOrNull()?.display()
        }
    }
}

/** Counts strings by a comparison key, remembering first appearance, spellings and the forms that said them. */
private class Tally(private val keyOf: (String?) -> String? = LikelyMeanings::meaningKey) {
    class Item(val first: Int) {
        var count = 0
        val spellings = LinkedHashMap<String, Int>()
        val forms = LinkedHashSet<String>()

        /** The most common spelling; the first seen on a tie. */
        fun display(): String {
            var best: Map.Entry<String, Int>? = null
            for (e in spellings.entries) if (best == null || e.value > best.value) best = e
            return best!!.key
        }
    }

    private val items = LinkedHashMap<String, Item>()

    fun has(text: String?): Boolean = keyOf(text)?.let { it in items } ?: false

    fun add(text: String?, form: String? = null) {
        val t = text?.trim() ?: return
        val key = keyOf(t) ?: return
        val item = items.getOrPut(key) { Item(items.size) }
        item.count++
        item.spellings[t] = (item.spellings[t] ?: 0) + 1
        form?.let { item.forms += it }
    }

    /** Most frequent first, then in order of first appearance. */
    fun ranked(): List<Item> = items.values.sortedWith(compareByDescending<Item> { it.count }.thenBy { it.first })
}
