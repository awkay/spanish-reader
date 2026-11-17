package net.awkay.spanishreader.ui

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import net.awkay.spanishreader.MainActivity
import net.awkay.spanishreader.SpanishReaderApp
import net.awkay.spanishreader.core.practice.PhraseDecks
import net.awkay.spanishreader.data.AppSettings
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The practice menu's say-it and listen modes: reveal, move on, stop. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PhraseScreenTest {
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

    private fun openFromMenu(item: String) {
        compose.onNodeWithContentDescription("Verb practice").performClick()
        waitForText(item)
        compose.onNodeWithText(item).performClick()
    }

    @Test
    fun sayItRevealsTheBlankThenMovesOnAndStops() {
        ActivityScenario.launch(MainActivity::class.java)
        waitForText("No lessons yet")
        openFromMenu("🗣 Say it: question words")
        val total = PhraseDecks.QUESTIONS.size
        waitForText("Show me")
        compose.onNodeWithText("1/$total").assertExists()
        compose.onNodeWithText("Show me").performClick()
        waitForText("Continue ›")
        compose.onNodeWithText("Continue ›").performClick()
        waitForText("2/$total")
        compose.onNodeWithText("Show me").assertExists()
        compose.onNodeWithContentDescription("Stop").performClick()
        waitForText("No lessons yet")
    }

    @Test
    fun listenHidesTheSentenceUntilShowAnswer() {
        ActivityScenario.launch(MainActivity::class.java)
        waitForText("No lessons yet")
        openFromMenu("🎧 Listen: everyday verbs")
        waitForText("Show answer")
        compose.onNodeWithContentDescription("Play").assertExists()
        // No sentence of the deck is on screen before the answer is shown.
        compose.waitUntil(2_000) { PhraseDecks.VERBS.none { c -> compose.onAllNodes(hasText(c.english)).fetchSemanticsNodes().isNotEmpty() } }
        compose.onNodeWithText("Show answer").performClick()
        compose.waitUntil(5_000) { PhraseDecks.VERBS.any { c -> compose.onAllNodes(hasText(c.english)).fetchSemanticsNodes().isNotEmpty() } }
        compose.onNodeWithText("Next ›").performClick()
        waitForText("2/${PhraseDecks.VERBS.size}")
        compose.onNodeWithText("Show answer").assertExists()
    }
}
