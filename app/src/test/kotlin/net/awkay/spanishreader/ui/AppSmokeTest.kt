package net.awkay.spanishreader.ui

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import net.awkay.spanishreader.MainActivity
import net.awkay.spanishreader.ShareInbox
import net.awkay.spanishreader.SpanishReaderApp
import net.awkay.spanishreader.core.vocab.WordStatus
import org.junit.Before
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Drives the real UI under Robolectric: share → import → reader → tap a word → status change. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppSmokeTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<SpanishReaderApp>()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
    }

    private fun waitForText(text: String) = compose.waitUntil(10_000) {
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun shareImportReadAndTap() {
        ActivityScenario.launch(MainActivity::class.java)
        waitForText("No lessons yet")

        val share = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Cuento")
            .putExtra(Intent.EXTRA_TEXT, "El perro come pan.\nLa casa es grande.")
        compose.runOnUiThread { ShareInbox.offer(app, share) }
        waitForText("Create lesson")
        compose.onNodeWithText("Create lesson").performClick()

        waitForText("Page 1 of 1")
        compose.onNodeWithText("Finish lesson").assertIsDisplayed()

        // Glossing isn't configured, so the sheet opens and explains that; the tap still adds the word at LEVEL_1.
        // (Robolectric's fake font metrics make the exact word hit approximate, so check whichever word was tapped.)
        compose.onNodeWithText("Cuento").assertIsDisplayed()
        tapWord("perro")
        waitForText("API key is required")
        val entries = { runBlocking { app.database.vocab().getAllOnce() } }
        compose.waitUntil(5_000) { entries().size == 1 }
        val tapped = entries().single()
        assertEquals(WordStatus.LEVEL_1, tapped.status)
        assertTrue(tapped.contextSentence!!.contains(tapped.form, ignoreCase = true))
        compose.onNodeWithText("Known ✓").performClick()
        compose.waitUntil(5_000) { runBlocking { app.vocab.get(tapped.form)?.status } == WordStatus.KNOWN }

        compose.onNodeWithContentDescription("Back").performClick()
        waitForText("Cuento")
        waitForText("8 words · 7 new · 0 learning")
    }

    /** Clicks the link annotation for [word] inside the page text. */
    private fun tapWord(word: String) {
        val node = compose.onNode(hasText("El perro come pan.", substring = true))
        val layout = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(layout)
        val text = layout.single().layoutInput.text.text
        val offset = text.indexOf(word) + 1
        val box = layout.single().getBoundingBox(offset)
        node.performTouchInput { click(box.center) }
    }
}
