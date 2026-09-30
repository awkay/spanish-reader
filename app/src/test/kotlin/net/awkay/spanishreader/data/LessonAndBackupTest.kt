package net.awkay.spanishreader.data

import kotlinx.coroutines.test.runTest
import net.awkay.spanishreader.core.backup.BackupFormatException
import net.awkay.spanishreader.core.vocab.WordStatus
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LessonAndBackupTest : DbTestBase() {
    private val lessons by lazy { LessonRepository(db) { now } }
    private val vocab by lazy { VocabRepository(db) { now } }
    private val backup by lazy { BackupService(db) { now } }

    @Test
    fun importCleansTextAndDerivesTitle() = runTest {
        val id = lessons.import("  ", "El gato\r\nnegro duerme.\r\n\r\nFin.")
        val l = lessons.get(id)!!
        assertEquals("El gato negro duerme.\n\nFin.", l.text)
        assertEquals("El gato negro duerme.", l.title)
    }

    @Test
    fun importRejectsBlankText() = runTest {
        assertFailsWith<IllegalArgumentException> { lessons.import("t", " \n​ ") }
    }

    @Test
    fun backupRoundTripMergesWithoutDuplicates() = runTest {
        lessons.import("Uno", "Hola.")
        vocab.tap("hola", "Hola.")
        vocab.annotate("hola", "hola", "hello")
        val json = backup.exportJson()

        // Local changes after the export are newer and must survive the restore.
        now = 5_000
        vocab.setStatus("hola", WordStatus.KNOWN)
        val summary = backup.importJson(json)
        assertEquals(RestoreSummary(lessonsAdded = 0, lessonsSkipped = 1, vocabUpdated = 0, vocabUnchanged = 1), summary)
        assertEquals(WordStatus.KNOWN, vocab.get("hola")!!.status)

        // Into an empty database everything comes back.
        db.clearAllTables()
        backup.importJson(json)
        assertEquals(listOf("Uno"), db.lessons().getAll().map { it.title })
        val restored = vocab.get("hola")!!
        assertEquals(WordStatus.LEVEL_1, restored.status)
        assertEquals("hello", restored.translation)
    }

    @Test
    fun badBackupIsRejected() = runTest {
        assertFailsWith<BackupFormatException> { backup.importJson("{}") }
    }

    @Test
    fun annotateOnlyFillsMissingFields() = runTest {
        vocab.tap("banco", "Fui al banco.")
        vocab.annotate("banco", "banco", "bank")
        vocab.annotate("banco", "banco", "bench")
        assertEquals("bank", vocab.get("banco")!!.translation)
    }
}
