package net.awkay.spanishreader.core.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * Manual sync through the server (ported to web/src/core/sync.ts). A device pushes its whole state ("sync up") or
 * replaces its state with the server's ("sync down"); there is no merging. [SyncState] remembers what the device last
 * exchanged, so each direction can warn when it would throw away newer work.
 */
@Serializable
data class SyncState(
    /** Server revision this device last pushed or pulled. */
    val revision: Int,
    /** [Sync.fingerprint] of this device's state right after that sync. */
    val fingerprint: String,
    val atMillis: Long,
    /** "up" or "down". */
    val direction: String,
)

/** Server-side description of the account's snapshot (revision 0: nothing pushed yet). */
@Serializable
data class SyncMeta(val revision: Int = 0, val updatedAt: Long = 0, val device: String = "")

/** How to turn the local lessons into the snapshot's, keeping the rows (ids, cached audio) of lessons both have. */
data class LessonPlan<K>(
    /** Local lesson [K] is the snapshot's lesson: copy its page and source fields over. */
    val update: List<Pair<K, BackupLesson>>,
    val insert: List<BackupLesson>,
    val delete: List<K>,
)

object Sync {
    private val canonical = Json { encodeDefaults = true }

    /** The state to push: lessons by creation time then title, vocabulary by form, so equal states encode equally. */
    fun snapshot(lessons: List<BackupLesson>, vocab: List<BackupVocab>, exportedAtMillis: Long): BackupFile = BackupFile(
        exportedAtMillis = exportedAtMillis,
        lessons = lessons.sortedWith(compareBy({ it.createdAtMillis }, { it.title }, { it.text })),
        vocab = vocab.sortedBy { it.form },
    )

    /** Identifies a device's state, ignoring when it was exported (sorts first, so row order doesn't matter). */
    fun fingerprint(state: BackupFile): String {
        val sorted = snapshot(state.lessons, state.vocab, 0)
        val bytes = canonical.encodeToString(BackupFile.serializer(), sorted).toByteArray()
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    /** Lessons are matched by [BackupMerge.lessonKey]; local lessons the snapshot doesn't have are deleted. */
    fun <K> replacePlan(local: List<Pair<K, BackupLesson>>, incoming: List<BackupLesson>): LessonPlan<K> {
        val byKey = LinkedHashMap<String, K>()
        val delete = mutableListOf<K>()
        for ((id, l) in local) {
            val key = BackupMerge.lessonKey(l.title, l.text)
            if (key in byKey) delete += id else byKey[key] = id
        }
        val update = mutableListOf<Pair<K, BackupLesson>>()
        val insert = mutableListOf<BackupLesson>()
        val seen = HashSet<String>()
        for (l in incoming) {
            val key = BackupMerge.lessonKey(l.title, l.text)
            if (!seen.add(key)) continue
            val id = byKey.remove(key)
            if (id != null) update += id to l else insert += l
        }
        delete += byKey.values
        return LessonPlan(update, insert, delete)
    }

    /** Sync down would discard local changes: the state differs from right after the last sync (or never synced). */
    fun hasLocalChanges(state: SyncState?, local: BackupFile): Boolean =
        if (state == null) local.lessons.isNotEmpty() || local.vocab.isNotEmpty() else fingerprint(local) != state.fingerprint
}
