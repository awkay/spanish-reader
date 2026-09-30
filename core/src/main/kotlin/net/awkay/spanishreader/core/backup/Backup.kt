package net.awkay.spanishreader.core.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import net.awkay.spanishreader.core.vocab.VocabEntry
import net.awkay.spanishreader.core.vocab.WordStatus

@Serializable
data class BackupLesson(
    val title: String,
    val text: String,
    val createdAtMillis: Long,
    val currentPage: Int = 0,
)

@Serializable
data class BackupVocab(
    val form: String,
    val lemma: String? = null,
    val status: Int,
    val translation: String? = null,
    val contextSentence: String? = null,
    val firstSeenMillis: Long,
    val lastSeenMillis: Long,
    val timesSeen: Int = 1,
) {
    fun toEntry() = VocabEntry(
        form, lemma, WordStatus.fromCode(status), translation, contextSentence, firstSeenMillis, lastSeenMillis, timesSeen,
    )

    companion object {
        fun from(e: VocabEntry) = BackupVocab(
            e.form, e.lemma, e.status.code, e.translation, e.contextSentence, e.firstSeenMillis, e.lastSeenMillis, e.timesSeen,
        )
    }
}

/** Everything worth keeping. The gloss cache is left out: it can be regenerated. */
@Serializable
data class BackupFile(
    val version: Int = CURRENT_VERSION,
    val exportedAtMillis: Long,
    val lessons: List<BackupLesson> = emptyList(),
    val vocab: List<BackupVocab> = emptyList(),
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

class BackupFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

object BackupCodec {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(backup: BackupFile): String = json.encodeToString(BackupFile.serializer(), backup)

    /** @throws BackupFormatException for malformed JSON, a newer version, or invalid status codes. */
    fun decode(text: String): BackupFile {
        val backup = try {
            json.decodeFromString(BackupFile.serializer(), text)
        } catch (e: SerializationException) {
            throw BackupFormatException("Not a Spanish Reader backup: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw BackupFormatException("Not a Spanish Reader backup: ${e.message}", e)
        }
        if (backup.version > BackupFile.CURRENT_VERSION) {
            throw BackupFormatException("Backup version ${backup.version} is newer than this app supports")
        }
        backup.vocab.forEach {
            if (WordStatus.entries.none { s -> s.code == it.status }) {
                throw BackupFormatException("Unknown status ${it.status} for '${it.form}'")
            }
        }
        return backup
    }
}

object BackupMerge {
    /**
     * What to write when restoring [incoming] over [existing]: the more recently seen entry wins its status,
     * first-seen takes the earliest, times-seen the larger, and missing lemma/translation/context are filled from
     * the other side. Returns null when nothing would change.
     */
    fun mergeVocab(existing: VocabEntry?, incoming: VocabEntry): VocabEntry? {
        if (existing == null) return incoming
        val (newer, older) = if (incoming.lastSeenMillis > existing.lastSeenMillis) incoming to existing else existing to incoming
        val merged = newer.copy(
            lemma = newer.lemma ?: older.lemma,
            translation = newer.translation ?: older.translation,
            contextSentence = newer.contextSentence ?: older.contextSentence,
            firstSeenMillis = minOf(newer.firstSeenMillis, older.firstSeenMillis),
            timesSeen = maxOf(newer.timesSeen, older.timesSeen),
        )
        return merged.takeIf { it != existing }
    }

    /** Identity of a lesson for de-duplication on restore. */
    fun lessonKey(title: String, text: String): String = title.trim() + "\u0000" + text.trim()
}
