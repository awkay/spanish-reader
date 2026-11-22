package net.awkay.spanishreader.core.gloss

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LikelyMeaningsTest {
    private fun g(
        form: String, meaning: String, lemma: String = form, pos: String = "noun",
        other: List<String> = emptyList(), lemmaMeaning: String? = null, verb: VerbForm? = null,
    ) = Gloss(form, lemma, pos, meaning, otherMeanings = other, lemmaMeaning = lemmaMeaning, verb = verb)

    @Test
    fun `nothing stored gives an empty result`() {
        val r = LikelyMeanings.build(StoredForm("banco"), emptyList())
        assertTrue(r.isEmpty)
        assertEquals(LikelyMeanings(), r)
    }

    @Test
    fun `meanings merge across case, spacing and punctuation and rank most frequent first`() {
        val own = StoredForm(
            "banco",
            glosses = listOf(
                g("banco", "bench"),
                g("banco", "bank"),
                g("banco", "Bank."),
                g("banco", " bank "),
                g("banco", "bench!"),
                g("banco", "shoal"),
            ),
        )
        val r = LikelyMeanings.build(own, emptyList())
        assertEquals(
            listOf(LikelyMeaning("bank", 3), LikelyMeaning("bench", 2), LikelyMeaning("shoal", 1)),
            r.meanings,
        )
        assertEquals("banco", r.lemma)
        assertEquals(listOf("noun"), r.partsOfSpeech)
        assertTrue(r.relatedForms.isEmpty())
    }

    @Test
    fun `ties keep the order of first appearance and the list is capped`() {
        val own = StoredForm("x", glosses = listOf("a", "b", "c", "d", "e", "f", "g").map { g("x", it) })
        assertEquals(listOf("a", "b", "c", "d", "e"), LikelyMeanings.build(own, emptyList()).meanings.map { it.text })
    }

    @Test
    fun `the saved translation counts only when no gloss said it`() {
        val copied = StoredForm("casa", translation = "House", glosses = listOf(g("casa", "house")))
        assertEquals(listOf(LikelyMeaning("house", 1)), LikelyMeanings.build(copied, emptyList()).meanings)

        val own = StoredForm("casa", lemma = "casa", translation = "home")
        val r = LikelyMeanings.build(own, emptyList())
        assertEquals(listOf(LikelyMeaning("home", 1)), r.meanings)
        assertEquals("casa", r.lemma)
    }

    @Test
    fun `related forms of the same lemma come after the word's own meanings, labeled with their forms`() {
        val own = StoredForm("fui", lemma = "ir", glosses = listOf(g("fui", "I went", lemma = "ir", pos = "verb", lemmaMeaning = "to go")))
        val candidates = listOf(
            StoredForm("fue", lemma = "ir", translation = "went", glosses = listOf(
                g("fue", "he went", lemma = "ir", pos = "verb"),
                g("fue", "was", lemma = "ser", pos = "verb"), // another family: left out
                g("fue", "I went", lemma = "ir", pos = "verb"), // already one of fui's meanings
            )),
            StoredForm("vamos", lemma = "irse", glosses = listOf(g("vamos", "he went", lemma = "ir", pos = "verb", other = listOf("let's go")))),
            StoredForm("ir", glosses = listOf(g("ir", "to go", lemma = "ir", pos = "verb"))), // the lemma itself, no vocab entry
            StoredForm("casa", lemma = "casa", translation = "house"), // unrelated
            StoredForm("Fui", lemma = "ir", translation = "same word"), // the word itself
        )
        val r = LikelyMeanings.build(own, candidates)
        assertEquals(
            listOf(
                LikelyMeaning("I went", 1),
                LikelyMeaning("he went", 2, listOf("fue", "vamos")),
                LikelyMeaning("went", 1, listOf("fue")),
                LikelyMeaning("to go", 1, listOf("ir")),
            ),
            r.meanings,
        )
        assertEquals(listOf("let's go"), r.otherMeanings)
        assertEquals(listOf("fue", "vamos", "ir"), r.relatedForms)
        assertEquals("ir", r.lemma)
        assertEquals("to go", r.lemmaMeaning)
        assertEquals(listOf("verb"), r.partsOfSpeech)
    }

    @Test
    fun `the tapped form anchors a family too, and reflexive se is ignored`() {
        val own = StoredForm("levantar")
        val candidates = listOf(StoredForm("levanto", lemma = "levantarse", translation = "I get up"))
        val r = LikelyMeanings.build(own, candidates)
        assertEquals(listOf(LikelyMeaning("I get up", 1, listOf("levanto"))), r.meanings)
        assertEquals("levantarse", r.lemma)
    }

    @Test
    fun `other meanings are deduped, never repeat a context meaning, and follow own then related`() {
        val own = StoredForm("banco", glosses = listOf(
            g("banco", "bank", other = listOf("bench", "Shoal", "bank")),
            g("banco", "bench", other = listOf("shoal.", "pew")),
        ))
        val candidates = listOf(StoredForm("bancos", lemma = "banco", glosses = listOf(g("bancos", "banks", lemma = "banco", other = listOf("pew", "stool")))))
        val r = LikelyMeanings.build(own, candidates)
        assertEquals(listOf("Shoal", "pew", "stool"), r.otherMeanings)
        assertEquals(listOf(LikelyMeaning("bank"), LikelyMeaning("bench"), LikelyMeaning("banks", 1, listOf("bancos"))), r.meanings)
    }

    @Test
    fun `verb lemmas are tidied, parts of speech ranked, and the lemma comes from the commonest gloss`() {
        val v = VerbForm("prepararse")
        val own = StoredForm("prepárate", lemma = "preparar", glosses = listOf(
            g("prepárate", "get ready", lemma = "Prepararse", pos = "verb", verb = v),
            g("prepárate", "prepare yourself", lemma = "prepararse", pos = "Verb", verb = v),
            g("prepárate", "get ready!", lemma = "preparar", pos = "imperative verb", verb = v),
        ))
        val r = LikelyMeanings.build(own, emptyList())
        assertEquals("preparar", r.lemma)
        assertEquals(listOf("verb", "imperative verb"), r.partsOfSpeech)
        assertEquals(LikelyMeaning("get ready", 2), r.meanings.first())
        assertNull(r.lemmaMeaning)
    }

    @Test
    fun `related forms are limited and the indexed query spellings cover capitals and reflexives`() {
        val own = StoredForm("hablo", lemma = "hablar")
        val many = (1..12).map { StoredForm("f$it", lemma = "hablar", translation = "t$it") }
        val r = LikelyMeanings.build(own, many)
        assertEquals(listOf("t1", "t2", "t3"), r.meanings.map { it.text })
        assertEquals(listOf("f1", "f2", "f3"), r.relatedForms)
        assertEquals(setOf("hablar", "hablo"), LikelyMeanings.familyKeys(own))
        assertEquals(
            setOf("hablar", "Hablar", "hablarse", "Hablarse", "casa", "Casa"),
            LikelyMeanings.lemmaSpellings(listOf("hablar", "casa")),
        )
    }
}
