package net.awkay.spanishreader.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.awkay.spanishreader.core.vocab.WordStatus
import net.awkay.spanishreader.gloss.LookupResult
import net.awkay.spanishreader.ui.StatusColors

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WordSheet(
    selection: WordSelection,
    status: WordStatus,
    onStatus: (WordStatus) -> Unit,
    onRetry: () -> Unit,
    onSpeak: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(selection.token.text, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onSpeak) { Icon(Icons.AutoMirrored.Filled.VolumeUp, "Pronounce") }
            }
            when (val g = selection.gloss) {
                GlossState.Loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.padding(4.dp))
                    Text("Looking up…")
                }
                is GlossState.Done -> when (val r = g.result) {
                    is LookupResult.Found -> GlossDetails(r)
                    is LookupResult.Failed -> {
                        Text(r.message, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onRetry) { Text("Retry") }
                    }
                }
            }
            Text("“${selection.sentence}”", style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic)
            Text("Status", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val choices = listOf(
                    WordStatus.LEVEL_1 to "1", WordStatus.RECOGNIZED to "2", WordStatus.FAMILIAR to "3",
                    WordStatus.LEARNED to "4", WordStatus.KNOWN to "Known ✓", WordStatus.IGNORED to "Ignore",
                )
                for ((s, label) in choices) {
                    FilterChip(
                        selected = status == s,
                        onClick = { onStatus(s) },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = StatusColors.swatch(s)),
                    )
                }
            }
        }
    }
}

@Composable
private fun GlossDetails(r: LookupResult.Found) {
    val g = r.gloss
    Text(
        listOfNotNull(g.lemma.takeIf { it.isNotBlank() && !it.equals(g.form, ignoreCase = true) }?.let { "from $it" }, g.partOfSpeech.takeIf { it.isNotBlank() })
            .joinToString(" · "),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(g.meaningInContext, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    if (r.fromOtherSentence) {
        Text("Couldn't reach the glosser; this meaning is from another sentence.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    g.grammarNote?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    if (g.isIdiomOrPhrase && !g.phrase.isNullOrBlank()) Text("Part of the phrase “${g.phrase}”", style = MaterialTheme.typography.bodyMedium)
    if (g.otherMeanings.isNotEmpty()) {
        Text("Other meanings: " + g.otherMeanings.joinToString(", "), style = MaterialTheme.typography.bodySmall)
    }
}
