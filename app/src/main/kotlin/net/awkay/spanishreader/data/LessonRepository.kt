package net.awkay.spanishreader.data

import kotlinx.coroutines.flow.Flow
import net.awkay.spanishreader.core.text.ImportCleaner

class LessonRepository(
    private val db: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.lessons()

    fun observeAll(): Flow<List<LessonEntity>> = dao.observeAll()

    fun observe(id: Long): Flow<LessonEntity?> = dao.observe(id)

    suspend fun get(id: Long): LessonEntity? = dao.get(id)

    suspend fun all(): List<LessonEntity> = dao.getAll()

    /**
     * Cleans [rawText] and stores it as a new lesson; a blank [title] is derived from the text.
     * @throws IllegalArgumentException if the text is empty after cleaning.
     */
    suspend fun import(title: String, rawText: String, joinWrappedLines: Boolean = true): Long {
        val text = ImportCleaner.clean(rawText, joinWrappedLines)
        require(text.isNotBlank()) { "The text is empty" }
        val finalTitle = title.trim().ifEmpty { ImportCleaner.suggestTitle(text) }
        return dao.insert(LessonEntity(title = finalTitle, text = text, createdAtMillis = clock()))
    }

    suspend fun setCurrentPage(id: Long, page: Int) = dao.setCurrentPage(id, page)

    suspend fun setVideo(id: Long, videoId: String?, sourceUrl: String?) = dao.setVideo(id, videoId, sourceUrl)

    suspend fun rename(id: Long, title: String) = dao.rename(id, title.trim())

    suspend fun delete(id: Long) {
        dao.get(id)?.let { dao.delete(it) }
    }
}
