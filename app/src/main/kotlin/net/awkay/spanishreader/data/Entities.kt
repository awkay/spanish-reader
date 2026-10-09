package net.awkay.spanishreader.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import net.awkay.spanishreader.core.vocab.VocabEntry
import net.awkay.spanishreader.core.vocab.WordStatus

@Entity(tableName = "lessons")
data class LessonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val text: String,
    @ColumnInfo(name = "created_at") val createdAtMillis: Long,
    /** Zero-based page the reader was last on. */
    @ColumnInfo(name = "current_page") val currentPage: Int = 0,
    /** YouTube video this lesson was transcribed from (by the web server); its audio is the original recording. */
    @ColumnInfo(name = "video_id") val videoId: String? = null,
    @ColumnInfo(name = "source_url") val sourceUrl: String? = null,
)

/** One row per lowercased written form (see Tokenizer.normalize); mirrors [VocabEntry]. */
@Entity(tableName = "vocab", indices = [Index("status"), Index("lemma")])
data class VocabEntity(
    @PrimaryKey val form: String,
    val lemma: String?,
    val status: WordStatus,
    val translation: String?,
    @ColumnInfo(name = "context_sentence") val contextSentence: String?,
    @ColumnInfo(name = "first_seen") val firstSeenMillis: Long,
    @ColumnInfo(name = "last_seen") val lastSeenMillis: Long,
    @ColumnInfo(name = "times_seen") val timesSeen: Int,
) {
    fun toEntry() = VocabEntry(form, lemma, status, translation, contextSentence, firstSeenMillis, lastSeenMillis, timesSeen)

    companion object {
        fun from(e: VocabEntry) = VocabEntity(
            e.form, e.lemma, e.status, e.translation, e.contextSentence, e.firstSeenMillis, e.lastSeenMillis, e.timesSeen,
        )
    }
}

/** A cached gloss (JSON-encoded core `Gloss`), keyed as in core `GlossCache`. */
@Entity(
    tableName = "gloss_cache",
    primaryKeys = ["form_key", "sentence_hash"],
    indices = [Index(value = ["form_key", "stored_at"])],
)
data class GlossEntity(
    @ColumnInfo(name = "form_key") val formKey: String,
    @ColumnInfo(name = "sentence_hash") val sentenceHash: String,
    val json: String,
    @ColumnInfo(name = "stored_at") val storedAtMillis: Long,
)

/** An idiom or fixed expression found in a sentence (by the phrase scan or a word's gloss). */
@Entity(tableName = "phrases", primaryKeys = ["sentence_hash", "phrase"])
data class PhraseEntity(
    @ColumnInfo(name = "sentence_hash") val sentenceHash: String,
    /** The expression's words as they appear in the sentence. */
    val phrase: String,
    val meaning: String,
    @ColumnInfo(name = "stored_at") val storedAtMillis: Long,
)

/** English translation of a whole sentence, keyed like the gloss cache (SHA-256 of the normalized sentence). */
@Entity(tableName = "sentence_translations")
data class SentenceTranslationEntity(
    @PrimaryKey @ColumnInfo(name = "sentence_hash") val sentenceHash: String,
    val translation: String,
    @ColumnInfo(name = "stored_at") val storedAtMillis: Long,
)

/** Sentences already sent to the phrase scan, so they aren't scanned again. */
@Entity(tableName = "phrase_scans")
data class PhraseScanEntity(
    @PrimaryKey @ColumnInfo(name = "sentence_hash") val sentenceHash: String,
    @ColumnInfo(name = "scanned_at") val scannedAtMillis: Long,
)

class Converters {
    @TypeConverter
    fun statusToCode(status: WordStatus): Int = status.code

    @TypeConverter
    fun codeToStatus(code: Int): WordStatus = WordStatus.fromCode(code)
}
