package net.awkay.spanishreader.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.awkay.spanishreader.core.gloss.Gloss
import net.awkay.spanishreader.core.vocab.WordFamilies
import net.awkay.spanishreader.core.vocab.WordStatus
import org.junit.Test
import kotlin.test.assertEquals

/** Word families end to end on the database: lemmas from cached glosses, inherited statuses, lemma backfill. */
class WordFamilyRepositoryTest : DbTestBase() {
    private val cache by lazy { RoomGlossCache(db.glosses()) { now } }
    private val repo by lazy { VocabRepository(db, cache) { now } }

    private fun gloss(form: String, lemma: String, meaning: String = form) =
        Gloss(form = form, lemma = lemma, partOfSpeech = "verb", meaningInContext = meaning, grammarNote = null, otherMeanings = emptyList())

    @Test
    fun lemmasPreferTheWordsOwnSentence() = runTest {
        cache.put("fue", "Fue a la tienda.", gloss("fue", "ir"))
        now = 2_000
        cache.put("fue", "Fue muy amable.", gloss("fue", "ser"))
        cache.put("hablaban", "Ellos hablaban.", gloss("hablaban", "hablar"))
        val lemmas = cache.lemmas(mapOf("fue" to "Fue a la tienda.", "hablaban" to "Otra frase.", "nada" to "Nada."))
        assertEquals(mapOf("fue" to "ir", "hablaban" to "hablar"), lemmas)
    }

    @Test
    fun aNewSpellingOfAKnownVerbIsShownKnownAndEntersKnown() = runTest {
        repo.setStatus("hablar", WordStatus.KNOWN)
        cache.put("hablaban", "Ellos hablaban.", gloss("hablaban", "hablar"))
        cache.put("comían", "Ellos comían.", gloss("comían", "comer"))
        val entries = repo.observeAll().first()
        val lemmas = cache.lemmas(mapOf("hablaban" to "Ellos hablaban.", "comían" to "Ellos comían."))
        val own = entries.associate { it.form to it.status }
        val families = WordFamilies.familyStatuses(entries)
        val shown = WordFamilies.effectiveStatuses(lemmas.keys, own, lemmas, families)
        assertEquals(WordStatus.KNOWN, shown["hablaban"])
        assertEquals(null, shown["comían"])

        val inherited = mapOf("hablaban" to WordFamilies.inherited("hablaban", own["hablaban"], lemmas, families)!!)
        now = 5_000
        assertEquals(
            listOf("hablaban", "comían"),
            repo.finishPage(listOf("hablaban" to "Ellos hablaban.", "comían" to "Ellos comían."), inherited),
        )
        assertEquals(WordStatus.KNOWN, repo.get("hablaban")!!.status)
        assertEquals("hablar", repo.get("hablaban")!!.lemma)
        assertEquals(WordStatus.LEVEL_1, repo.get("comían")!!.status)

        assertEquals(WordStatus.RECOGNIZED, repo.tap("hablamos", "Hablamos.", WordStatus.RECOGNIZED).status)
    }

    @Test
    fun markingKnownStoresTheLemmaAndBackfillFillsOldEntries() = runTest {
        cache.put("dijo", "Me lo dijo.", gloss("dijo", "decir"))
        cache.put("puso", "Lo puso ahí.", gloss("puso", "poner"))
        repo.markNewAsKnown(listOf("dijo"))
        assertEquals("decir", repo.get("dijo")!!.lemma)

        repo.setStatus("puso", WordStatus.FAMILIAR) // saved before it had a lemma
        repo.setStatus("xyz", WordStatus.KNOWN) // never glossed
        assertEquals(null, repo.get("puso")!!.lemma)
        assertEquals(1, repo.backfillLemmas())
        assertEquals("poner", repo.get("puso")!!.lemma)
        assertEquals(0, repo.backfillLemmas())
    }
}
