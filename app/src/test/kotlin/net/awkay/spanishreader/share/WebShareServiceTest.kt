package net.awkay.spanishreader.share

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import net.awkay.spanishreader.core.gloss.FoundPhrase
import net.awkay.spanishreader.core.gloss.Gloss
import net.awkay.spanishreader.core.gloss.GlossCache
import net.awkay.spanishreader.core.vocab.WordStatus
import net.awkay.spanishreader.data.DbTestBase
import net.awkay.spanishreader.data.LessonEntity
import net.awkay.spanishreader.data.LessonRepository
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebShareServiceTest : DbTestBase() {
    @get:Rule
    val tmp = TemporaryFolder()
    private val server = MockWebServer().apply { start() }

    @After
    fun stop() = server.close()

    private fun settings() = SettingsRepository(PreferenceDataStoreFactory.create { tmp.newFile("s.preferences_pb").also { it.delete() } })

    @Test
    fun sharesLessonWithCachedSentenceDataButNoVocabulary() = runTest {
        val s = settings()
        s.update { it.copy(webUrl = server.url("/").toString(), webAccessCode = "482913", webName = "Tony") }
        val phrases = PhraseStore(db.phrases())
        val cache = RoomGlossCache(db.glosses(), phrases)
        val sentence = "Voy a echar de menos a mi familia."
        cache.put("echar", sentence, Gloss("echar", "echar", "verb", "to throw", isIdiomOrPhrase = true, phrase = "echar de menos", phraseMeaning = "to miss"))
        phrases.saveTranslation(sentence, "I'm going to miss my family.")
        VocabRepository(db).setStatus("familia", WordStatus.KNOWN)

        server.enqueue(MockResponse.Builder().code(200).body("""{"token":"tok1"}""").build())
        server.enqueue(MockResponse.Builder().code(200).body("""{"id":"abcdef0123456789","title":"T","words":9}""").build())
        val service = WebShareService(s, cache, phrases, OkHttpClient())
        val id = service.share(LessonEntity(title = "T", text = "$sentence Hola.", createdAtMillis = 1))
        assertEquals("abcdef0123456789", id)

        val login = server.takeRequest()
        assertEquals("/api/login", login.url.encodedPath)
        assertEquals("482913", Json.parseToJsonElement(login.body!!.utf8()).jsonObject["code"]!!.jsonPrimitive.content)
        val upload = server.takeRequest()
        assertEquals("Bearer tok1", upload.headers["Authorization"])
        val raw = upload.body!!.utf8()
        val body = Json.parseToJsonElement(raw).jsonObject
        assertEquals("Tony", body["sharedBy"]!!.jsonPrimitive.content)
        val sentences = body["sentences"]!!.jsonArray
        assertEquals(1, sentences.size) // "Hola." has nothing cached
        val first = sentences[0] as JsonObject
        assertEquals(GlossCache.sentenceHash(sentence), first["hash"]!!.jsonPrimitive.content)
        assertEquals("I'm going to miss my family.", first["translation"]!!.jsonPrimitive.content)
        assertEquals("echar de menos", first["phrases"]!!.jsonArray[0].jsonObject["phrase"]!!.jsonPrimitive.content)
        assertEquals("to throw", first["glosses"]!!.jsonObject["echar"]!!.jsonObject["meaningInContext"]!!.jsonPrimitive.content)
        assertFalse("familia" in raw.substringAfter("\"sentences\"").substringBefore("\"glosses\"") && "KNOWN" in raw, "no vocabulary statuses")
        assertFalse("status" in raw)
        assertEquals("tok1", s.current().webToken) // token kept for next time
        assertTrue(phrases.forSentence(sentence).isNotEmpty())
    }

    @Test
    fun expiredTokenLogsInAgainOnceAndErrorsAreReported() = runTest {
        val s = settings()
        s.update { it.copy(webUrl = server.url("/").toString(), webAccessCode = "1", webToken = "old") }
        server.enqueue(MockResponse.Builder().code(401).body("""{"error":"Not signed in"}""").build())
        server.enqueue(MockResponse.Builder().code(200).body("""{"token":"new"}""").build())
        server.enqueue(MockResponse.Builder().code(200).body("""{"id":"x"}""").build())
        val service = WebShareService(s, RoomGlossCache(db.glosses()), PhraseStore(db.phrases()), OkHttpClient())
        assertEquals("x", service.share(LessonEntity(title = "T", text = "Hola.", createdAtMillis = 1)))
        assertEquals(listOf("/api/lessons", "/api/login", "/api/lessons"), List(3) { server.takeRequest().url.encodedPath })

        server.enqueue(MockResponse.Builder().code(401).body("""{"error":"That code isn't right."}""").build())
        s.update { it.copy(webToken = "") }
        val e = assertFailsWith<java.io.IOException> { service.share(LessonEntity(title = "T", text = "Hola.", createdAtMillis = 1)) }
        assertEquals("That code isn't right.", e.message)
        assertFailsWith<IllegalArgumentException> {
            s.update { it.copy(webAccessCode = "") }
            service.share(LessonEntity(title = "T", text = "Hola.", createdAtMillis = 1))
        }
    }

    @Test
    fun listsSharedLessonsWithTheSavedToken() = runTest {
        val s = settings()
        s.update { it.copy(webUrl = server.url("/").toString(), webAccessCode = "1", webToken = "tok") }
        server.enqueue(
            MockResponse.Builder().code(200).body(
                """[{"id":"a1","title":"Cuento","createdAt":5,"words":120,"sharedBy":"Ana"},{"id":"b2","title":"Otro","createdAt":4,"words":7}]""",
            ).build(),
        )
        val service = WebShareService(s, RoomGlossCache(db.glosses()), PhraseStore(db.phrases()), OkHttpClient())
        val list = service.listShared()
        assertEquals(listOf(SharedLessonSummary("a1", "Cuento", 120, "Ana", 5), SharedLessonSummary("b2", "Otro", 7, null, 4)), list)
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/lessons", req.url.encodedPath)
        assertEquals("Bearer tok", req.headers["Authorization"])
    }

    @Test
    fun downloadsLessonAndStoresSharedAiResultsUnderTheAppsKeysWithoutTouchingVocabulary() = runTest {
        val s = settings()
        s.update { it.copy(webUrl = server.url("/").toString(), webAccessCode = "1", webToken = "tok") }
        val phrases = PhraseStore(db.phrases())
        val cache = RoomGlossCache(db.glosses(), phrases)
        val lessons = LessonRepository(db) { now }
        val vocab = VocabRepository(db)
        val s1 = "Voy a echar de menos a mi familia."
        val s2 = "Hola, amigo."
        val mine = Gloss("familia", "familia", "noun", "my own answer")
        cache.put("familia", s1, mine)
        val serverGloss = Json.encodeToString(Gloss.serializer(), Gloss("echar", "echar", "verb", "to throw", isIdiomOrPhrase = true, phrase = "echar de menos", phraseMeaning = "to miss"))
        val h1 = GlossCache.sentenceHash(s1)
        val h2 = GlossCache.sentenceHash(s2)

        server.enqueue(MockResponse.Builder().code(200).body(Json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("id", "a1"); put("title", "Cuento"); put("text", "$s1 $s2"); put("createdAt", 5)
        })).build())
        server.enqueue(MockResponse.Builder().code(200).body(
            """{"$h1":{"hash":"$h1","translation":"I'm going to miss my family.","phrases":[{"phrase":"echar de menos","meaning":"to miss"}],"scanned":true,
               "glosses":{"echar":$serverGloss,"familia":{"form":"familia","lemma":"familia","partOfSpeech":"noun","meaningInContext":"server answer"},"mi":{"bogus":1}}},
               "$h2":{"hash":"$h2","scanned":true}}""",
        ).build())
        val service = WebShareService(s, cache, phrases, OkHttpClient())
        val result = service.download("a1", lessons)

        assertFalse(result.alreadyHad)
        assertEquals(2, result.aiResults) // echar's gloss + the translation; local familia kept, malformed mi skipped
        val lesson = lessons.get(result.lessonId)!!
        assertEquals("Cuento", lesson.title)
        assertEquals("$s1 $s2", lesson.text)
        assertEquals("/api/lessons/a1", server.takeRequest().url.encodedPath)
        val cacheReq = server.takeRequest()
        assertEquals("/api/cache/get", cacheReq.url.encodedPath)
        val asked = Json.parseToJsonElement(cacheReq.body!!.utf8()).jsonObject["hashes"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf(h1, h2), asked)

        assertEquals("to throw", cache.get("echar", s1)!!.meaningInContext)
        assertEquals("my own answer", cache.get("familia", s1)!!.meaningInContext)
        assertEquals(null, cache.get("mi", s1))
        assertEquals("I'm going to miss my family.", phrases.translation(s1))
        assertEquals(listOf(FoundPhrase("echar de menos", "to miss")), phrases.forSentence(s1))
        assertEquals(emptyList<String>(), phrases.unscanned(listOf(s1, s2)))
        assertEquals(null, vocab.get("echar"))
        assertEquals(null, vocab.get("familia"))

        // Adding it again reuses the local copy and only fills in what's still missing.
        server.enqueue(MockResponse.Builder().code(200).body("""{"id":"a1","title":"Cuento","text":"$s1 $s2","createdAt":5}""").build())
        server.enqueue(MockResponse.Builder().code(500).body("""{"error":"disk full"}""").build())
        val again = service.download("a1", lessons)
        assertTrue(again.alreadyHad)
        assertEquals(result.lessonId, again.lessonId)
        assertEquals(null, again.aiResults) // cache fetch failed, the lesson is still there
        assertEquals(1, lessons.all().size)
    }

    @Test
    fun downloadReportsMissingSettingsAndServerErrors() = runTest {
        val s = settings()
        val lessons = LessonRepository(db) { now }
        val service = WebShareService(s, RoomGlossCache(db.glosses()), PhraseStore(db.phrases()), OkHttpClient())
        s.update { it.copy(webUrl = server.url("/").toString(), webAccessCode = "") }
        assertFailsWith<IllegalArgumentException> { service.download("a1", lessons) }
        s.update { it.copy(webAccessCode = "1", webToken = "tok") }
        server.enqueue(MockResponse.Builder().code(404).body("""{"error":"no such lesson"}""").build())
        val e = assertFailsWith<java.io.IOException> { service.download("gone", lessons) }
        assertEquals("no such lesson", e.message)
        assertTrue(lessons.all().isEmpty())
    }
}
