package net.awkay.spanishreader.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import net.awkay.spanishreader.MainActivity
import net.awkay.spanishreader.SpanishReaderApp
import net.awkay.spanishreader.core.gloss.GlossProvider
import net.awkay.spanishreader.data.AiProfile
import net.awkay.spanishreader.data.AppSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Settings → AI accounts and models: switch the one in use, copy one to try another model with the same key. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsProfilesTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<SpanishReaderApp>()
    private val router = AiProfile("r", provider = GlossProvider.OPENROUTER.name, apiKey = "or-key", model = "deepseek/deepseek-v4.1-flash")
    private val glm = AiProfile("g", name = "z.ai plan", provider = GlossProvider.ZAI_RESPONSES.name, apiKey = "z", model = "glm-5.3")

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        runBlocking(Dispatchers.IO) {
            app.database.clearAllTables()
            app.settings.update { AppSettings(aiProfiles = listOf(glm, router), activeProfileId = "g") }
        }
    }

    @After
    fun tearDown() = runBlocking(Dispatchers.IO) { app.settings.update { AppSettings() } }

    private fun waitForText(text: String) = compose.waitUntil(10_000) {
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun switchAndCopyProfiles() {
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForText("No lessons yet")
            compose.onNodeWithContentDescription("Settings").performClick()
            waitForText("AI accounts and models")
            System.getenv("SCREENSHOT_DIR")?.let { dir ->
                compose.waitForIdle()
                File(dir).mkdirs()
                File(dir, "settings-ai.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            compose.onNodeWithContentDescription("Use ${router.label}").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasContentDescription("Use ${router.label}") and isSelected()).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals("deepseek/deepseek-v4.1-flash", runBlocking(Dispatchers.IO) { app.settings.current().primaryGlosser.model })

            compose.onNodeWithContentDescription("Delete ${router.label}").performClick()
            waitForText("Its API key is deleted")
            compose.onNodeWithText("Cancel").performClick()
            compose.onNodeWithContentDescription("Copy ${router.label}").performClick()
            waitForText("AI account / model")
            compose.onNodeWithText("Save").performScrollTo().performClick()
            waitForText("${router.label} (copy)")
            val copy = runBlocking(Dispatchers.IO) { app.settings.current().aiProfiles.last() }
            assertEquals("or-key", copy.apiKey) // same key, ready for another model
            assertEquals("${router.label} (copy)", copy.name)
        }
    }
}
