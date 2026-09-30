package net.awkay.spanishreader.data

import androidx.room.withTransaction
import net.awkay.spanishreader.core.backup.BackupCodec
import net.awkay.spanishreader.core.backup.BackupFile
import net.awkay.spanishreader.core.backup.BackupLesson
import net.awkay.spanishreader.core.backup.BackupMerge
import net.awkay.spanishreader.core.backup.BackupVocab

data class RestoreSummary(val lessonsAdded: Int, val lessonsSkipped: Int, val vocabUpdated: Int, val vocabUnchanged: Int) {
    override fun toString() =
        "Added $lessonsAdded lessons ($lessonsSkipped already present); updated $vocabUpdated words ($vocabUnchanged unchanged)."
}

/** JSON export/import of lessons and vocabulary. Restoring merges; it never deletes anything. */
class BackupService(
    private val db: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun exportJson(): String {
        val lessons = db.lessons().getAll().map { BackupLesson(it.title, it.text, it.createdAtMillis, it.currentPage) }
        val vocab = db.vocab().getAllOnce().map { BackupVocab.from(it.toEntry()) }
        return BackupCodec.encode(BackupFile(exportedAtMillis = clock(), lessons = lessons, vocab = vocab))
    }

    /** @throws net.awkay.spanishreader.core.backup.BackupFormatException if [json] is not a valid backup. */
    suspend fun importJson(json: String): RestoreSummary {
        val backup = BackupCodec.decode(json)
        return db.withTransaction {
            val existingKeys = db.lessons().getAll().map { BackupMerge.lessonKey(it.title, it.text) }.toMutableSet()
            var added = 0
            for (l in backup.lessons) {
                if (existingKeys.add(BackupMerge.lessonKey(l.title, l.text))) {
                    db.lessons().insert(LessonEntity(title = l.title, text = l.text, createdAtMillis = l.createdAtMillis, currentPage = l.currentPage))
                    added++
                }
            }
            var updated = 0
            for (v in backup.vocab) {
                val incoming = v.toEntry()
                val merged = BackupMerge.mergeVocab(db.vocab().get(incoming.form)?.toEntry(), incoming)
                if (merged != null) {
                    db.vocab().upsert(VocabEntity.from(merged))
                    updated++
                }
            }
            RestoreSummary(added, backup.lessons.size - added, updated, backup.vocab.size - updated)
        }
    }
}
