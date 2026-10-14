package net.awkay.spanishreader.core.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SyncTest {
    private val a = BackupLesson("A", "Hola.", 1, currentPage = 2)
    private val b = BackupLesson("B", "Adiós.", 2, videoId = "abcdefghijk", sourceUrl = "https://youtu.be/abcdefghijk")
    private val v1 = BackupVocab("hola", "hola", 5, null, null, 1, 2)
    private val v2 = BackupVocab("adiós", null, 1, null, null, 1, 3)

    @Test
    fun `snapshot order and fingerprint ignore row order and export time`() {
        val s1 = Sync.snapshot(listOf(b, a), listOf(v1, v2), 10)
        assertEquals(listOf("A", "B"), s1.lessons.map { it.title })
        assertEquals(listOf("adiós", "hola"), s1.vocab.map { it.form })
        val s2 = Sync.snapshot(listOf(a, b), listOf(v2, v1), 99)
        assertEquals(Sync.fingerprint(s1), Sync.fingerprint(s2))
        assertEquals(Sync.fingerprint(s1), Sync.fingerprint(BackupFile(exportedAtMillis = 5, lessons = listOf(b, a), vocab = listOf(v1, v2))))
        assertNotEquals(Sync.fingerprint(s1), Sync.fingerprint(Sync.snapshot(listOf(a.copy(currentPage = 3), b), listOf(v1, v2), 10)))
        assertNotEquals(Sync.fingerprint(s1), Sync.fingerprint(Sync.snapshot(listOf(a, b), listOf(v1, v2.copy(status = 2)), 10)))
    }

    @Test
    fun `replace plan keeps matching lessons, inserts new ones and deletes the rest`() {
        val local = listOf(1L to a.copy(currentPage = 0), 2L to BackupLesson("C", "Gone.", 3), 3L to a)
        val incoming = listOf(a.copy(title = " A ", currentPage = 5), b, b)
        val plan = Sync.replacePlan(local, incoming)
        assertEquals(listOf(1L to a.copy(title = " A ", currentPage = 5)), plan.update)
        assertEquals(listOf(b), plan.insert)
        assertEquals(listOf(3L, 2L), plan.delete)
    }

    @Test
    fun `local changes are those since the last sync`() {
        val s = Sync.snapshot(listOf(a), listOf(v1), 1)
        val state = SyncState(4, Sync.fingerprint(s), 1, "down")
        assertFalse(Sync.hasLocalChanges(state, s))
        assertTrue(Sync.hasLocalChanges(state, s.copy(vocab = listOf(v1.copy(status = 4)))))
        assertTrue(Sync.hasLocalChanges(null, s))
        assertFalse(Sync.hasLocalChanges(null, BackupFile(exportedAtMillis = 1)))
    }

    @Test
    fun `lesson source fields survive the backup codec, and old backups still decode`() {
        val text = BackupCodec.encode(BackupFile(exportedAtMillis = 1, lessons = listOf(b)))
        assertEquals(b, BackupCodec.decode(text).lessons.single())
        val old = """{"version":1,"exportedAtMillis":1,"lessons":[{"title":"A","text":"Hola.","createdAtMillis":1}]}"""
        assertEquals(BackupLesson("A", "Hola.", 1), BackupCodec.decode(old).lessons.single())
    }
}
