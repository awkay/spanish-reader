package net.awkay.spanishreader.ui

import android.content.Intent
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
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
import net.awkay.spanishreader.MainActivity
import net.awkay.spanishreader.ShareInbox
import net.awkay.spanishreader.SpanishReaderApp
import net.awkay.spanishreader.core.gloss.Gloss
import net.awkay.spanishreader.core.gloss.GlossProvider
import net.awkay.spanishreader.core.gloss.GlosserConfig
import net.awkay.spanishreader.core.text.Tokenizer
import net.awkay.spanishreader.core.vocab.WordStatus
import net.awkay.spanishreader.data.AppSettings
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A KNOWN word without a gloss for its sentence opens with what the device already has and makes no request to the
 * glossing provider until "Look up in this sentence"; a NEW word still looks itself up at once.
 * Every word of each lesson is the same one, since Robolectric's font metrics make exact word hits approximate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class KnownWordSheetTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<SpanishReaderApp>()
    private val server = MockWebServer()
    private val glossed = Collections.synchronizedList(mutableListOf<String>())
    private val translated = Collections.synchronizedList(mutableListOf<String>())

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
                val system = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content
                val user = body["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonPrimitive.content
                val payload = Json.parseToJsonElement(user.substringAfter('\n').substringBefore("\n")).jsonObject
                val content = if ("idioms" in system) {
                    buildJsonObject {
                        put("sentences", buildJsonArray {
                            for (item in payload["sentences"]!!.jsonArray) {
                                translated += (item as JsonObject)["sentence"]!!.jsonPrimitive.content
                                add(buildJsonObject {
                                    put("id", item["id"]!!.jsonPrimitive.content)
                                    put("translation", "English sentence")
                                    put("phrases", buildJsonArray {})
                                })
                            }
                        })
                    }
                } else {
                    buildJsonObject {
                        put("glosses", buildJsonArray {
                            for (item in payload["items"]!!.jsonArray) {
                                val form = (item as JsonObject)["form"]!!.jsonPrimitive.content
                                glossed += Tokenizer.normalize(form)
                                add(buildJsonObject {
                                    put("id", item["id"]!!.jsonPrimitive.content)
                                    put("form", form)
                                    put("lemma", form)
                                    put("partOfSpeech", "verb")
                                    put("meaningInContext", "contextual meaning of $form")
                                })
                            }
                        })
                    }
                }.toString()
                val reply = buildJsonObject {
                    put("choices", buildJsonArray { add(buildJsonObject { put("message", buildJsonObject { put("content", content) }) }) })
                }
                return MockResponse.Builder().code(200).body(reply.toString()).build()
            }
        }
        server.start()
        runBlocking(Dispatchers.IO) {
            app.database.clearAllTables()
            app.settings.update { AppSettings(preGlossOnImport = false) }
            app.settings.useConfig(GlosserConfig(GlossProvider.OPENAI_COMPATIBLE, server.url("/v1").toString(), model = "m"))
        }
    }

    /** The app's database and settings outlive this test in the Robolectric JVM; leave them as a fresh install. */
    @After
    fun tearDown() = runBlocking(Dispatchers.IO) {
        server.close()
        app.database.clearAllTables()
        app.settings.update { AppSettings() }
    }

    private fun waitForText(text: String) = compose.waitUntil(10_000) {
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun openLesson(text: String) {
        ActivityScenario.launch(MainActivity::class.java)
        compose.runOnUiThread {
            ShareInbox.offer(app, Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Cuento").putExtra(Intent.EXTRA_TEXT, text))
        }
        waitForText("Create lesson")
        compose.onNode(hasText("Create lesson")).performClick()
        waitForText("Page 1 of 1")
    }

    /** Clicks the second word of the paragraph containing [snippet]. */
    private fun tapSecondWord(snippet: String) {
        val node = compose.onNode(hasText(snippet, substring = true))
        val layout = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(layout)
        val text = layout.single().layoutInput.text.text
        val box = layout.single().getBoundingBox(text.indexOf(' ') + 2)
        node.performTouchInput { click(box.center) }
    }

    @Test
    fun knownWordShowsStoredMeaningsWithoutAskingTheProviderUntilAsked() {
        val sentence = "Fue fue fue fue."
        runBlocking(Dispatchers.IO) {
            app.vocab.setStatus("fue", WordStatus.KNOWN)
            app.vocab.annotate("fue", "ir", "went")
            app.glossCache.put("fue", "Ayer fue al mercado.", Gloss("fue", "ir", "verb", "went", otherMeanings = listOf("left")))
            app.glossCache.put("fue", "Él fue a casa.", Gloss("fue", "ir", "verb", "Went."))
            app.vocab.setStatus("fui", WordStatus.LEVEL_1)
            app.vocab.annotate("fui", "ir", "I went")
            app.glossCache.put("fui", "Fui al cine.", Gloss("fui", "ir", "verb", "I went", lemmaMeaning = "to go"))
        }
        openLesson(sentence)
        tapSecondWord("fue fue")

        waitForText("Likely meanings (not from this sentence)")
        waitForText("×2")
        waitForText("From related forms (fui)")
        waitForText("I went")
        waitForText("Other meanings: left")
        waitForText("from ir · verb · to go")
        compose.waitForIdle()
        Thread.sleep(300)
        compose.waitForIdle()
        assertEquals(0, server.requestCount, "no request to the glossing provider before the button")
        assertTrue(glossed.isEmpty() && translated.isEmpty())

        compose.onNode(hasText("Look up in this sentence", substring = true)).performScrollTo().performClick()
        waitForText("contextual meaning of fue")
        assertEquals(listOf("fue"), glossed.toList())
        waitForText("English sentence") // the sentence's translation comes with the explicit lookup
        assertNotNull(runBlocking(Dispatchers.IO) { app.glossCache.get("fue", sentence) }, "the lookup is cached for this sentence")
        assertEquals(WordStatus.KNOWN, runBlocking(Dispatchers.IO) { app.vocab.get("fue")?.status })
    }

    @Test
    fun knownWordWithNothingStoredOffersTheLookup() {
        runBlocking(Dispatchers.IO) { app.vocab.setStatus("casa", WordStatus.KNOWN) }
        openLesson("Casa casa casa casa.")
        tapSecondWord("casa casa")
        waitForText("No meaning stored for this word yet")
        waitForText("Look up in this sentence")
        waitForText("Translate sentence")
        compose.waitForIdle()
        assertEquals(0, server.requestCount)
    }

    @Test
    fun knownWordWithAGlossForThisSentenceShowsItAsBefore() {
        val sentence = "Casa casa casa casa."
        runBlocking(Dispatchers.IO) {
            app.vocab.setStatus("casa", WordStatus.KNOWN)
            app.glossCache.put("casa", sentence, Gloss("casa", "casa", "noun", "house in this sentence"))
        }
        openLesson(sentence)
        tapSecondWord("casa casa")
        waitForText("house in this sentence")
        compose.waitForIdle()
        assertTrue(compose.onAllNodes(hasText("Likely meanings", substring = true)).fetchSemanticsNodes().isEmpty())
        assertTrue(glossed.isEmpty())
    }

    @Test
    fun newWordStillLooksItselfUpAtOnce() {
        openLesson("Perro perro perro perro.")
        tapSecondWord("perro perro")
        waitForText("contextual meaning of perro")
        assertEquals(listOf("perro"), glossed.toList())
        assertTrue(compose.onAllNodes(hasText("Likely meanings", substring = true)).fetchSemanticsNodes().isEmpty())
        compose.waitUntil(5_000) { runBlocking(Dispatchers.IO) { app.vocab.get("perro")?.status } == WordStatus.LEVEL_1 }
    }
}
