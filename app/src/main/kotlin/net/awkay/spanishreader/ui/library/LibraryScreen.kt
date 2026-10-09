package net.awkay.spanishreader.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.awkay.spanishreader.ui.appViewModel
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpen: (Long) -> Unit,
    onListen: (Long) -> Unit,
    onImport: () -> Unit,
    onVocabulary: () -> Unit,
    onSettings: () -> Unit,
    onGetFromWeb: () -> Unit = {},
) {
    val vm = appViewModel { LibraryViewModel(it) }
    val rows by vm.rows.collectAsStateWithLifecycle()
    val shareMessage by vm.shareMessage.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(shareMessage) {
        shareMessage?.let { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show() }
    }
    var deleting by remember { mutableStateOf<LessonRow?>(null) }
    var renaming by remember { mutableStateOf<LessonRow?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Spanish Reader") },
                actions = {
                    IconButton(onClick = onGetFromWeb) { Icon(Icons.Default.CloudDownload, "Get from web") }
                    IconButton(onClick = onVocabulary) { Icon(Icons.Default.Translate, "Vocabulary") }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onImport, icon = { Icon(Icons.Default.Add, null) }, text = { Text("New lesson") })
        },
    ) { padding ->
        val list = rows
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), Alignment.Center) {
                Text(
                    "No lessons yet.\n\nShare Spanish text to this app from any other app, or tap New lesson to paste text or open a .txt file.",
                    textAlign = TextAlign.Center,
                )
            }
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(list, key = { it.lesson.id }) { row ->
                    LessonCard(
                        row,
                        onOpen = { onOpen(row.lesson.id) },
                        onListen = { onListen(row.lesson.id) },
                        onPreGloss = { vm.preGloss(row.lesson.id) },
                        onShareToWeb = { vm.shareToWeb(row.lesson) },
                        onRename = { renaming = row },
                        onDelete = { deleting = row },
                    )
                }
            }
        }
    }

    deleting?.let { row ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete lesson?") },
            text = { Text("\"${row.lesson.title}\" will be removed. Your vocabulary is kept.") },
            confirmButton = { TextButton(onClick = { vm.delete(row.lesson.id); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
    renaming?.let { row ->
        var title by remember(row.lesson.id) { mutableStateOf(row.lesson.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename lesson") },
            text = { OutlinedTextField(title, { title = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.rename(row.lesson.id, title); renaming = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun LessonCard(
    row: LessonRow,
    onOpen: () -> Unit,
    onListen: () -> Unit,
    onPreGloss: () -> Unit,
    onShareToWeb: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text((if (row.lesson.videoId != null) "▶ " else "") + row.lesson.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val s = row.stats
                Text(
                    "${s.totalWords} words · ${s.newCount} new · ${s.learningCount} learning · ${s.knownPercent.roundToInt()}% known",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("Page ${(row.lesson.currentPage + 1).coerceAtMost(row.pageCount)} of ${row.pageCount}", style = MaterialTheme.typography.bodySmall)
                when (val g = row.preGloss) {
                    is PreGlossState.Running -> {
                        Text("Pre-glossing ${g.done}/${g.total}", style = MaterialTheme.typography.bodySmall)
                        if (g.total > 0) LinearProgressIndicator(progress = { g.done / g.total.toFloat() }, Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                    PreGlossState.Waiting -> Text("Pre-gloss waiting for network…", style = MaterialTheme.typography.bodySmall)
                    is PreGlossState.Failed -> Text(
                        "Pre-gloss failed: ${g.error}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    null -> Unit
                }
            }
            IconButton(onClick = onListen) { Icon(Icons.Default.Headphones, "Listen") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Pre-gloss whole lesson") }, onClick = { menu = false; onPreGloss() })
                    DropdownMenuItem(text = { Text("Share to web") }, onClick = { menu = false; onShareToWeb() })
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}
