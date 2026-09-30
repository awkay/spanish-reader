package net.awkay.spanishreader.core.backup

import net.awkay.spanishreader.core.vocab.VocabEntry
import net.awkay.spanishreader.core.vocab.WordStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BackupTest {
    private fun entry(status: WordStatus, first: Long, last: Long, lemma: String? = null, translation: String? = null, times: Int = 1) =
        VocabEntry("fui", lemma, status, translation, null, first, last, times)

    @Test
    fun `round trip`() {
        val backup = BackupFile(
            exportedAtMillis = 42,
            lessons = listOf(BackupLesson("T", "Hola.", 1, 2)),
            vocab = listOf(BackupVocab.from(entry(WordStatus.FAMILIAR, 1, 2, "ir", "I went"))),
        )
        val decoded = BackupCodec.decode(BackupCodec.encode(backup))
        assertEquals(backup, decoded)
        assertEquals(WordStatus.FAMILIAR, decoded.vocab.single().toEntry().status)
    }

    @Test
    fun `rejects garbage, newer versions and bad statuses`() {
        assertFailsWith<BackupFormatException> { BackupCodec.decode("not json") }
        assertFailsWith<BackupFormatException> { BackupCodec.decode("""{"version":99,"exportedAtMillis":1}""") }
        assertFailsWith<BackupFormatException> {
            BackupCodec.decode("""{"exportedAtMillis":1,"vocab":[{"form":"a","status":7,"firstSeenMillis":1,"lastSeenMillis":1}]}""")
        }
        assertEquals(0, BackupCodec.decode("""{"exportedAtMillis":1,"extra":true}""").lessons.size)
    }

    @Test
    fun `merge prefers the more recently seen status and fills gaps`() {
        val existing = entry(WordStatus.LEVEL_1, first = 10, last = 20, lemma = "ir", times = 5)
        val incoming = entry(WordStatus.KNOWN, first = 5, last = 30, translation = "I went", times = 2)
        val merged = BackupMerge.mergeVocab(existing, incoming)!!
        assertEquals(WordStatus.KNOWN, merged.status)
        assertEquals("ir", merged.lemma)
        assertEquals("I went", merged.translation)
        assertEquals(5, merged.firstSeenMillis)
        assertEquals(30, merged.lastSeenMillis)
        assertEquals(5, merged.timesSeen)
    }

    @Test
    fun `merge keeps a newer local entry and reports no-ops`() {
        val existing = entry(WordStatus.LEARNED, first = 1, last = 50, lemma = "ir", translation = "I went")
        assertNull(BackupMerge.mergeVocab(existing, entry(WordStatus.LEVEL_1, first = 1, last = 10)))
        assertNull(BackupMerge.mergeVocab(existing, existing))
        assertEquals(existing, BackupMerge.mergeVocab(null, existing))
    }
}
