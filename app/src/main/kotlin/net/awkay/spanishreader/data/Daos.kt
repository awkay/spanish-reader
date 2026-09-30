package net.awkay.spanishreader.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import net.awkay.spanishreader.core.vocab.WordStatus

@Dao
interface LessonDao {
    @Insert
    suspend fun insert(lesson: LessonEntity): Long

    @Query("SELECT * FROM lessons ORDER BY created_at DESC")
    fun observeAll(): Flow<List<LessonEntity>>

    @Query("SELECT * FROM lessons WHERE id = :id")
    suspend fun get(id: Long): LessonEntity?

    @Query("SELECT * FROM lessons WHERE id = :id")
    fun observe(id: Long): Flow<LessonEntity?>

    @Query("SELECT * FROM lessons ORDER BY created_at")
    suspend fun getAll(): List<LessonEntity>

    @Query("UPDATE lessons SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)

    @Query("UPDATE lessons SET current_page = :page WHERE id = :id")
    suspend fun setCurrentPage(id: Long, page: Int)

    @Delete
    suspend fun delete(lesson: LessonEntity)
}

@Dao
interface VocabDao {
    @Query("SELECT * FROM vocab WHERE form = :form")
    suspend fun get(form: String): VocabEntity?

    /** Keep [forms] under SQLite's bound-variable limit; see [VocabRepository]. */
    @Query("SELECT * FROM vocab WHERE form IN (:forms)")
    suspend fun getAll(forms: List<String>): List<VocabEntity>

    @Query("SELECT * FROM vocab ORDER BY last_seen DESC")
    fun observeAll(): Flow<List<VocabEntity>>

    @Query("SELECT * FROM vocab")
    suspend fun getAllOnce(): List<VocabEntity>

    /** Lightweight projection for coloring text: every stored form and its status. */
    @Query("SELECT form, status FROM vocab")
    fun observeStatuses(): Flow<List<FormStatus>>

    @Query("DELETE FROM vocab WHERE form = :form")
    suspend fun delete(form: String)

    @Query("SELECT * FROM vocab WHERE status = :status ORDER BY last_seen DESC")
    fun observeByStatus(status: WordStatus): Flow<List<VocabEntity>>

    @Upsert
    suspend fun upsert(entity: VocabEntity)

    @Upsert
    suspend fun upsertAll(entities: List<VocabEntity>)
}

data class FormStatus(val form: String, val status: WordStatus)

@Dao
interface GlossDao {
    @Query("SELECT * FROM gloss_cache WHERE form_key = :formKey AND sentence_hash = :sentenceHash")
    suspend fun get(formKey: String, sentenceHash: String): GlossEntity?

    @Query("SELECT * FROM gloss_cache WHERE form_key = :formKey ORDER BY stored_at DESC LIMIT 1")
    suspend fun latestByForm(formKey: String): GlossEntity?

    @Upsert
    suspend fun upsert(entity: GlossEntity)

    @Query("SELECT COUNT(*) FROM gloss_cache")
    suspend fun count(): Int

    @Query("DELETE FROM gloss_cache")
    suspend fun clear()
}

@Dao
interface PhraseDao {
    /** Keep [hashes] under SQLite's bound-variable limit. */
    @Query("SELECT * FROM phrases WHERE sentence_hash IN (:hashes)")
    fun observeFor(hashes: List<String>): Flow<List<PhraseEntity>>

    @Query("SELECT * FROM phrases WHERE sentence_hash = :hash")
    suspend fun forSentence(hash: String): List<PhraseEntity>

    @Upsert
    suspend fun upsert(phrases: List<PhraseEntity>)

    @Query("SELECT sentence_hash FROM phrase_scans WHERE sentence_hash IN (:hashes)")
    suspend fun scanned(hashes: List<String>): List<String>

    @Upsert
    suspend fun markScanned(scans: List<PhraseScanEntity>)

    @Query("DELETE FROM phrases")
    suspend fun clearPhrases()

    @Upsert
    suspend fun upsertTranslation(t: SentenceTranslationEntity)

    @Query("SELECT translation FROM sentence_translations WHERE sentence_hash = :hash")
    suspend fun translation(hash: String): String?

    @Query("SELECT sentence_hash FROM sentence_translations WHERE sentence_hash IN (:hashes)")
    suspend fun translated(hashes: List<String>): List<String>

    @Query("SELECT * FROM sentence_translations WHERE sentence_hash IN (:hashes)")
    fun observeTranslations(hashes: List<String>): Flow<List<SentenceTranslationEntity>>

    @Query("DELETE FROM sentence_translations")
    suspend fun clearTranslations()

    @Query("DELETE FROM phrase_scans")
    suspend fun clearScans()
}
