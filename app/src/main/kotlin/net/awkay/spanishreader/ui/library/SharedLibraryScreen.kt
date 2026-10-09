package net.awkay.spanishreader.ui.library

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.awkay.spanishreader.share.SharedLessonSummary
import net.awkay.spanishreader.ui.BackButton
import net.awkay.spanishreader.ui.appViewModel

/** Lessons shared to the household web app; "Add" copies one (with its cached AI results) into this phone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedLibraryScreen(onBack: () -> Unit, onOpen: (Long) -> Unit, onSettings: () -> Unit) {
    val vm = appViewModel { SharedLibraryViewModel(it) }
    val localTitles by vm.localTitles.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(vm.message) {
        vm.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            vm.message = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Get from web") },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    IconButton(onClick = vm::refresh, enabled = !vm.loading) { Icon(Icons.Default.Refresh, "Refresh") }
                },
            )
        },
    ) { padding ->
        val list = vm.lessons
        val error = vm.error
        when {
            error != null && list == null -> Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(error, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error)
                Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (vm.needsSettings) Button(onClick = onSettings) { Text("Open Settings") }
                    OutlinedButton(onClick = vm::refresh, enabled = !vm.loading) { Text("Try again") }
                }
            }
            list == null -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), Alignment.Center) {
                Text("Nothing has been shared yet.\n\nUse “Share to web” on a lesson, or share one from the web app.", textAlign = TextAlign.Center)
            }
            else -> Column(Modifier.fillMaxSize().padding(padding)) {
                if (error != null) {
                    Text(
                        error, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                    )
                    if (vm.needsSettings) TextButton(onClick = onSettings, Modifier.padding(horizontal = 8.dp)) { Text("Open Settings") }
                }
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(list, key = { it.id }) { lesson ->
                        SharedLessonCard(
                            lesson,
                            inLibrary = lesson.title in localTitles,
                            adding = vm.adding == lesson.id,
                            enabled = vm.adding == null,
                            onAdd = { vm.add(lesson, onOpen) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SharedLessonCard(lesson: SharedLessonSummary, inLibrary: Boolean, adding: Boolean, enabled: Boolean, onAdd: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(lesson.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${lesson.words} words" + (if (lesson.videoId != null) " · YouTube" else "") + (lesson.sharedBy?.let { " · shared by $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                )
                // Same title only; Add reuses the local copy when the text matches too.
                if (inLibrary) Text("In your library", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            if (adding) {
                CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
            } else {
                TextButton(onClick = onAdd, enabled = enabled) { Text("Add") }
            }
        }
    }
}
