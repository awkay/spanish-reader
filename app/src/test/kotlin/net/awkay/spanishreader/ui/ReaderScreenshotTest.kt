package net.awkay.spanishreader.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import net.awkay.spanishreader.MainActivity
import net.awkay.spanishreader.ShareInbox
import net.awkay.spanishreader.SpanishReaderApp
import net.awkay.spanishreader.core.vocab.WordStatus
import net.awkay.spanishreader.data.AppSettings
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders the reader to build/reader.png when SCREENSHOT_DIR is set; a manual visual check, skipped otherwise. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderScreenshotTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<SpanishReaderApp>()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        runBlocking(Dispatchers.IO) {
            app.database.clearAllTables()
            app.settings.update { AppSettings(preGlossOnImport = false) }
        }
    }

    @Test
    fun renderReader() {
        val dir = System.getenv("SCREENSHOT_DIR") ?: return
        runBlocking(Dispatchers.IO) {
            listOf("perro" to WordStatus.LEVEL_1, "casa" to WordStatus.RECOGNIZED, "grande" to WordStatus.FAMILIAR,
                "come" to WordStatus.LEARNED, "el" to WordStatus.KNOWN, "la" to WordStatus.KNOWN, "es" to WordStatus.KNOWN,
                "y" to WordStatus.KNOWN, "de" to WordStatus.KNOWN)
                .forEach { (f, s) -> app.vocab.setStatus(f, s) }
        }
        ActivityScenario.launch(MainActivity::class.java)
        compose.runOnUiThread {
            ShareInbox.offer(app, android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                .putExtra(android.content.Intent.EXTRA_SUBJECT, "Cuento")
                .putExtra(android.content.Intent.EXTRA_TEXT,
                    "El perro come pan en la casa grande. La casa es de mi abuela y el perro duerme en el jardín todas las tardes. " +
                        "Mi abuela dice que el perro es muy inteligente, pero yo creo que solamente tiene hambre."))
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Create lesson")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Create lesson")).performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Page 1 of 1")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        File(dir).mkdirs()
        File(dir, "reader.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}


