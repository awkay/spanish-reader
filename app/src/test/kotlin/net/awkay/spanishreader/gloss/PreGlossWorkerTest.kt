package net.awkay.spanishreader.gloss

import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import net.awkay.spanishreader.SpanishReaderApp
import net.awkay.spanishreader.core.gloss.GlossProvider
import net.awkay.spanishreader.core.gloss.GlosserConfig
import net.awkay.spanishreader.core.text.Tokenizer
import net.awkay.spanishreader.data.AppSettings
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Runs the real worker against a fake OpenAI-compatible server and checks it glosses only the page window. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PreGlossWorkerTest {
    private val app get() = ApplicationProvider.getApplicationContext<SpanishReaderApp>()
    private val server = MockWebServer()
    private val glossed = Collections.synchronizedList(mutableListOf<String>())

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
                val system = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content
                val user = body["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonPrimitive.content
                if ("idioms" in system) return phraseReply(user)
                val items = Json.parseToJsonElement(user.substringAfter('\n')).jsonObject["items"]!!.jsonArray
                val glosses = buildJsonArray {
                    for (item in items) {
                        val o = item as JsonObject
                        val form = o["form"]!!.jsonPrimitive.content
                        glossed += Tokenizer.normalize(form)
                        add(buildJsonObject {
                            put("id", o["id"]!!.jsonPrimitive.content)
                            put("form", form)
                            put("lemma", form)
                            put("partOfSpeech", "x")
                            put("meaningInContext", "meaning of $form")
                        })
                    }
                }
                val content = buildJsonObject { put("glosses", glosses) }.toString()
                val reply = buildJsonObject {
                    put("choices", buildJsonArray { add(buildJsonObject { put("message", buildJsonObject { put("content", content) }) }) })
                }
                return MockResponse.Builder().code(200).body(reply.toString()).build()
            }
        }
        server.start()
    }

    private val scannedSentences = Collections.synchronizedList(mutableListOf<String>())

    /** Phrase scan: reports "p?sawa p?sawb" as an expression in every sentence. */
    private fun phraseReply(user: String): MockResponse {
        val items = Json.parseToJsonElement(user.substringAfter('\n')).jsonObject["sentences"]!!.jsonArray
        val sentences = buildJsonArray {
            for (item in items) {
                val o = item as JsonObject
                val text = o["sentence"]!!.jsonPrimitive.content
                scannedSentences += text
                add(buildJsonObject {
                    put("id", o["id"]!!.jsonPrimitive.content)
                    put("phrases", buildJsonArray {
                        add(buildJsonObject { put("phrase", text.split(" ").take(2).joinToString(" ")); put("meaning", "a phrase") })
                    })
                })
            }
        }
        val content = buildJsonObject { put("sentences", sentences) }.toString()
        val reply = buildJsonObject {
            put("choices", buildJsonArray { add(buildJsonObject { put("message", buildJsonObject { put("content", content) }) }) })
        }
        return MockResponse.Builder().code(200).body(reply.toString()).build()
    }

    /** The app's database and settings outlive this test in the Robolectric JVM; leave them as a fresh install. */
    @After
    fun tearDown() = runBlocking(Dispatchers.IO) {
        server.close()
        app.database.clearAllTables()
        app.settings.updateProvider(GlosserConfig(GlossProvider.OPENAI_COMPATIBLE))
        app.settings.update { AppSettings() }
    }

    @Test
    fun glossesOnlyTheRequestedPagesAndSkipsCachedAndKnownWords() = runBlocking {
        app.settings.update { it.copy(provider = GlossProvider.OPENAI_COMPATIBLE, wordsPerPage = 50) }
        app.settings.updateProvider(GlosserConfig(GlossProvider.OPENAI_COMPATIBLE, server.url("/v1").toString(), model = "m"))
        // 4 pages of 50 words: page N uses words "pN_w0" … so we can tell pages apart.
        val text = (0 until 4).joinToString("\n") { p -> (0 until 10).joinToString(" ") { s -> (0 until 5).joinToString(" ") { w -> "p${'a' + p}s${'a' + s}w${'a' + w}" } } + "." }
        val id = app.lessons.import("T", text)
        app.vocab.setStatus("pbsawa", net.awkay.spanishreader.core.vocab.WordStatus.KNOWN)

        suspend fun run(from: Int, count: Int): ListenableWorker.Result =
            TestListenableWorkerBuilder<PreGlossWorker>(app)
                .setInputData(workDataOf(PreGlossWorker.KEY_LESSON to id, PreGlossWorker.KEY_FROM_PAGE to from, PreGlossWorker.KEY_PAGE_COUNT to count))
                .build().doWork()

        assertIs<ListenableWorker.Result.Success>(run(1, 2))
        val pages = glossed.map { it.substring(0, 2) }.toSet()
        assertEquals(setOf("pb", "pc"), pages)
        assertEquals(99, glossed.size) // 2 pages × 50 words, minus the known one
        assertEquals("meaning of pcsawa", app.glossCache.getByForm("pcsawa")?.meaningInContext)

        // Each page is one sentence here: pages 1 and 2 were scanned for expressions, once.
        assertEquals(listOf("pb", "pc"), scannedSentences.map { it.substring(0, 2) })
        assertEquals("a phrase", app.phrases.forSentence(scannedSentences[0]).single().meaning)

        glossed.clear()
        scannedSentences.clear()
        assertIs<ListenableWorker.Result.Success>(run(2, 2)) // page 2 is cached already; only page 3 is new
        assertEquals(setOf("pd"), glossed.map { it.substring(0, 2) }.toSet())
        assertEquals(listOf("pd"), scannedSentences.map { it.substring(0, 2) })
    }
}
