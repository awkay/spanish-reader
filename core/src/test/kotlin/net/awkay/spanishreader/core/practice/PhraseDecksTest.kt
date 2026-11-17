package net.awkay.spanishreader.core.practice

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhraseDecksTest {
    @Test
    fun `every card has one blank, an answer, and a unique id`() {
        for (deck in PhraseDeck.entries) {
            val cards = deck.cards
            assertTrue(cards.size >= 60, "${deck.id}: ${cards.size} cards")
            assertEquals(cards.size, cards.map { it.id }.toSet().size)
            for (c in cards) {
                assertEquals(2, c.spanish.split(Drill.BLANK).size, c.id)
                assertTrue(c.answer.isNotBlank() && c.english.isNotBlank(), c.id)
                assertTrue(c.answer.trim() == c.answer && "_" !in c.answer, c.id)
                // A blank that opens the sentence holds a capitalized answer, and only then.
                val opens = c.spanish.trimStart('¿', '¡').startsWith(Drill.BLANK)
                assertEquals(opens, c.answer[0].isUpperCase(), "${c.id}: ${c.spanish} / ${c.answer}")
            }
        }
    }

    @Test
    fun `verb cards name the verb and a known tense, question cards neither`() {
        for (c in PhraseDecks.VERBS) {
            assertNotNull(c.verb, c.id)
            assertNotNull(VerbTenses.byId(c.tense!!), "${c.id}: ${c.tense}")
        }
        assertTrue(PhraseDecks.QUESTIONS.all { it.verb == null && it.tense == null && it.hint == null })
        val tener = PhraseDecks.VERBS.first { it.verb == "tener" }
        assertEquals("tener · present", tener.hint)
        assertEquals("Tengo dos hijos.", tener.filled)
        // The everyday verbs Tony asked for are all there.
        val verbs = PhraseDecks.VERBS.map { it.verb }.toSet()
        for (v in listOf("hacer", "tener", "haber", "tomar", "ir", "conseguir", "pedir", "ser", "estar", "encontrar")) assertTrue(v in verbs, v)
    }

    @Test
    fun `a session is the whole deck shuffled, and decks and modes resolve by id`() {
        val s = PhraseDecks.session(PhraseDeck.QUESTIONS, Random(1))
        assertEquals(PhraseDecks.QUESTIONS.toSet(), s.toSet())
        assertTrue(s != PhraseDecks.QUESTIONS)
        assertEquals(PhraseDeck.VERBS, PhraseDeck.byId("verbs"))
        assertEquals(PhraseMode.LISTEN, PhraseMode.byId("listen"))
        assertNull(PhraseDeck.byId("x"))
    }
}
