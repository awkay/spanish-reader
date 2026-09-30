package net.awkay.spanishreader.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

class LessonDaoTest : DbTestBase() {
    @Test
    fun insertListAndTrackPage() = runTest {
        val dao = db.lessons()
        val a = dao.insert(LessonEntity(title = "A", text = "Hola.", createdAtMillis = 1))
        dao.insert(LessonEntity(title = "B", text = "Adiós.", createdAtMillis = 2))
        assertEquals(listOf("B", "A"), dao.observeAll().first().map { it.title })
        dao.setCurrentPage(a, 3)
        assertEquals(3, dao.get(a)?.currentPage)
    }
}
