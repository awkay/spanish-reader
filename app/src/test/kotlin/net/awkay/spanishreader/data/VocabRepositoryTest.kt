package net.awkay.spanishreader.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.awkay.spanishreader.core.vocab.WordStatus
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VocabRepositoryTest : DbTestBase() {
    private val repo by lazy { VocabRepository(db) { now } }

    @Test
    fun tapOnUnknownFormAddsItAtLevel1WithContext() = runTest {
        val e = repo.tap("hablo", "Yo hablo español.")
        assertEquals(WordStatus.LEVEL_1, e.status)
        assertEquals(e, repo.get("hablo"))
        assertEquals("Yo hablo español.", repo.get("hablo")?.contextSentence)
    }

    @Test
    fun tapDoesNotDemoteALearnedWord() = runTest {
        repo.setStatus("casa", WordStatus.FAMILIAR)
        now = 2_000
        assertEquals(WordStatus.FAMILIAR, repo.tap("casa", "La casa.").status)
    }

    @Test
    fun formsAreDistinctKeys() = runTest {
        repo.tap("sí", null)
        assertNull(repo.get("si"))
        repo.tap("hablas", null)
        assertEquals(setOf("sí", "hablas"), repo.observeAll().first().map { it.form }.toSet())
    }

    @Test
    fun finishPageAddsUnmarkedWordsAtLevel1WithTheirGloss() = runTest {
        val cache = net.awkay.spanishreader.core.gloss.InMemoryGlossCache()
        cache.put("come", "El gato come.", net.awkay.spanishreader.core.gloss.Gloss("come", "comer", "verb", "eats"))
        val repo = VocabRepository(db, cache) { now }
        repo.tap("gato", null)
        repo.setStatus("juan", WordStatus.IGNORED)
        repo.setStatus("perro", WordStatus.KNOWN)
        now = 5_000
        val words = listOf("el", "gato", "juan", "perro", "come", "el").map { it to "El gato come." }
        assertEquals(listOf("el", "come"), repo.finishPage(words))
        assertEquals(
            mapOf(
                "el" to WordStatus.LEVEL_1, "gato" to WordStatus.LEVEL_1, "juan" to WordStatus.IGNORED,
                "perro" to WordStatus.KNOWN, "come" to WordStatus.LEVEL_1,
            ),
            repo.statuses(listOf("el", "gato", "juan", "perro", "come")),
        )
        val come = repo.get("come")!!
        assertEquals("comer", come.lemma)
        assertEquals("eats", come.translation)
        assertEquals("El gato come.", come.contextSentence)
        assertEquals(5_000, come.lastSeenMillis)
        assertNull(repo.get("el")!!.translation) // never glossed: added without a meaning
    }

    @Test
    fun markNewAsKnownIsExplicitAndOnlyTouchesBlueWords() = runTest {
        repo.tap("gato", null)
        assertEquals(listOf("el", "come"), repo.markNewAsKnown(listOf("el", "gato", "come")))
        assertEquals(WordStatus.LEVEL_1, repo.get("gato")!!.status)
        assertEquals(WordStatus.KNOWN, repo.get("el")!!.status)
    }

    @Test
    fun largePagesAreChunkedUnderTheBindLimit() = runTest {
        val forms = (1..2_500).map { "palabra$it" }
        assertEquals(2_500, repo.finishPage(forms.map { it to "s" }).size)
        assertEquals(2_500, repo.statuses(forms).size)
    }

    @Test
    fun observeByStatusFilters() = runTest {
        repo.tap("uno", null)
        repo.setStatus("dos", WordStatus.KNOWN)
        assertEquals(listOf("uno"), repo.observeByStatus(WordStatus.LEVEL_1).first().map { it.form })
    }

    @Test
    fun annotateKeepsStatusAndSkipsMissingRows() = runTest {
        repo.tap("fui", null)
        repo.annotate("fui", "ir", "I went")
        repo.annotate("nada", "nada", "nothing")
        val e = repo.get("fui")!!
        assertEquals(WordStatus.LEVEL_1, e.status)
        assertEquals("ir", e.lemma)
        assertEquals("I went", e.translation)
        assertNull(repo.get("nada"))
    }
}
