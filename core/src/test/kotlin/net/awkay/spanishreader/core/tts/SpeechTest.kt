package net.awkay.spanishreader.core.tts

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import net.awkay.spanishreader.core.gloss.RetryPolicy
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SpeechTest {
    private val dir: File = Files.createTempDirectory("tts").toFile()
    private val server = MockWebServer().apply { start() }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
        server.close()
    }

    private class FakeSynth(override val cacheKey: String = "fake voice/1", var fail: Boolean = false) : SentenceSynthesizer {
        var calls = 0
        override val fileExtension = "wav"
        override suspend fun synthesizeTo(text: String, file: File) {
            calls++
            if (fail) {
                file.writeText("partial")
                throw IOException("boom")
            }
            file.writeText("audio:$text")
        }
    }

    @Test
    fun `cache synthesizes once per text and voice`() = runTest {
        val cache = SentenceAudioCache(dir)
        val synth = FakeSynth()
        val a = cache.ensure(synth, " Hola. ")
        val b = cache.ensure(synth, "Hola.")
        assertEquals(a, b)
        assertEquals(1, synth.calls)
        assertEquals("audio:Hola.", a.readText())
        assertTrue(a.name.endsWith(".wav"))
        assertNotEquals(a, cache.fileFor(FakeSynth("other"), "Hola."))
    }

    @Test
    fun `failed synthesis leaves nothing cached`() = runTest {
        val cache = SentenceAudioCache(dir)
        val synth = FakeSynth(fail = true)
        assertFailsWith<IOException> { cache.ensure(synth, "Hola.") }
        assertFalse(cache.fileFor(synth, "Hola.").exists())
        assertTrue(cache.fileFor(synth, "Hola.").parentFile.listFiles().orEmpty().isEmpty())
        synth.fail = false
        assertEquals("audio:Hola.", cache.ensure(synth, "Hola.").readText())
    }

    @Test
    fun `google tts request shape and decoding`() = runTest {
        val audio = byteArrayOf(1, 2, 3, 4)
        server.enqueue(MockResponse.Builder().code(200).body("""{"audioContent":"${Base64.getEncoder().encodeToString(audio)}"}""").build())
        val synth = GoogleCloudSynthesizer("key", "es-US-Chirp3-HD-Aoede", baseUrl = server.url("/").toString())
        assertContentEquals(audio, synth.synthesize("¿Qué tal?"))
        val req = server.takeRequest()
        assertEquals("/v1/text:synthesize", req.url.encodedPath)
        assertEquals("key", req.headers["X-Goog-Api-Key"])
        val body = Json.parseToJsonElement(req.body!!.utf8()).jsonObject
        assertEquals("¿Qué tal?", body["input"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("es-US", body["voice"]!!.jsonObject["languageCode"]!!.jsonPrimitive.content)
        assertEquals("es-US-Chirp3-HD-Aoede", body["voice"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("MP3", body["audioConfig"]!!.jsonObject["audioEncoding"]!!.jsonPrimitive.content)
        assertEquals("mp3", synth.fileExtension)
    }

    @Test
    fun `google tts retries 5xx and rejects bodies without audio`() = runTest {
        server.enqueue(MockResponse.Builder().code(503).build())
        server.enqueue(MockResponse.Builder().code(200).body("{}").build())
        val synth = GoogleCloudSynthesizer("k", "es-MX-Voice", baseUrl = server.url("/").toString(), retry = RetryPolicy(sleep = {}))
        assertFailsWith<IOException> { synth.synthesize("Hola") }
        assertEquals(2, server.requestCount)
    }
}
