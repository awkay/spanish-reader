package net.awkay.spanishreader.drill

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import net.awkay.spanishreader.core.gloss.TextCompleter
import net.awkay.spanishreader.core.practice.CommonVerb
import net.awkay.spanishreader.core.practice.Drill
import net.awkay.spanishreader.core.practice.DrillVerdict
import net.awkay.spanishreader.core.vocab.WordStatus
import net.awkay.spanishreader.data.DbTestBase
import net.awkay.spanishreader.data.PhraseStore
import net.awkay.spanishreader.data.RoomGlossCache
import net.awkay.spanishreader.data.SettingsRepository
import net.awkay.spanishreader.data.VocabRepository
import net.awkay.spanishreader.share.WebShareService
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DrillServiceTest : DbTestBase() {
    @get:Rule
    val tmp = TemporaryFolder()

    /** The server's drill store (first set wins), in memory. */
    private val stored = mutableMapOf<String, String>()
    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                return when {
                    path == "/api/login" -> MockResponse.Builder().code(200).body("""{"token":"t"}""").build()
                    request.method == "GET" && path.startsWith("/api/drill/") ->
                        stored[path]?.let { MockResponse.Builder().code(200).body(it).build() } ?: MockResponse.Builder().code(404).body("{}").build()
                    request.method == "PUT" && path.startsWith("/api/drill/") -> {
                        val cards = Json.parseToJsonElement(request.body!!.utf8()).jsonObject["cards"]!!
                        stored.getOrPut(path) { """{"verb":"x","cards":$cards}""" }
                        MockResponse.Builder().code(200).body(stored.getValue(path)).build()
                    }
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        start()
    }

    @After
    fun stop() = server.close()

    private val tenCards = """{"cards":[${(1..10).joinToString(",") { """{"n":$it,"english":"E$it","spanish":"Ayer ___ $it.","answer":"fuimos"}""" }}]}"""
    private var aiCalls = 0

    private suspend fun service(withWeb: Boolean): Pair<DrillService, VocabRepository> {
        val s = SettingsRepository(PreferenceDataStoreFactory.create { java.io.File(tmp.newFolder(), "s.preferences_pb") })
        if (withWeb) s.update { it.copy(webUrl = server.url("/").toString(), webAccessCode = "1") }
        val phrases = PhraseStore(db.phrases())
        val web = WebShareService(s, RoomGlossCache(db.glosses(), phrases), phrases, OkHttpClient())
        val ai = object : TextCompleter {
            override suspend fun complete(system: String, user: String): String {
                aiCalls++
                return if (system == Drill.SYSTEM) tenCards else "unparseable review" // review keeps the cards as written
            }
        }
        val vocab = VocabRepository(db)
        return DrillService(db, s, vocab, web, writerFor = { ai to ai }, clock = { now }) to vocab
    }

    @Test
    fun cardsAreWrittenOnceSharedAndReusedFromTheServer() = runTest {
        val (first, _) = service(withWeb = true)
        val ir = CommonVerb("ir", "to go")
        val cards = first.cardsFor(ir)
        assertEquals(10, cards.size)
        assertEquals(3, aiCalls) // write + two review tries
        assertTrue("/api/drill/ir" in stored)
        assertEquals(cards, first.cardsFor(ir)) // kept on this device
        assertEquals(3, aiCalls)

        // Another device (empty database) gets the shared set without asking the AI.
        db.clearAllTables()
        val (second, _) = service(withWeb = true)
        assertEquals(cards, second.cardsFor(ir))
        assertEquals(3, aiCalls)
    }

    @Test
    fun sixRightAnswersLearnACardAndPutItsWordsInTheVocabulary() = runTest {
        val (drill, vocab) = service(withWeb = false)
        val card = drill.cardsFor(CommonVerb("ir", "to go")).first()
        assertEquals(DrillVerdict.WRONG, drill.answer(card, "vamos").verdict)
        repeat(5) { assertFalse(drill.answer(card, "Fuimos").learned) }
        assertNull(vocab.get("fuimos"))
        assertTrue(drill.answer(card, "fuimos").learned)
        val word = vocab.get("fuimos")!!
        assertEquals(WordStatus.KNOWN, word.status)
        assertEquals("ir", word.lemma)
        assertEquals("Ayer fuimos 1.", word.contextSentence)
        assertEquals(6, drill.progress().getValue(card.id).correct)
        assertFalse(drill.answer(card, "fuimos").learned) // only the first time counts

        drill.hide(drill.cardsFor(CommonVerb("ir", "to go"))[1])
        assertTrue(drill.progress().getValue("ir#2").hidden)
    }
}
