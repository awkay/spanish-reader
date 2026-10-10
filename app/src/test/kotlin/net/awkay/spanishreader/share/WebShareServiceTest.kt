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
import java.io.File
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

    @Test
    fun importsYouTubeThroughTheServerAndFetchesPageAudioOnce() = runTest {
        val s = settings()
        s.update { it.copy(webUrl = server.url("/").toString(), webAccessCode = "1", webToken = "tok") }
        val phrases = PhraseStore(db.phrases())
        val lessons = LessonRepository(db) { now }
        val service = WebShareService(s, RoomGlossCache(db.glosses(), phrases), phrases, OkHttpClient())
        val ok = { body: String -> MockResponse.Builder().code(200).body(body).build() }
        server.enqueue(ok("""{"id":"j1","videoId":"dQw4w9WgXcQ","status":"queued"}"""))
        server.enqueue(ok("""{"id":"j1","videoId":"dQw4w9WgXcQ","status":"transcribing","title":"Charla","detail":"Transcribing part 1 of 2"}"""))
        server.enqueue(ok("""{"id":"j1","videoId":"dQw4w9WgXcQ","status":"done","title":"Charla","lessonId":"L1"}"""))
        server.enqueue(ok("""{"id":"L1","title":"Charla","text":"Hola, amigos. ¿Cómo están?","createdAt":1,"source":"youtube",
            "videoId":"dQw4w9WgXcQ","sourceUrl":"https://www.youtube.com/watch?v=dQw4w9WgXcQ"}"""))
        server.enqueue(ok("{}"))
        val progress = mutableListOf<String>()
        val result = service.importYouTube(" https://youtu.be/dQw4w9WgXcQ ", lessons, onProgress = { progress += it })

        val lesson = lessons.get(result.lessonId)!!
        assertEquals("dQw4w9WgXcQ", lesson.videoId)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", lesson.sourceUrl)
        assertEquals(listOf("Waiting for another import to finish", "Charla — Transcribing part 1 of 2"), progress)
        val start = server.takeRequest()
        assertEquals("/api/youtube", start.url.encodedPath)
        assertEquals("https://youtu.be/dQw4w9WgXcQ", Json.parseToJsonElement(start.body!!.utf8()).jsonObject["url"]!!.jsonPrimitive.content)
        assertEquals("true", Json.parseToJsonElement(start.body!!.utf8()).jsonObject["deviceDownload"]!!.jsonPrimitive.content)
        assertEquals("/api/youtube/jobs/j1", server.takeRequest().url.encodedPath)
        assertEquals("/api/youtube/jobs/j1", server.takeRequest().url.encodedPath)
        assertEquals("/api/lessons/L1", server.takeRequest().url.encodedPath)
        server.takeRequest() // cache

        // Sharing it again keeps the video.
        assertEquals("dQw4w9WgXcQ", service.payload(lesson, "")["videoId"]!!.jsonPrimitive.content)

        // Page audio: fetched and saved once, then served from the device.
        server.enqueue(ok("""{"audio":"/api/audio/0123456789abcdef0123456789abcdef.mp3","timings":[[150,1650],[2000,3100]],"voice":"original"}"""))
        server.enqueue(MockResponse.Builder().code(200).body(okio.Buffer().write(byteArrayOf(1, 2, 3))).build())
        val dir = tmp.newFolder("video")
        val sentences = listOf("Hola, amigos.", "¿Cómo están?")
        val page = service.videoPage("dQw4w9WgXcQ", sentences, dir)
        assertEquals(listOf(150L..1650L, 2000L..3100L), page.timings)
        assertEquals(listOf<Byte>(1, 2, 3), page.file.readBytes().toList())
        val audioReq = server.takeRequest()
        assertEquals("/api/youtube/dQw4w9WgXcQ/audio", audioReq.url.encodedPath)
        assertEquals(sentences, Json.parseToJsonElement(audioReq.body!!.utf8()).jsonObject["sentences"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("Bearer tok", server.takeRequest().headers["Authorization"])
        val requests = server.requestCount
        assertEquals(page, service.videoPage("dQw4w9WgXcQ", sentences, dir))
        assertEquals(requests, server.requestCount, "the second time comes from the device")
        assertFailsWith<IllegalArgumentException> { service.videoPage("../../etc", sentences, dir) }
    }

    @Test
    fun youTubeImportErrorsAreReported() = runTest {
        val s = settings()
        s.update { it.copy(webUrl = server.url("/").toString(), webAccessCode = "1", webToken = "tok") }
        val phrases = PhraseStore(db.phrases())
        val service = WebShareService(s, RoomGlossCache(db.glosses(), phrases), phrases, OkHttpClient())
        server.enqueue(MockResponse.Builder().code(400).body("""{"error":"not a YouTube link"}""").build())
        assertEquals("not a YouTube link", assertFailsWith<java.io.IOException> {
            service.importYouTube("https://evil.com", LessonRepository(db) { now }, onProgress = {})
        }.message)
        server.enqueue(MockResponse.Builder().code(200).body("""{"id":"j2","videoId":"dQw4w9WgXcQ","status":"error","error":"the video is 90 minutes long; the limit is 60"}""").build())
        assertEquals("the video is 90 minutes long; the limit is 60", assertFailsWith<java.io.IOException> {
            service.importYouTube("https://youtu.be/dQw4w9WgXcQ", LessonRepository(db) { now }, onProgress = {})
        }.message)
    }

    private class FakeAudio(private val bytes: ByteArray?, private val fail: Exception? = null) : VideoAudioSource {
        val calls = mutableListOf<Pair<String, Int>>()
        var file: File? = null
        override suspend fun fetch(videoId: String, dir: File, maxMinutes: Int, onProgress: (Int) -> Unit): VideoAudio {
            calls += videoId to maxMinutes
            fail?.let { throw it }
            onProgress(50)
            onProgress(100)
            val f = File(dir, "yt-$videoId.webm").apply { writeBytes(bytes!!) }
            file = f
            return VideoAudio(f, "Charla en vivo", 620, "audio/webm", "$videoId.webm")
        }
    }

    @Test
    fun youTubeAudioIsDownloadedHereUploadedThenTranscribedOnTheServer() = runTest {
        val s = settings()
        s.update { it.copy(webUrl = server.url("/").toString(), webAccessCode = "1", webToken = "tok", webName = "Tony") }
        val phrases = PhraseStore(db.phrases())
        val lessons = LessonRepository(db) { now }
        val audio = FakeAudio(ByteArray(300_000) { (it % 251).toByte() })
        val service = WebShareService(s, RoomGlossCache(db.glosses(), phrases), phrases, OkHttpClient(), audio)
        val ok = { body: String -> MockResponse.Builder().code(200).body(body).build() }
        server.enqueue(ok("""{"id":"","videoId":"dQw4w9WgXcQ","status":"upload","maxMinutes":60}"""))
        server.enqueue(ok("""{"id":"j3","videoId":"dQw4w9WgXcQ","status":"queued","title":"Charla en vivo"}"""))
        server.enqueue(ok("""{"id":"j3","videoId":"dQw4w9WgXcQ","status":"done","title":"Charla en vivo","lessonId":"L3"}"""))
        server.enqueue(ok("""{"id":"L3","title":"Charla en vivo","text":"Hola, amigos.","createdAt":1,"source":"youtube",
            "videoId":"dQw4w9WgXcQ","sourceUrl":"https://www.youtube.com/watch?v=dQw4w9WgXcQ"}"""))
        server.enqueue(ok("{}"))
        val progress = mutableListOf<String>()
        val work = tmp.newFolder("work")
        val result = service.importYouTube("https://youtu.be/dQw4w9WgXcQ", lessons, onProgress = { progress += it }, workDir = work, pollMillis = 1)

        assertEquals(listOf("dQw4w9WgXcQ" to 60), audio.calls, "downloads the server's video ID with its length limit")
        assertEquals("dQw4w9WgXcQ", lessons.get(result.lessonId)!!.videoId)
        assertFalse(audio.file!!.exists(), "the downloaded audio is deleted after the upload")
        assertTrue(work.listFiles()!!.isEmpty())
        assertEquals(listOf("Reading the video…", "Downloading audio… 50%", "Downloading audio… 100%", "Uploading… 0%"), progress.take(4))
        assertTrue("Uploading… 100%" in progress)
        assertEquals("Charla en vivo — Waiting for another import to finish", progress.last())

        assertEquals("/api/youtube", server.takeRequest().url.encodedPath)
        val up = server.takeRequest()
        assertEquals("/api/youtube/upload", up.url.encodedPath)
        assertEquals("Bearer tok", up.headers["Authorization"])
        assertTrue(up.headers["Content-Type"]!!.startsWith("multipart/form-data"))
        val raw = up.body!!.toByteArray()
        val text = String(raw, Charsets.ISO_8859_1)
        val names = Regex("""name="([a-zA-Z]+)"""").findAll(text).map { it.groupValues[1] }.toList()
        assertEquals(listOf("videoId", "title", "durationSec", "sharedBy", "audio"), names, "fields first, the file last")
        for (v in listOf("dQw4w9WgXcQ", "620", "Tony", "filename=\"dQw4w9WgXcQ.webm\"", "Content-Type: audio/webm")) assertTrue(v in text, v)
        assertTrue(raw.size > 300_000)
        assertEquals("/api/youtube/jobs/j3", server.takeRequest().url.encodedPath)
        assertEquals("/api/lessons/L3", server.takeRequest().url.encodedPath)
    }

    @Test
    fun youTubeAlreadyOnTheServerIsNotDownloadedAgainAndDownloadErrorsUploadNothing() = runTest {
        val s = settings()
        s.update { it.copy(webUrl = server.url("/").toString(), webAccessCode = "1", webToken = "tok") }
        val phrases = PhraseStore(db.phrases())
        val ok = { body: String -> MockResponse.Builder().code(200).body(body).build() }
        val audio = FakeAudio(byteArrayOf(1))
        val service = WebShareService(s, RoomGlossCache(db.glosses(), phrases), phrases, OkHttpClient(), audio)
        server.enqueue(ok("""{"id":"j4","videoId":"dQw4w9WgXcQ","status":"done","title":"Charla","lessonId":"L4"}"""))
        server.enqueue(ok("""{"id":"L4","title":"Charla","text":"Hola.","createdAt":1,"videoId":"dQw4w9WgXcQ"}"""))
        server.enqueue(ok("{}"))
        service.importYouTube("https://youtu.be/dQw4w9WgXcQ", LessonRepository(db) { now }, onProgress = {}, workDir = tmp.newFolder())
        assertTrue(audio.calls.isEmpty())
        assertEquals(3, server.requestCount)

        val failing = FakeAudio(null, VideoAudioException("This video is age-restricted; YouTube only plays it to signed-in users, so it can't be imported."))
        val service2 = WebShareService(s, RoomGlossCache(db.glosses(), phrases), phrases, OkHttpClient(), failing)
        server.enqueue(ok("""{"id":"","videoId":"aaaaaaaaaaa","status":"upload","maxMinutes":60}"""))
        val e = assertFailsWith<VideoAudioException> {
            service2.importYouTube("https://youtu.be/aaaaaaaaaaa", LessonRepository(db) { now }, onProgress = {}, workDir = tmp.newFolder())
        }
        assertTrue("age-restricted" in e.message!!)
        assertEquals(4, server.requestCount, "nothing uploaded")
    }

    @Test
    fun photosAreReadByTheServerIntoASharedLessonThenAddedHere() = runTest {
        val s = settings()
        s.update { it.copy(webUrl = server.url("/").toString(), webAccessCode = "1", webToken = "tok", webName = "Tony") }
        val phrases = PhraseStore(db.phrases())
        val lessons = LessonRepository(db) { now }
        val service = WebShareService(s, RoomGlossCache(db.glosses(), phrases), phrases, OkHttpClient())
        val ok = { body: String -> MockResponse.Builder().code(200).body(body).build() }
        server.enqueue(ok("""{"id":"P1","title":"CORREDOR BIÓTICO","words":6,"source":"photo"}"""))
        server.enqueue(ok("""{"id":"P1","title":"CORREDOR BIÓTICO","text":"CORREDOR BIÓTICO\n\nCada una de las orillas.","createdAt":1,"source":"photo"}"""))
        server.enqueue(ok("{}"))
        val progress = mutableListOf<String>()
        val first = ByteArray(5000) { 1 }
        val result = service.importPhotos(listOf(first, ByteArray(3000) { 2 }), " ", lessons, onProgress = { progress += it })

        val lesson = lessons.get(result.lessonId)!!
        assertEquals("CORREDOR BIÓTICO", lesson.title)
        assertTrue(lesson.text.startsWith("CORREDOR BIÓTICO"))
        assertEquals(listOf("Uploading… 0%", "Reading the text in 2 photos…", "Adding the lesson…"), progress.distinct().filter { "%" !in it || it == "Uploading… 0%" })

        val up = server.takeRequest()
        assertEquals("/api/image", up.url.encodedPath)
        assertEquals("Bearer tok", up.headers["Authorization"])
        val text = String(up.body!!.toByteArray(), Charsets.ISO_8859_1)
        val names = Regex("""name="([a-zA-Z]+)"""").findAll(text).map { it.groupValues[1] }.toList()
        assertEquals(listOf("sharedBy", "image", "image"), names, "a blank title isn't sent, so the server takes the heading")
        for (v in listOf("filename=\"photo1.jpg\"", "filename=\"photo2.jpg\"", "Content-Type: image/jpeg")) assertTrue(v in text, v)
        assertEquals("/api/lessons/P1", server.takeRequest().url.encodedPath)
        assertEquals("/api/cache/get", server.takeRequest().url.encodedPath)

        server.enqueue(MockResponse.Builder().code(422).body("""{"error":"no Spanish text found in the photo"}""").build())
        val e = assertFailsWith<java.io.IOException> { service.importPhotos(listOf(first), "Letrero", lessons, onProgress = {}) }
        assertEquals("no Spanish text found in the photo", e.message)
        val second = String(server.takeRequest().body!!.toByteArray(), Charsets.UTF_8)
        assertTrue(Regex("""name="title"\r?\n(?:[^\r\n]+\r?\n)*\r?\nLetrero\r?\n""").containsMatchIn(second), "a typed title is sent")
    }
}
