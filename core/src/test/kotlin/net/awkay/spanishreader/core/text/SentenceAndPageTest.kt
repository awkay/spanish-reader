package net.awkay.spanishreader.core.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SentenceAndPageTest {
    private fun sentences(s: String) = Tokenizer.tokenize(s).sentences()

    @Test
    fun `splits on terminal punctuation followed by space`() {
        assertEquals(
            listOf("Hola.", "¿Cómo estás?", "¡Muy bien!", "Pues…", "Nada."),
            sentences("Hola. ¿Cómo estás? ¡Muy bien! Pues… Nada."),
        )
    }

    @Test
    fun `does not split on abbreviations or initials`() {
        assertEquals(
            listOf("El Sr. García y la Dra. Pérez llegaron.", "Trajeron frutas, verduras, etc. y pan.", "Firmado J. Pérez."),
            sentences("El Sr. García y la Dra. Pérez llegaron. Trajeron frutas, verduras, etc. y pan. Firmado J. Pérez."),
        )
    }

    @Test
    fun `etc splits when followed by a capital`() {
        assertEquals(listOf("Compré pan, leche, etc.", "Luego volví."), sentences("Compré pan, leche, etc. Luego volví."))
    }

    @Test
    fun `closing quote stays with its sentence`() {
        assertEquals(listOf("«¿Vienes?»", "Nadie contestó."), sentences("«¿Vienes?» Nadie contestó."))
        assertEquals(listOf("Dijo \"basta.\"", "Se fue."), sentences("Dijo \"basta.\" Se fue."))
    }

    @Test
    fun `dialogue tag after question stays in sentence`() {
        assertEquals(
            listOf("—¿Vienes? —preguntó ella.", "—Sí."),
            sentences("—¿Vienes? —preguntó ella.\n—Sí."),
        )
    }

    @Test
    fun `line and paragraph breaks end sentences`() {
        assertEquals(listOf("Título", "Primer párrafo sin punto", "Segundo."), sentences("Título\nPrimer párrafo sin punto\n\nSegundo."))
    }

    @Test
    fun `no split without following space`() {
        assertEquals(listOf("Ver www.ejemplo.com hoy."), sentences("Ver www.ejemplo.com hoy."))
    }

    @Test
    fun `sentence lookup for a token`() {
        val t = Tokenizer.tokenize("Me llamo Ana. Vivo en México.")
        val mexico = t.words.first { it.text == "México" }
        assertEquals("Vivo en México.", t.sentenceFor(mexico))
        assertEquals(2, t.sentenceCount)
        assertEquals(0, t.tokens.first().sentenceIndex)
    }

    @Test
    fun `leading whitespace does not create an empty sentence`() {
        assertEquals(listOf("Hola."), sentences("\n\n  Hola.\n"))
    }

    private fun text(sentenceCount: Int, wordsPerSentence: Int) =
        (1..sentenceCount).joinToString(" ") { s -> (1..wordsPerSentence).joinToString(" ") { "palabra" } + "." }

    @Test
    fun `pagination breaks only at sentence boundaries`() {
        val t = Tokenizer.tokenize(text(sentenceCount = 10, wordsPerSentence = 7))
        val pages = Paginator.paginate(t, wordsPerPage = 20)
        assertEquals(listOf(14, 14, 14, 14, 14), pages.map { it.wordCount })
        assertEquals(t.source, pages.joinToString("") { it.text })
        pages.zipWithNext().forEach { (a, b) -> assertTrue(a.lastSentence < b.firstSentence) }
        assertEquals(pages.indices.toList(), pages.map { it.index })
    }

    @Test
    fun `long sentence may exceed page size`() {
        val long = (1..30).joinToString(" ") { "muy" } + " largo."
        val t = Tokenizer.tokenize("Corta. $long Otra corta.")
        val pages = Paginator.paginate(t, wordsPerPage = 10)
        assertEquals(listOf(1, 31, 2), pages.map { it.wordCount })
    }

    @Test
    fun `default page size and edge cases`() {
        val t = Tokenizer.tokenize(text(sentenceCount = 100, wordsPerSentence = 10))
        val pages = Paginator.paginate(t)
        assertTrue(pages.all { it.wordCount <= 250 })
        assertEquals(1000, pages.sumOf { it.wordCount })
        assertEquals(emptyList(), Paginator.paginate(Tokenizer.tokenize("")))
        assertFailsWith<IllegalArgumentException> { Paginator.paginate(t, 0) }
        assertEquals(listOf("palabra"), Paginator.paginate(Tokenizer.tokenize("Palabra."), 5).single().wordForms)
    }
}
