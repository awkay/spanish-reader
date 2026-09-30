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

class Converters {
    @TypeConverter
    fun statusToCode(status: WordStatus): Int = status.code

    @TypeConverter
    fun codeToStatus(code: Int): WordStatus = WordStatus.fromCode(code)
}
