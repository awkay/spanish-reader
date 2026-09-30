package net.awkay.spanishreader.data

import kotlinx.coroutines.test.runTest
import net.awkay.spanishreader.core.gloss.CachingGlosser
import net.awkay.spanishreader.core.gloss.Gloss
import net.awkay.spanishreader.core.gloss.GlossRequest
import net.awkay.spanishreader.core.gloss.GlossResult
import net.awkay.spanishreader.core.gloss.Glosser
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RoomGlossCacheTest : DbTestBase() {
    private val cache by lazy { RoomGlossCache(db.glosses()) { now } }

    private fun gloss(meaning: String) = Gloss(
        form = "banco", lemma = "banco", partOfSpeech = "noun", meaningInContext = meaning,
        grammarNote = null, otherMeanings = listOf("bench"),
    )

    @Test
    fun roundTripsByFormAndSentence() = runTest {
        cache.put("Banco", "Fui al banco.", gloss("bank"))
        assertEquals(gloss("bank"), cache.get("banco", "Fui  al banco."))
        assertNull(cache.get("banco", "Me senté en el banco."))
    }

    @Test
    fun getByFormReturnsMostRecent() = runTest {
        cache.put("banco", "Fui al banco.", gloss("bank"))
        now = 2_000
        cache.put("banco", "Me senté en el banco.", gloss("bench"))
        assertEquals("bench", cache.getByForm("banco")?.meaningInContext)
    }

    @Test
    fun putReplacesSameKey() = runTest {
        cache.put("banco", "Fui al banco.", gloss("bank"))
        cache.put("banco", "Fui al banco.", gloss("bank (financial)"))
        assertEquals("bank (financial)", cache.get("banco", "Fui al banco.")?.meaningInContext)
    }

    @Test
    fun corruptRowIsAMiss() = runTest {
        cache.put("banco", "Fui al banco.", gloss("bank"))
        val row = db.glosses().latestByForm("banco")!!
        db.glosses().upsert(row.copy(json = "{not json"))
        assertNull(cache.get("banco", "Fui al banco."))
    }

    @Test
    fun cachingGlosserOnlyCallsDelegateOnce() = runTest {
        var calls = 0
        val delegate = object : Glosser {
            override suspend fun gloss(requests: List<GlossRequest>): List<GlossResult> {
                calls += requests.size
                return requests.map { GlossResult.Success(it.id, gloss("bank")) }
            }
        }
        val glosser = CachingGlosser(delegate, cache)
        repeat(2) { glosser.gloss(listOf(GlossRequest("banco", "Fui al banco."))) }
        assertEquals(1, calls)
    }
}
