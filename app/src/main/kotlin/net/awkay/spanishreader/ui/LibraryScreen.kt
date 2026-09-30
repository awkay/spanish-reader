package net.awkay.spanishreader.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.awkay.spanishreader.data.LessonDao

/** Placeholder library: lists imported lessons. Import and the reader come next. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(lessonDao: LessonDao) {
    val lessonsFlow = remember(lessonDao) { lessonDao.observeAll() }
    val lessons by lessonsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    Scaffold(topBar = { TopAppBar(title = { Text("Spanish Reader") }) }) { padding ->
        if (lessons.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No lessons yet. Share Spanish text to this app to import it.")
            }
        } else {
            LazyColumn(Modifier.padding(padding)) {
                items(lessons, key = { it.id }) { lesson -> ListItem(headlineContent = { Text(lesson.title) }) }
            }
        }
    }
}
