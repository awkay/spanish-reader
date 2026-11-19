package net.awkay.spanishreader.phrases

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import net.awkay.spanishreader.core.gloss.TextCompleter
import net.awkay.spanishreader.core.practice.PhraseDeck
import net.awkay.spanishreader.core.practice.PhraseMode
import net.awkay.spanishreader.core.practice.PhrasePool
import net.awkay.spanishreader.core.practice.PhraseWriting
import net.awkay.spanishreader.data.DbTestBase
import net.awkay.spanishreader.data.PhraseStore
import net.awkay.spanishreader.data.RoomGlossCache
import net.awkay.spanishreader.data.SettingsRepository
import net.awkay.spanishreader.share.WebShareService
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhraseServiceTest : DbTestBase() {
    @get:Rule
    val tmp = TemporaryFolder()

    /** The server's shared list per deck (append when "after" matches), in memory. */
    private val stored = mutableMapOf<String, JsonArray>()
    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                val have = stored[path] ?: JsonArray(emptyList())
                return when {
                    path == "/api/login" -> MockResponse.Builder().code(200).body("""{"token":"t"}""").build()
                    request.method == "GET" && path.startsWith("/api/phrases/") ->
                        MockResponse.Builder().code(200).body("""{"cards":$have}""").build()
                    request.method == "PUT" && path.startsWith("/api/phrases/") -> {
                        val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
                        if (body["after"]!!.jsonPrimitive.int == have.size) stored[path] = JsonArray(have + (body["cards"] as JsonArray))
                        MockResponse.Builder().code(200).body("""{"cards":${stored[path] ?: have}}""").build()
                    }
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        start()
    }

    @After
    fun stop() = server.close()

    private var aiCalls = 0
    private var written = 0

    /** Writes a card for every target asked for, with English nobody has used. */
    private val ai = object : TextCompleter {
        override suspend fun complete(system: String, user: String): String {
            aiCalls++
            if (system != PhraseWriting.SYSTEM) return "unparseable review" // the cards stay as written
            val words = Regex("\"questionWords\":\"([^\"]+)\"").findAll(user).map { it.groupValues[1] }.toList()
            return words.mapIndexed { i, w -> """{"n":${i + 1},"english":"Fresh sentence ${++written}","spanish":"¿___ ahora?","answer":"$w"}""" }
                .joinToString(",", "{\"cards\":[", "]}")
        }
    }

    private suspend fun service(withWeb: Boolean): PhraseService {
        val s = SettingsRepository(PreferenceDataStoreFactory.create { java.io.File(tmp.newFolder(), "s.preferences_pb") })
        if (withWeb) s.update { it.copy(webUrl = server.url("/").toString(), webAccessCode = "1") }
        val phrases = PhraseStore(db.phrases())
        val web = WebShareService(s, RoomGlossCache(db.glosses(), phrases), phrases, OkHttpClient())
        return PhraseService(db, s, web, writerFor = { ai to ai }, clock = { now })
    }

    @Test
    fun threeRightsRetireASentenceAndAMissTakesOneBack() = runTest {
        val svc = service(withWeb = false)
        val card = svc.pool(PhraseDeck.QUESTIONS, PhraseMode.SAY).first()
        assertFalse(svc.answer(PhraseMode.SAY, card, right = true).retired)
        assertEquals(0, svc.answer(PhraseMode.SAY, card, right = false).progress.score)
        repeat(2) { assertFalse(svc.answer(PhraseMode.SAY, card, right = true).retired) }
        val last = svc.answer(PhraseMode.SAY, card, right = true)
        assertTrue(last.retired)
        assertEquals(4 to 1, last.progress.right to last.progress.wrong)
        assertFalse(card in svc.pool(PhraseDeck.QUESTIONS, PhraseMode.SAY))
        assertTrue(card in svc.pool(PhraseDeck.QUESTIONS, PhraseMode.LISTEN))
    }

    @Test
    fun aRetiredSentenceIsReplacedByANewSharedOne() = runTest {
        val first = service(withWeb = true)
        assertEquals(0, first.topUp(PhraseDeck.QUESTIONS)) // nothing retired: nothing to write
        assertEquals(0, aiCalls)

        val card = first.pool(PhraseDeck.QUESTIONS, PhraseMode.SAY).first()
        repeat(PhrasePool.TO_RETIRE) { first.answer(PhraseMode.SAY, card, right = true) }
        assertEquals(PhrasePool.BATCH, first.topUp(PhraseDeck.QUESTIONS))
        assertEquals(3, aiCalls) // write + two review tries
        val pool = first.pool(PhraseDeck.QUESTIONS, PhraseMode.SAY)
        assertEquals(PhrasePool.SIZE, pool.size)
        assertEquals("questions~1", pool.last().id)
        assertEquals("Fresh sentence 1", pool.last().english)
        assertEquals(PhrasePool.BATCH, stored.getValue("/api/phrases/questions").size)

        // Another device gets the shared sentences without asking the AI.
        db.practice().upsertScores(emptyList())
        val second = service(withWeb = true)
        assertEquals(PhrasePool.BATCH, second.topUp(PhraseDeck.QUESTIONS))
        assertEquals(3, aiCalls)
        assertEquals(first.generated(PhraseDeck.QUESTIONS), second.generated(PhraseDeck.QUESTIONS))
    }

    @Test
    fun withoutTheAiTheHandWrittenReserveFillsIn() = runTest {
        val s = SettingsRepository(PreferenceDataStoreFactory.create { java.io.File(tmp.newFolder(), "s.preferences_pb") })
        val phrases = PhraseStore(db.phrases())
        val svc = PhraseService(db, s, WebShareService(s, RoomGlossCache(db.glosses(), phrases), phrases, OkHttpClient()), writerFor = { null })
        val card = svc.pool(PhraseDeck.QUESTIONS, PhraseMode.SAY).first()
        repeat(PhrasePool.TO_RETIRE) { svc.answer(PhraseMode.SAY, card, right = true) }
        assertEquals(0, svc.topUp(PhraseDeck.QUESTIONS))
        val pool = svc.pool(PhraseDeck.QUESTIONS, PhraseMode.SAY)
        assertEquals(PhrasePool.SIZE, pool.size)
        assertEquals(PhrasePool.builtInOrder(PhraseDeck.QUESTIONS)[PhrasePool.SIZE], pool.last())
    }
}
