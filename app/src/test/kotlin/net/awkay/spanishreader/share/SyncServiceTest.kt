package net.awkay.spanishreader.share

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import net.awkay.spanishreader.core.vocab.WordStatus
import net.awkay.spanishreader.data.DbTestBase
import net.awkay.spanishreader.data.LessonEntity
import net.awkay.spanishreader.data.PhraseStore
import net.awkay.spanishreader.data.RoomGlossCache
import net.awkay.spanishreader.data.SettingsRepository
import net.awkay.spanishreader.data.VocabRepository
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncServiceTest : DbTestBase() {
    @get:Rule
    val tmp = TemporaryFolder()

    /** The server's sync endpoints, in memory (like server/sync.go). */
    private var revision = 0
    private var device = ""
    private var snapshot: JsonElement? = null
    private val logins = mutableListOf<JsonObject>()
    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body?.utf8()?.takeIf { it.isNotEmpty() }?.let { Json.parseToJsonElement(it).jsonObject }
                fun ok(o: JsonElement) = MockResponse.Builder().code(200).body(o.toString()).build()
                fun meta() = buildJsonObject { put("revision", revision); put("updatedAt", 5); put("device", device) }
                return when ("${request.method} ${request.url.encodedPath}") {
                    "POST /api/login" -> {
                        logins += body!!
                        ok(buildJsonObject { put("token", "tok") })
                    }
                    "GET /api/sync/meta" -> ok(meta())
                    "GET /api/sync" -> ok(JsonObject(meta() + ("snapshot" to (snapshot ?: Json.parseToJsonElement("null")))))
                    "PUT /api/sync" -> {
                        if (body!!["force"]!!.jsonPrimitive.content != "true" && body["baseRevision"]!!.jsonPrimitive.int != revision) {
                            MockResponse.Builder().code(409).body(buildJsonObject { put("error", "newer"); put("current", meta()) }.toString()).build()
                        } else {
                            revision++
                            device = body["device"]!!.jsonPrimitive.content
                            snapshot = body["snapshot"]
                            ok(meta())
                        }
                    }
                    "POST /api/cache/get" -> ok(JsonObject(emptyMap()))
                    "POST /api/cache/put" -> ok(buildJsonObject { put("ok", true) })
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        start()
    }

    @After
    fun stop() = server.close()

    private suspend fun service(): SyncService {
        val s = SettingsRepository(PreferenceDataStoreFactory.create { tmp.newFile("s.preferences_pb").also { it.delete() } })
        s.update { it.copy(webUrl = server.url("/").toString(), webAccessCode = "482913", webUser = "tony") }
        val phrases = PhraseStore(db.phrases())
        val web = WebShareService(s, RoomGlossCache(db.glosses(), phrases), phrases, OkHttpClient())
        return SyncService(db, s, web, "Android (test)", clock = { now })
    }

    @Test
    fun upThenDownReplacesLocalStateAndWarnsAboutNewerWork() = runTest {
        val sync = service()
        val keptId = db.lessons().insert(LessonEntity(title = "A", text = "Hola.", createdAtMillis = 1, currentPage = 2))
        db.lessons().insert(LessonEntity(title = "Gone", text = "Adiós.", createdAtMillis = 2))
        val vocab = VocabRepository(db)
        vocab.setStatus("hola", WordStatus.KNOWN)

        assertIs<SyncOutcome.Done>(sync.up())
        assertEquals("tony", logins.single()["username"]!!.jsonPrimitive.content)
        assertEquals(1, revision)
        assertEquals("Android (test)", device)
        assertFalse(sync.status().localChanges)

        // Another device pushes: lesson A on page 5, a new lesson, different words; "Gone" was deleted there.
        val other = Json.parseToJsonElement(
            """{"version":1,"exportedAtMillis":9,"lessons":[{"title":"A","text":"Hola.","createdAtMillis":1,"currentPage":5},
               {"title":"B","text":"Bien.","createdAtMillis":3,"currentPage":1,"videoId":"abcdefghijk"}],
               "vocab":[{"form":"bien","status":3,"firstSeenMillis":1,"lastSeenMillis":2}]}""",
        )
        snapshot = other
        revision = 2
        device = "Web (iPhone)"

        // This device changed since its sync: pushing would overwrite the newer server copy, pulling would lose the change.
        vocab.setStatus("hola", WordStatus.LEARNED)
        assertEquals(2, assertIs<SyncOutcome.ServerNewer>(sync.up()).current.revision)
        assertEquals(1, assertIs<SyncOutcome.LocalChanges>(sync.down()).state!!.revision)

        assertIs<SyncOutcome.Done>(sync.down(force = true))
        val lessons = db.lessons().getAll().associateBy { it.title }
        assertEquals(setOf("A", "B"), lessons.keys)
        assertEquals(keptId, lessons.getValue("A").id)
        assertEquals(5, lessons.getValue("A").currentPage)
        assertEquals("abcdefghijk", lessons.getValue("B").videoId)
        assertEquals(listOf("bien"), db.vocab().getAllOnce().map { it.form })
        assertNull(db.vocab().get("hola"))
        val status = sync.status()
        assertEquals(2, status.state!!.revision)
        assertFalse(status.localChanges)

        // Now in step with the server: a push goes through without forcing.
        vocab.setStatus("bien", WordStatus.KNOWN)
        assertIs<SyncOutcome.Done>(sync.up())
        assertEquals(3, revision)
        assertTrue("\"bien\"" in snapshot.toString())
    }
}
