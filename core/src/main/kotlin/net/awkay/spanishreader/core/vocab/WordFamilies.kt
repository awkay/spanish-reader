package net.awkay.spanishreader.core.vocab

import java.text.Normalizer
import java.util.Locale

/**
 * Word families: spellings that share a dictionary form (lemma). A spelling the learner hasn't met yet takes the
 * status of its family, so `hablaban` isn't blue when `hablo` is already known. Statuses stay keyed by written form;
 * the family only fills in for forms that are still NEW.
 */
object WordFamilies {
    private val SPANISH = Locale.forLanguageTag("es")
    private val REFLEXIVE = Regex("([aeií]r)se$")

    /** Comparable lemma: NFC, lowercase, typographic apostrophe folded, reflexive infinitive without `se`. */
    fun normalizeLemma(lemma: String?): String? {
        val l = lemma?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val n = Normalizer.normalize(l, Normalizer.Form.NFC).replace('’', '\'').lowercase(SPANISH)
        return n.replace(REFLEXIVE, "$1")
    }

    /**
     * The status of each family among [entries]: the highest LEVEL_1..KNOWN status of an entry whose lemma is that
     * family, or whose own form is (a known `hablar` anchors the `hablar` family even without a stored lemma).
     * NEW and IGNORED entries don't count.
     */
    fun familyStatuses(entries: Collection<VocabEntry>): Map<String, WordStatus> {
        val out = HashMap<String, WordStatus>()
        fun offer(key: String?, s: WordStatus) {
            if (key == null) return
            val cur = out[key]
            if (cur == null || s.code > cur.code) out[key] = s
        }
        for (e in entries) {
            if (e.status.code !in 1..5) continue
            offer(normalizeLemma(e.lemma), e.status)
            offer(normalizeLemma(e.form), e.status)
        }
        return out
    }

    /** [own] unless it is NEW or absent; then the status of the family of [lemma]; else NEW. */
    fun effectiveStatus(own: WordStatus?, lemma: String?, families: Map<String, WordStatus>): WordStatus =
        if (own != null && own != WordStatus.NEW) own
        else normalizeLemma(lemma)?.let { families[it] } ?: WordStatus.NEW

    /**
     * The status a family lends to [form]: non-null only when the form itself is NEW or absent and its lemma (from
     * [lemmas], by form) belongs to a family in [families].
     */
    fun inherited(
        form: String, own: WordStatus?, lemmas: Map<String, String>, families: Map<String, WordStatus>,
    ): WordStatus? =
        if (own != null && own != WordStatus.NEW) null else normalizeLemma(lemmas[form])?.let { families[it] }

    /** [statuses] with every NEW/absent form among [forms] that has a family filled in with the family's status. */
    fun effectiveStatuses(
        forms: Collection<String>,
        statuses: Map<String, WordStatus>,
        lemmas: Map<String, String>,
        families: Map<String, WordStatus>,
    ): Map<String, WordStatus> {
        val out = HashMap(statuses)
        for (f in forms) inherited(f, statuses[f], lemmas, families)?.let { out[f] = it }
        return out
    }
}
