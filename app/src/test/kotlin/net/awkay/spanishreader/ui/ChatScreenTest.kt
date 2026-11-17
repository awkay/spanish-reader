package net.awkay.spanishreader.ui

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import net.awkay.spanishreader.MainActivity
import net.awkay.spanishreader.SpanishReaderApp
import net.awkay.spanishreader.chat.ChatBackend
import net.awkay.spanishreader.chat.ChatViewModel
import net.awkay.spanishreader.core.chat.ChatAi
import net.awkay.spanishreader.core.chat.ChatPrompt
import net.awkay.spanishreader.core.gloss.TextCompleter
import net.awkay.spanishreader.data.AppSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Conversation mode with a fake AI, voice and speech recognizer: reply heard, then read, then translated; saved as a lesson. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChatScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<SpanishReaderApp>()
    private val played = mutableListOf<String>()
    private val asked = mutableListOf<String>()

    private val fake = object : ChatBackend {
        override suspend fun ai() = ChatAi(object : TextCompleter {
            override suspend fun complete(system: String, user: String): String {
                asked += user
                return if (system == ChatPrompt.PARTNER_SYSTEM) {
                    """{"reply": [{"es": "¡Qué rico!", "en": "How delicious!"}, {"es": "¿Cuál es tu taco favorito?", "en": "What's your favorite taco?"}]}"""
                } else {
                    """{"english": "I like tacos.", "corrected": "Me gustan los tacos.", "changes": [{"from": "gusta tacos", "to": "gustan los tacos", "why": "Tacos is plural."}], "ok": false}"""
                }
            }
        })

        override suspend fun speech(sentence: String): File = File.createTempFile("say", ".mp3").also { it.writeText(sentence) }

        override suspend fun play(file: File) {
            played += file.readText()
        }

        override suspend fun transcribe(recording: File) = "Ayer fui al store"
    }

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        ChatBackend.override = { fake }
        runBlocking(Dispatchers.IO) {
            app.database.clearAllTables()
            app.settings.update { AppSettings(preGlossOnImport = false) }
        }
    }

    @After
    fun tearDown() {
        ChatBackend.override = null
    }

    private fun waitForText(text: String) = compose.waitUntil(30_000) {
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun replyIsHeardThenReadThenTranslatedAndTheChatBecomesALesson() {
        ActivityScenario.launch(MainActivity::class.java).use { steps() }
    }

    private fun steps() {
        waitForText("No lessons yet")
        compose.onNodeWithContentDescription("Verb practice").performClick()
        waitForText("💬 Conversation")
        compose.onNodeWithText("💬 Conversation").performClick()

        waitForText("What do you want to talk about?")
        compose.onNodeWithText("La comida").performClick()
        compose.onNodeWithText("Start").performClick()

        waitForText("Escribe o habla")
        compose.onNode(hasSetTextAction()).performTextInput("Me gusta tacos")
        compose.onNodeWithContentDescription("Send").performClick()

        // Heard first: both sentences played, the text hidden until tapped.
        waitForText("Tap to show text")
        compose.waitUntil(10_000) { played.size == 2 }
        assertEquals(listOf("¡Qué rico!", "¿Cuál es tu taco favorito?"), played)
        compose.onNodeWithContentDescription("English").assertDoesNotExist()
        compose.onNodeWithText("Tap to show text").performClick()
        waitForText("¡Qué rico! ¿Cuál es tu taco favorito?")
        compose.onNodeWithContentDescription("English").performClick()
        waitForText("How delicious! What's your favorite taco?")

        // The learner's own bubble: what they meant and the smallest fix.
        compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("What I said")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("What I said").performClick()
        waitForText("Better: Me gustan los tacos.")
        waitForText("I like tacos.")
        assertTrue(asked.any { "\"learnerJustSaid\":\"Me gusta tacos\"" in it })

        compose.onNodeWithText("Finish").performClick()
        waitForText("Page 1 of 1")
        val lesson = runBlocking(Dispatchers.IO) { app.lessons.all().single() }
        assertEquals("Me gustan los tacos.\n\n¡Qué rico! ¿Cuál es tu taco favorito?", lesson.text)
        assertTrue(lesson.title.startsWith("Conversación: La comida · "))
        runBlocking(Dispatchers.IO) {
            assertEquals("How delicious!", app.phrases.translation("¡Qué rico!"))
            assertEquals("What's your favorite taco?", app.phrases.translation("¿Cuál es tu taco favorito?"))
            assertEquals("I like tacos.", app.phrases.translation("Me gustan los tacos."))
        }
    }

    @Test
    fun speechLandsInTheBoxToFixBeforeSending() {
        val vm = ChatViewModel(app, fake)
        vm.start("Viajes")
        vm.setDraft("Hola,")
        val clip = File.createTempFile("speech", ".m4a")
        vm.transcribe(clip)
        compose.waitUntil(10_000) { !vm.ui.value.transcribing }
        assertEquals("Hola, Ayer fui al store", vm.ui.value.draft)
        assertTrue(vm.ui.value.draftVoice)
        assertTrue(!clip.exists())
    }
}
