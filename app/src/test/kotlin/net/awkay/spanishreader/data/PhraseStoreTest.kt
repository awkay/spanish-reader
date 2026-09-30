package net.awkay.spanishreader.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.awkay.spanishreader.core.gloss.FoundPhrase
import net.awkay.spanishreader.core.gloss.Gloss
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhraseStoreTest : DbTestBase() {
    private val store by lazy { PhraseStore(db.phrases()) { now } }

    @Test
    fun saveObserveAndScanTracking() = runTest {
        val s1 = "Voy a echar de menos a mi familia."
        val s2 = "Sin embargo, llegó."
        store.save(s1, listOf(FoundPhrase("echar de menos", "to miss")))
        assertEquals(mapOf(s1 to listOf(FoundPhrase("echar de menos", "to miss"))), store.observe(listOf(s1, s2)).first())
        assertEquals(listOf(s1, s2), store.unscanned(listOf(s1, s2, s1)))
        store.markScanned(listOf(s1))
        assertEquals(listOf(s2), store.unscanned(listOf(s1, s2)))
        assertTrue(store.observe(emptyList()).first().isEmpty())
    }

    @Test
    fun glossCacheRecordsTheExpressionAGlossMentions() = runTest {
        val cache = RoomGlossCache(db.glosses(), store) { now }
        val sentence = "Se me olvidó la llave."
        cache.put("olvidó", sentence, Gloss("olvidó", "olvidar", "verb", "forgot", isIdiomOrPhrase = true, phrase = "se me olvidó", phraseMeaning = "I forgot"))
        cache.put("llave", sentence, Gloss("llave", "llave", "noun", "key", isIdiomOrPhrase = true, phrase = "llave"))
        assertEquals(listOf(FoundPhrase("se me olvidó", "I forgot")), store.forSentence(sentence))
    }
}
