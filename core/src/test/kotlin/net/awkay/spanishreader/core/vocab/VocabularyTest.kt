package net.awkay.spanishreader.core.vocab

import net.awkay.spanishreader.core.text.Tokenizer
import net.awkay.spanishreader.core.vocab.WordStatus.FAMILIAR
import net.awkay.spanishreader.core.vocab.WordStatus.IGNORED
import net.awkay.spanishreader.core.vocab.WordStatus.KNOWN
import net.awkay.spanishreader.core.vocab.WordStatus.LEARNED
import net.awkay.spanishreader.core.vocab.WordStatus.LEVEL_1
import net.awkay.spanishreader.core.vocab.WordStatus.NEW
import net.awkay.spanishreader.core.vocab.WordStatus.RECOGNIZED
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class VocabularyTest {
    private fun entry(form: String, status: WordStatus) =
        VocabEntry(form = form, status = status, firstSeenMillis = 1, lastSeenMillis = 1)

    @Test
    fun `status codes and highlighting`() {
        assertEquals(listOf(0, 1, 2, 3, 4, 5, -1), WordStatus.entries.map { it.code })
        assertEquals(listOf(NEW, LEVEL_1, RECOGNIZED, FAMILIAR, LEARNED), WordStatus.entries.filter { it.isHighlighted })
        assertFalse(KNOWN.isHighlighted)
        assertFalse(IGNORED.isHighlighted)
        assertEquals(listOf(1f, 0.75f, 0.5f, 0.25f), listOf(LEVEL_1, RECOGNIZED, FAMILIAR, LEARNED).map { it.highlightIntensity })
        assertEquals(0f, NEW.highlightIntensity)
        assertEquals(RECOGNIZED, WordStatus.fromCode(2))
        assertFailsWith<IllegalArgumentException> { WordStatus.fromCode(9) }
        assertEquals("Familiar", FAMILIAR.label)
    }

    @Test
    fun `ladder stepping`() {
        assertEquals(listOf(LEVEL_1, RECOGNIZED, FAMILIAR, LEARNED, KNOWN, KNOWN, IGNORED),
            listOf(NEW, LEVEL_1, RECOGNIZED, FAMILIAR, LEARNED, KNOWN, IGNORED).map { it.next() })
        assertEquals(listOf(NEW, LEVEL_1, LEVEL_1, RECOGNIZED, FAMILIAR, LEARNED, IGNORED),
            listOf(NEW, LEVEL_1, RECOGNIZED, FAMILIAR, LEARNED, KNOWN, IGNORED).map { it.previous() })
    }

    @Test
    fun `tapping an unseen word adds it at level 1`() {
        val e = VocabularyRules.onTap(null, "casa", now = 100, contextSentence = "Mi casa.")
        assertEquals(VocabEntry("casa", null, LEVEL_1, null, "Mi casa.", 100, 100, 1), e)
    }

    @Test
    fun `tapping a NEW entry promotes it`() {
        val e = VocabularyRules.onTap(entry("casa", NEW), "casa", now = 50, contextSentence = "x")
        assertEquals(LEVEL_1, e.status)
        assertEquals(50, e.lastSeenMillis)
        assertEquals("x", e.contextSentence)
    }

    @Test
    fun `tapping other statuses leaves entry unchanged`() {
        for (s in listOf(LEVEL_1, FAMILIAR, KNOWN, IGNORED)) {
            val original = entry("casa", s)
            assertSame(original, VocabularyRules.onTap(original, "casa", now = 99))
        }
    }

    @Test
    fun `setStatus updates status and time`() {
        val e = VocabularyRules.setStatus(entry("casa", LEVEL_1), FAMILIAR, now = 7)
        assertEquals(FAMILIAR, e.status)
        assertEquals(7, e.lastSeenMillis)
        val same = entry("casa", KNOWN)
        assertSame(same, VocabularyRules.setStatus(same, KNOWN, now = 7))
    }

    @Test
    fun `finishing a page selects the remaining NEW words`() {
        val page = listOf("el", "perro", "come", "el", "hueso", "uf", "ya")
        val statuses = mapOf("perro" to LEVEL_1, "come" to NEW, "uf" to IGNORED, "ya" to KNOWN)
        assertEquals(listOf("el", "come", "hueso"), VocabularyRules.onPageFinished(page, statuses))
    }

    @Test
    fun `applyPageFinished adds unmarked words at LEVEL_1 with details, never KNOWN`() {
        val existing = mapOf("come" to entry("come", NEW), "perro" to entry("perro", RECOGNIZED))
        val details = mapOf("gato" to WordDetail("El gato duerme.", "gato", "cat"), "come" to WordDetail("Él come.", "comer", "eats"))
        val updated = VocabularyRules.applyPageFinished(listOf("come", "perro", "gato"), existing, now = 42, details = details)
        assertEquals(
            listOf(
                entry("come", NEW).copy(status = LEVEL_1, lemma = "comer", translation = "eats", contextSentence = "Él come.", lastSeenMillis = 42),
                VocabEntry("gato", "gato", LEVEL_1, "cat", "El gato duerme.", firstSeenMillis = 42, lastSeenMillis = 42),
            ),
            updated,
        )
        assertEquals(LEVEL_1, VocabularyRules.applyPageFinished(listOf("sin"), emptyMap(), now = 1).single().status)
    }

    @Test
    fun `marking a page known only touches NEW words`() {
        val existing = mapOf("perro" to entry("perro", RECOGNIZED), "uf" to entry("uf", IGNORED))
        val updated = VocabularyRules.markNewAsKnown(listOf("el", "perro", "uf", "el"), existing, now = 9)
        assertEquals(listOf(VocabEntry("el", status = KNOWN, firstSeenMillis = 9, lastSeenMillis = 9)), updated)
    }

    @Test
    fun `lesson stats`() {
        val text = Tokenizer.tokenize("El perro y el gato. ¡El perro come! Uf.")
        val stats = LessonStats.compute(text, mapOf("perro" to LEVEL_1, "gato" to KNOWN, "y" to KNOWN, "uf" to IGNORED))
        assertEquals(9, stats.totalWords)
        assertEquals(6, stats.uniqueWords)
        assertEquals(2, stats.newCount) // el, come
        assertEquals(1, stats.learningCount)
        assertEquals(2, stats.count(KNOWN))
        assertEquals(1, stats.count(IGNORED))
        assertEquals(0, stats.count(LEARNED))
        assertEquals(100.0 * 2 / 6, stats.knownPercent, 1e-9)
        assertEquals(100.0, WordStatus.entries.sumOf { stats.percent(it) }, 1e-9)
        assertTrue(WordStatus.entries.all { it in stats.countByStatus })
    }

    @Test
    fun `empty lesson stats`() {
        val stats = LessonStats.compute(emptyList(), emptyMap())
        assertEquals(0, stats.uniqueWords)
        assertEquals(0.0, stats.percent(NEW))
    }
}
