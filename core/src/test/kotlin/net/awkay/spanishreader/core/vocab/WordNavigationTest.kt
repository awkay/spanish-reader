package net.awkay.spanishreader.core.vocab

import net.awkay.spanishreader.core.text.Paginator
import net.awkay.spanishreader.core.text.Token
import net.awkay.spanishreader.core.text.Tokenizer
import net.awkay.spanishreader.core.vocab.NavDirection.NEXT
import net.awkay.spanishreader.core.vocab.NavDirection.PREVIOUS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WordNavigationTest {
    private val tokens = Tokenizer.tokenize("El perro, Juan y 3 gatos comen. ¡Qué bien!").tokens

    private fun at(word: String): Int = tokens.first { it.text == word }.index

    private fun step(from: String, dir: NavDirection, statuses: Map<String, WordStatus>, page: List<Token> = tokens) =
        WordNavigation.step(page, statuses, at(from), dir)

    @Test
    fun `skips known, ignored, digits and punctuation`() {
        val statuses = mapOf(
            "el" to WordStatus.KNOWN, "perro" to WordStatus.LEVEL_1, "juan" to WordStatus.IGNORED,
            "y" to WordStatus.KNOWN, "comen" to WordStatus.KNOWN, "qué" to WordStatus.KNOWN,
        )
        // "gatos" has no entry, so it is NEW.
        assertEquals(at("gatos"), step("perro", NEXT, statuses))
        assertEquals(at("perro"), step("gatos", PREVIOUS, statuses))
        assertEquals(at("bien"), step("gatos", NEXT, statuses))
    }

    @Test
    fun `NEW and every learning level count`() {
        for (s in listOf(WordStatus.NEW, WordStatus.LEVEL_1, WordStatus.RECOGNIZED, WordStatus.FAMILIAR, WordStatus.LEARNED)) {
            val statuses = mapOf("el" to WordStatus.KNOWN, "perro" to s, "juan" to WordStatus.KNOWN)
            assertEquals(at("perro"), step("El", NEXT, statuses), "status $s")
            assertEquals(at("perro"), step("Juan", PREVIOUS, statuses), "status $s")
        }
    }

    @Test
    fun `null at the page edges`() {
        assertNull(step("El", PREVIOUS, emptyMap()))
        assertNull(step("bien", NEXT, emptyMap()))
        // Only known words left after "gatos".
        val statuses = mapOf("comen" to WordStatus.KNOWN, "qué" to WordStatus.IGNORED, "bien" to WordStatus.KNOWN)
        assertNull(step("gatos", NEXT, statuses))
    }

    @Test
    fun `starting from a word that is not highlighted`() {
        val statuses = mapOf("perro" to WordStatus.KNOWN, "juan" to WordStatus.IGNORED)
        assertEquals(at("y"), step("perro", NEXT, statuses))
        assertEquals(at("El"), step("perro", PREVIOUS, statuses))
        assertEquals(at("El"), step("Juan", PREVIOUS, statuses))
    }

    @Test
    fun `stays on the page`() {
        val pages = Paginator.paginate(Tokenizer.tokenize("Uno dos. Tres cuatro."), wordsPerPage = 2)
        assertEquals(2, pages.size)
        val first = pages[0].tokens
        val dos = first.first { it.text == "dos" }.index
        assertNull(WordNavigation.step(first, emptyMap(), dos, NEXT))
        val tres = pages[1].tokens.first { it.text == "Tres" }.index
        assertNull(WordNavigation.step(pages[1].tokens, emptyMap(), tres, PREVIOUS))
        // A current word that isn't on the given page.
        assertNull(WordNavigation.step(first, emptyMap(), tres, PREVIOUS))
    }
}
