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

    @Query("SELECT * FROM vocab WHERE status = :status ORDER BY last_seen DESC")
    fun observeByStatus(status: WordStatus): Flow<List<VocabEntity>>

    @Upsert
    suspend fun upsert(entity: VocabEntity)

    @Upsert
    suspend fun upsertAll(entities: List<VocabEntity>)
}

@Dao
interface GlossDao {
    @Query("SELECT * FROM gloss_cache WHERE form_key = :formKey AND sentence_hash = :sentenceHash")
    suspend fun get(formKey: String, sentenceHash: String): GlossEntity?

    @Query("SELECT * FROM gloss_cache WHERE form_key = :formKey ORDER BY stored_at DESC LIMIT 1")
    suspend fun latestByForm(formKey: String): GlossEntity?

    @Upsert
    suspend fun upsert(entity: GlossEntity)
}
