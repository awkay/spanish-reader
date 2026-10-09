package net.awkay.spanishreader.core.vocab

import net.awkay.spanishreader.core.vocab.WordStatus.FAMILIAR
import net.awkay.spanishreader.core.vocab.WordStatus.IGNORED
import net.awkay.spanishreader.core.vocab.WordStatus.KNOWN
import net.awkay.spanishreader.core.vocab.WordStatus.LEVEL_1
import net.awkay.spanishreader.core.vocab.WordStatus.NEW
import net.awkay.spanishreader.core.vocab.WordStatus.RECOGNIZED
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WordFamiliesTest {
    private fun entry(form: String, status: WordStatus, lemma: String? = null) =
        VocabEntry(form = form, lemma = lemma, status = status, firstSeenMillis = 1, lastSeenMillis = 1)

    @Test
    fun `lemmas compare without case, reflexive se or typographic apostrophes`() {
        assertEquals("levantar", WordFamilies.normalizeLemma(" Levantarse "))
        assertEquals("reír", WordFamilies.normalizeLemma("reírse"))
        assertEquals("ir", WordFamilies.normalizeLemma("irse"))
        assertEquals("ver", WordFamilies.normalizeLemma("verse"))
        assertEquals("casa", WordFamilies.normalizeLemma("casa"))
        assertEquals("clase", WordFamilies.normalizeLemma("clase"))
        assertEquals("o'clock", WordFamilies.normalizeLemma("O’clock"))
        // NFD input (e + combining acute) compares equal to the precomposed form.
        assertEquals("café", WordFamilies.normalizeLemma("café"))
        assertNull(WordFamilies.normalizeLemma("  "))
        assertNull(WordFamilies.normalizeLemma(null))
    }

    @Test
    fun `a family takes the highest status of its members, by lemma or by own form`() {
        val families = WordFamilies.familyStatuses(
            listOf(
                entry("hablo", LEVEL_1, lemma = "hablar"),
                entry("hablas", FAMILIAR, lemma = "hablar"),
                entry("casa", KNOWN), // no stored lemma: its own form anchors the family
                entry("me", RECOGNIZED, lemma = "levantarse"),
            ),
        )
        assertEquals(FAMILIAR, families["hablar"])
        assertEquals(KNOWN, families["casa"])
        assertEquals(RECOGNIZED, families["levantar"])
    }

    @Test
    fun `new and ignored words don't make a family`() {
        val families = WordFamilies.familyStatuses(
            listOf(entry("maría", IGNORED, lemma = "maría"), entry("comen", NEW, lemma = "comer")),
        )
        assertEquals(emptyMap(), families)
    }

    @Test
    fun `a new spelling inherits its family, but its own status wins`() {
        val families = mapOf("hablar" to KNOWN, "levantar" to RECOGNIZED)
        assertEquals(KNOWN, WordFamilies.effectiveStatus(null, "hablar", families))
        assertEquals(KNOWN, WordFamilies.effectiveStatus(NEW, "Hablar", families))
        assertEquals(RECOGNIZED, WordFamilies.effectiveStatus(null, "levantarse", families))
        assertEquals(LEVEL_1, WordFamilies.effectiveStatus(LEVEL_1, "hablar", families))
        assertEquals(IGNORED, WordFamilies.effectiveStatus(IGNORED, "hablar", families))
        assertEquals(NEW, WordFamilies.effectiveStatus(null, "comer", families))
        assertEquals(NEW, WordFamilies.effectiveStatus(null, null, families))
    }

    @Test
    fun `effective statuses fill in only forms that are new and have a family`() {
        val statuses = mapOf("hablo" to LEVEL_1, "hablamos" to NEW)
        val lemmas = mapOf("hablaban" to "hablar", "hablamos" to "hablar", "hablo" to "hablar", "comí" to "comer")
        val families = mapOf("hablar" to KNOWN)
        val out = WordFamilies.effectiveStatuses(listOf("hablaban", "hablamos", "hablo", "comí", "y"), statuses, lemmas, families)
        assertEquals(mapOf("hablo" to LEVEL_1, "hablamos" to KNOWN, "hablaban" to KNOWN), out)
        assertEquals(KNOWN, WordFamilies.inherited("hablaban", null, lemmas, families))
        assertNull(WordFamilies.inherited("hablo", LEVEL_1, lemmas, families))
        assertNull(WordFamilies.inherited("comí", null, lemmas, families))
    }

    @Test
    fun `tapping or finishing a page uses the inherited status`() {
        val tapped = VocabularyRules.onTap(null, "hablaban", now = 5, contextSentence = "Ellos hablaban.", inherited = KNOWN)
        assertEquals(KNOWN, tapped.status)
        assertEquals(LEVEL_1, VocabularyRules.onTap(null, "comí", now = 5).status)
        assertEquals(RECOGNIZED, VocabularyRules.onTap(entry("hablamos", NEW), "hablamos", now = 5, inherited = RECOGNIZED).status)

        val added = VocabularyRules.applyPageFinished(
            listOf("hablaban", "comí", "hablaban"), emptyMap(), now = 9, inherited = mapOf("hablaban" to KNOWN),
        )
        assertEquals(listOf("hablaban" to KNOWN, "comí" to LEVEL_1), added.map { it.form to it.status })
    }
}
