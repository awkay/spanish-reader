package net.awkay.spanishreader.core.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TokenizerTest {
    private fun words(s: String) = Tokenizer.tokenize(s).words.map { it.text }
    private fun roundTrip(s: String) = assertEquals(s, Tokenizer.tokenize(s).tokens.joinToString("") { it.text })

    @Test
    fun `round trip reproduces input exactly`() {
        listOf(
            "",
            "¿Qué?",
            "¡Hola, mundo!\n\n—Sí —dijo él—, claro…",
            "  leading and trailing  \n",
            "Tengo 1.500 pesos y 3,14 razones.",
            "«Entre comillas» y \"otras\" y “curvas”.",
            "emoji 😀 y tabs\tand\r\nCRLF",
            "decomposed: café y ñ",
        ).forEach(::roundTrip)
    }

    @Test
    fun `token offsets match source`() {
        val t = Tokenizer.tokenize("¿Dónde está el baño?")
        t.tokens.forEach { assertEquals(it.text, t.source.substring(it.start, it.end)) }
        assertEquals(t.tokens.indices.toList(), t.tokens.map { it.index })
    }

    @Test
    fun `inverted punctuation is separate from words`() {
        val t = Tokenizer.tokenize("¿Qué?")
        assertEquals(listOf("¿", "Qué", "?"), t.tokens.map { it.text })
        assertEquals(listOf(TokenKind.PUNCT, TokenKind.WORD, TokenKind.PUNCT), t.tokens.map { it.kind })
        assertEquals(listOf("Hola"), words("¡Hola!"))
    }

    @Test
    fun `normalized form is lowercase and keeps accents`() {
        val t = Tokenizer.tokenize("Sí, SI Ñandú Él")
        assertEquals(listOf("sí", "si", "ñandú", "él"), t.words.map { it.normalized })
        assertTrue(t.tokens.filter { !it.isWord }.all { it.normalized == null })
    }

    @Test
    fun `decomposed accents normalize to composed form`() {
        assertEquals(listOf("café"), Tokenizer.tokenize("Café").words.map { it.normalized })
    }

    @Test
    fun `clitic compounds are one word`() {
        assertEquals(listOf("Dámelo", "ahora", "cómpratelo"), words("Dámelo ahora, cómpratelo."))
    }

    @Test
    fun `numbers are not words`() {
        val t = Tokenizer.tokenize("Tengo 25 años y 1.500,50 pesos en 2024.")
        assertEquals(listOf("Tengo", "años", "y", "pesos", "en"), t.words.map { it.text })
        assertTrue(t.tokens.any { it.text == "1.500,50" && it.kind == TokenKind.PUNCT })
    }

    @Test
    fun `em dash dialogue`() {
        val t = Tokenizer.tokenize("—Ven aquí —dijo ella—. ¡Rápido!")
        assertEquals(listOf("Ven", "aquí", "dijo", "ella", "Rápido"), t.words.map { it.text })
        assertTrue(t.tokens.filter { it.text == "—" }.all { it.kind == TokenKind.PUNCT })
    }

    @Test
    fun `internal apostrophes and hyphens only between letters`() {
        assertEquals(listOf("franco-mexicano", "pa'l", "rock"), words("franco-mexicano pa'l 'rock'"))
        assertEquals(listOf("pa’l"), words("pa’l"))
        assertEquals("pa'l", Tokenizer.tokenize("pa’l").words.single().normalized)
        assertEquals(listOf("guion", "final"), words("guion- -final"))
    }

    @Test
    fun `whitespace runs including newlines are single tokens`() {
        val t = Tokenizer.tokenize("uno\n\n  dos")
        assertEquals(listOf("uno", "\n\n  ", "dos"), t.tokens.map { it.text })
        assertEquals(TokenKind.WHITESPACE, t.tokens[1].kind)
    }

    @Test
    fun `ellipsis and guillemets are punctuation`() {
        val t = Tokenizer.tokenize("«Pues…» y...")
        assertEquals(listOf("«", "Pues", "…", "»", " ", "y", "..."), t.tokens.map { it.text })
        assertNull(t.tokens.first().normalized)
    }
}
