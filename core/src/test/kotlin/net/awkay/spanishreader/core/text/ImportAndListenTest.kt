package net.awkay.spanishreader.core.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImportAndListenTest {
    @Test
    fun `hard wrapped lines are joined but sentence ends are kept`() {
        val raw = "Había una vez un niño que\nvivía en el campo.\nTenía un perro."
        assertEquals("Había una vez un niño que vivía en el campo.\nTenía un perro.", ImportCleaner.clean(raw))
    }

    @Test
    fun `paragraphs, CRLF, invisible chars and extra spaces are normalized`() {
        val raw = "﻿Hola   mundo.\r\n\r\n\r\n  Adiós​\tamigo.  \r\n"
        assertEquals("Hola mundo.\n\nAdiós amigo.", ImportCleaner.clean(raw))
    }

    @Test
    fun `dialogue and list lines are not joined`() {
        val raw = "Ella dijo\n—¿Vienes?\nLista\n1. uno\n2) dos"
        assertEquals(raw, ImportCleaner.clean(raw))
    }

    @Test
    fun `hyphenated word across a break is rejoined`() {
        assertEquals("Usa la computadora hoy.", ImportCleaner.clean("Usa la compu-\ntadora hoy."))
    }

    @Test
    fun `joining can be turned off`() {
        assertEquals("un niño que\nvivía", ImportCleaner.clean("un niño que\nvivía", joinWrappedLines = false))
    }

    @Test
    fun `title suggestions`() {
        assertEquals("El perro", ImportCleaner.suggestTitle("\n  El perro\nEra grande."))
        assertEquals("Untitled", ImportCleaner.suggestTitle("  \n "))
        val long = "Esta es una frase muy larga que sin duda supera el límite de sesenta caracteres permitido"
        val title = ImportCleaner.suggestTitle(long)
        assertTrue(title.length <= 61 && title.endsWith("…"), title)
        assertTrue(long.startsWith(title.dropLast(1)))
    }

    @Test
    fun `url detection`() {
        assertTrue(ImportCleaner.isJustUrl(" https://elpais.com/a/b "))
        assertFalse(ImportCleaner.isJustUrl("Mira https://elpais.com"))
        assertFalse(ImportCleaner.isJustUrl("hola"))
    }

    @Test
    fun `youtube link detection`() {
        listOf(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ", "https://youtu.be/dQw4w9WgXcQ?si=x", "youtube.com/shorts/dQw4w9WgXcQ",
            "https://m.youtube.com/watch?feature=share&v=dQw4w9WgXcQ", "  https://www.youtube.com/live/dQw4w9WgXcQ  ",
        ).forEach { assertTrue(ImportCleaner.isYouTubeUrl(it), it) }
        listOf(
            "https://example.com/watch?v=dQw4w9WgXcQ", "https://www.youtube.com/playlist?list=PL1", "Hola amigos",
            "mira https://youtu.be/dQw4w9WgXcQ", "https://youtu.be/short",
        ).forEach { assertFalse(ImportCleaner.isYouTubeUrl(it), it) }
    }

    @Test
    fun `listen script skips wordless sentences and maps pages`() {
        val text = Tokenizer.tokenize("Uno dos tres. 123. Cuatro cinco seis. Siete.")
        val pages = Paginator.paginate(text, wordsPerPage = 3)
        val script = ListenScript.build(text, pages)
        assertEquals(listOf("Uno dos tres.", "Cuatro cinco seis.", "Siete."), script.map { it.text })
        assertEquals(listOf(0, 1, 2), script.map { it.pageIndex })
        assertTrue(script.map { it.sentenceIndex }.zipWithNext().all { (a, b) -> b > a })
    }
}
