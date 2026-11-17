package net.awkay.spanishreader.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import net.awkay.spanishreader.MainActivity
import net.awkay.spanishreader.SpanishReaderApp
import net.awkay.spanishreader.data.AppSettings
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders the say-it and listen screens to SCREENSHOT_DIR when it is set; a manual visual check, skipped otherwise. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhraseScreenshotTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<SpanishReaderApp>()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        runBlocking(Dispatchers.IO) {
            app.database.clearAllTables()
            app.settings.update { AppSettings() }
        }
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

    @Test
    fun renderPhraseScreens() {
        val dir = System.getenv("SCREENSHOT_DIR") ?: return
        ActivityScenario.launch(MainActivity::class.java)
        waitForText("No lessons yet")
        for ((item, name, reveal) in listOf(
            Triple("🗣 Say it: everyday verbs", "phrases-say", "Show me"),
            Triple("🎧 Listen: question words", "phrases-listen", "Show answer"),
        )) {
            compose.onNodeWithContentDescription("Verb practice").performClick()
            waitForText(item)
            compose.onNodeWithText(item).performClick()
            waitForText(reveal)
            shot(dir, name)
            compose.onNodeWithText(reveal).performClick()
            shot(dir, "$name-shown")
            compose.onNodeWithContentDescription("Stop").performClick()
            waitForText("No lessons yet")
        }
    }
}
