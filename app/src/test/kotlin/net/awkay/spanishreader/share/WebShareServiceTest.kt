package net.awkay.spanishreader.share

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import net.awkay.spanishreader.core.gloss.FoundPhrase
import net.awkay.spanishreader.core.gloss.Gloss
import net.awkay.spanishreader.core.gloss.GlossCache
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
}
