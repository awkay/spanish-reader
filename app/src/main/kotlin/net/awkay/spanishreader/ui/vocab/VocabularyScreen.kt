package net.awkay.spanishreader.ui.vocab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.awkay.spanishreader.core.vocab.VocabEntry
import net.awkay.spanishreader.core.vocab.WordStatus
import net.awkay.spanishreader.ui.BackButton
import net.awkay.spanishreader.ui.StatusColors
import net.awkay.spanishreader.ui.appViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VocabularyScreen(onBack: () -> Unit) {
    val vm = appViewModel { VocabularyViewModel(it) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf<String?>(null) }

    Scaffold(topBar = { TopAppBar(title = { Text("Vocabulary") }, navigationIcon = { BackButton(onBack) }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                query, { vm.query.value = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                placeholder = { Text("Search word, lemma or meaning") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true,
            )
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (f in VocabFilter.entries) {
                    FilterChip(filter == f, { vm.filter.value = f }, label = { Text("${f.label} ${ui?.counts?.get(f) ?: ""}") })
                }
            }
            val u = ui
            when {
                u == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                u.entries.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
                    Text("No words here yet. Tap words while reading to add them.")
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(u.entries, key = { it.form }) { e ->
                        VocabRow(
                            e, expanded == e.form,
                            onClick = { expanded = if (expanded == e.form) null else e.form },
                            onStatus = { vm.setStatus(e.form, it) },
                            onDelete = { vm.delete(e.form); expanded = null },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VocabRow(e: VocabEntry, expanded: Boolean, onClick: () -> Unit, onStatus: (WordStatus) -> Unit, onDelete: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).background(StatusColors.swatch(e.status), CircleShape))
            Text(e.form, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp))
            e.lemma?.takeIf { it != e.form }?.let { Text("  ($it)", style = MaterialTheme.typography.bodySmall) }
            Text(e.status.label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f).padding(start = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        e.translation?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 22.dp)) }
        if (expanded) {
            e.contextSentence?.let { Text("“$it”", style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, modifier = Modifier.padding(start = 22.dp, top = 4.dp)) }
            Text("Seen ${e.timesSeen}×", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 22.dp, top = 4.dp))
            FlowRow(Modifier.padding(start = 16.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (s in listOf(WordStatus.LEVEL_1, WordStatus.RECOGNIZED, WordStatus.FAMILIAR, WordStatus.LEARNED, WordStatus.KNOWN, WordStatus.IGNORED)) {
                    AssistChip(onClick = { onStatus(s) }, label = { Text(if (s == e.status) "• ${s.label}" else s.label) })
                }
                TextButton(onClick = onDelete) { Text("Forget word") }
            }
        }
    }
}
