package net.awkay.spanishreader.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
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
import net.awkay.spanishreader.core.chat.ChatAi
import net.awkay.spanishreader.core.chat.ChatPrompt
import net.awkay.spanishreader.core.gloss.TextCompleter
import net.awkay.spanishreader.data.AppSettings
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders the conversation screen to SCREENSHOT_DIR when it is set; a manual visual check, skipped otherwise. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatScreenshotTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<SpanishReaderApp>()
    private var turn = 0

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        ChatBackend.override = {
            object : ChatBackend {
                override suspend fun ai() = ChatAi(object : TextCompleter {
                    override suspend fun complete(system: String, user: String): String = if (system == ChatPrompt.PARTNER_SYSTEM) {
                        if (turn++ == 0) """{"reply": [{"es": "¡Qué bien! A mí también me encanta la playa.", "en": "Great! I love the beach too."}, {"es": "¿Hacía mucho calor?", "en": "Was it very hot?"}]}"""
                        else """{"reply": [{"es": "¡Qué rico! El pescado frito con limón es lo mejor.", "en": "Yum! Fried fish with lime is the best."}, {"es": "¿Y qué tomaron?", "en": "And what did you drink?"}]}"""
                    } else if ("Ayer" in user) {
                        """{"english": "Yesterday I went to the beach with my family and we ate sandwiches.", "corrected": "Ayer fui a la playa con mi familia y comimos sándwiches.", "changes": [{"from": "al playa", "to": "a la playa", "why": "Playa is feminine; only a + el contracts to al."}, {"from": "we ate", "to": "comimos", "why": "'We ate' is comimos (comer, preterite, nosotros)."}], "ok": false}"""
                    } else {
                        """{"english": "Yes, it was very hot and we ate fried fish.", "corrected": "Sí, hacía mucho calor y comimos pescado frito.", "changes": [], "ok": true}"""
                    }
                })
                override suspend fun speech(sentence: String) = File.createTempFile("say", ".mp3")
                override suspend fun play(file: File) {}
                override suspend fun transcribe(recording: File) = ""
            }
        }
        runBlocking(Dispatchers.IO) {
            app.database.clearAllTables()
            app.settings.update { AppSettings(preGlossOnImport = false) }
        }
    }

    @After
    fun tearDown() {
        ChatBackend.override = null
    }

    private fun waitForText(text: String) = compose.waitUntil(10_000) {
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun shot(dir: String, name: String) {
        compose.waitForIdle()
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        File(dir).mkdirs()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun say(text: String) {
        compose.onNode(hasSetTextAction()).performTextInput(text)
        compose.onNodeWithContentDescription("Send").performClick()
    }

    @Test
    fun renderChat() {
        val dir = System.getenv("SCREENSHOT_DIR") ?: return
        ActivityScenario.launch(MainActivity::class.java)
        waitForText("No lessons yet")
        compose.onNodeWithContentDescription("Verb practice").performClick()
        waitForText("💬 Conversation")
        compose.onNodeWithText("💬 Conversation").performClick()
        waitForText("What do you want to talk about?")
        compose.onNode(hasSetTextAction()).performTextInput("Un día en la playa")
        shot(dir, "chat-topic")
        compose.onNodeWithText("Start").performClick()
        waitForText("Escribe o habla")
        say("Ayer fui al playa con mi familia y we ate sándwiches.")
        waitForText("Tap to show text")
        compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("What I said")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Tap to show text").performClick()
        compose.onNodeWithContentDescription("English").performClick()
        compose.onNodeWithContentDescription("What I said").performClick()
        waitForText("Better:")
        say("Sí, hacía mucho calor y comimos pescado frito.")
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Tap to show text")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("What I said")).fetchSemanticsNodes().size == 2 }
        shot(dir, "chat-conversation")
    }
}
