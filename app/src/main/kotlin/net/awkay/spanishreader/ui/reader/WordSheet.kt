package net.awkay.spanishreader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import net.awkay.spanishreader.core.gloss.Clitic
import net.awkay.spanishreader.core.gloss.CliticRole
import net.awkay.spanishreader.core.gloss.CliticSplit
import net.awkay.spanishreader.core.vocab.WordStatus
import net.awkay.spanishreader.gloss.LookupResult
import net.awkay.spanishreader.ui.StatusColors

/** One color per clitic role, so "direct object" always looks the same wherever it shows up. */
object CliticColors {
    fun of(role: CliticRole): Color = when (role) {
        CliticRole.DIRECT_OBJECT -> Color(0xFF42A5F5)
        CliticRole.INDIRECT_OBJECT -> Color(0xFFFFA726)
        CliticRole.REFLEXIVE -> Color(0xFF66BB6A)
        CliticRole.RECIPROCAL -> Color(0xFF26A69A)
        CliticRole.PRONOMINAL -> Color(0xFFAB47BC)
        CliticRole.ACCIDENTAL_SE -> Color(0xFFEC407A)
        CliticRole.IMPERSONAL_SE -> Color(0xFF78909C)
        CliticRole.OTHER -> Color(0xFFBDBDBD)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WordSheet(
    selection: WordSelection,
    status: WordStatus,
    phrases: List<PhraseSpan>,
    onStatus: (WordStatus) -> Unit,
    onRetry: () -> Unit,
    onImprove: () -> Unit,
    onSpeak: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val found = ((selection.gloss as? GlossState.Done)?.result as? LookupResult.Found)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(selection.token.text, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onSpeak) { Icon(Icons.AutoMirrored.Filled.VolumeUp, "Pronounce") }
                if (selection.gloss is GlossState.Done) {
                    if (selection.improving) {
                        CircularProgressIndicator(Modifier.size(24.dp).padding(2.dp), strokeWidth = 2.dp)
                    } else {
                        TextButton(onClick = onImprove) {
                            Icon(Icons.Default.AutoFixHigh, null, Modifier.size(18.dp))
                            Text(" Improve")
                        }
                    }
                }
            }
            selection.improveError?.let { Text("Improve failed: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            // Expressions found by the phrase scan or by this word's own gloss.
            val expressions = buildList {
                phrases.forEach { add(it.phrase to it.meaning) }
                found?.gloss?.let { g ->
                    val p = g.phrase
                    if (g.isIdiomOrPhrase && p != null && none { it.first.equals(p, ignoreCase = true) }) add(p to g.phraseMeaning.orEmpty())
                }
            }.distinctBy { it.first.lowercase() }
            expressions.forEach { (phrase, meaning) -> PhraseCard(phrase, meaning) }

            when (val g = selection.gloss) {
                GlossState.Loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.padding(4.dp))
                    Text("Looking up…")
                }
                is GlossState.Done -> when (val r = g.result) {
                    is LookupResult.Found -> GlossDetails(r, selection.token.text)
                    is LookupResult.Failed -> {
                        Text(r.message, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onRetry) { Text("Retry") }
                    }
                }
            }
            Text("“${selection.sentence}”", style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic)
            Text("Status", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
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
private fun Section(title: String, content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun PhraseCard(phrase: String, meaning: String) = Section("Expression") {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(phrase) }
            if (meaning.isNotBlank()) append(" — $meaning")
        },
        style = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
private fun GlossDetails(r: LookupResult.Found, written: String) {
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
    if (g.lacksDetail) {
        Text("Saved before detailed explanations existed — tap Improve for conjugation, pronouns and roots.", style = MaterialTheme.typography.bodySmall)
    }
    g.verb?.let { v ->
        Section("Verb") {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(v.infinitive) }
                    if (v.summary.isNotBlank()) append("  ·  ${v.summary}")
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            v.formation?.let { Labeled("How it's built", it) }
            v.whyThisForm?.let { Labeled("Why this form", it) }
        }
    }
    if (g.clitics.isNotEmpty()) CliticsCard(written, g.clitics)
    g.roots?.let { Section("Roots") { Text(it, style = MaterialTheme.typography.bodyMedium) } }
    g.grammarNote?.let { Section("Note") { Text(it, style = MaterialTheme.typography.bodyMedium) } }
    if (g.otherMeanings.isNotEmpty()) {
        Text("Other meanings: " + g.otherMeanings.joinToString(", "), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Labeled(label: String, text: String) = Text(
    buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("$label: ") }
        append(text)
    },
    style = MaterialTheme.typography.bodyMedium,
)

/** The verb with its attached pronouns split off and colored by role, then one line per pronoun. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CliticsCard(written: String, clitics: List<Clitic>) = Section("Pronouns") {
    val split = CliticSplit.of(written, clitics)
    val remaining = clitics.toMutableList()
    val attachedRoles = split.attached.map { p ->
        val c = remaining.firstOrNull { it.pronoun.equals(p, ignoreCase = true) }
        if (c != null) remaining.remove(c)
        c?.kind ?: CliticRole.OTHER
    }
    if (split.attached.isNotEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(split.base, style = MaterialTheme.typography.titleMedium)
            split.attached.zip(attachedRoles).forEach { (p, role) ->
                Text("+", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 4.dp))
                RoleTag(p, role)
            }
        }
    }
    val attachedSet = split.attached.map { it.lowercase() }.toMutableList()
    for (c in clitics) {
        val attached = attachedSet.remove(c.pronoun.lowercase())
        FlowRow(verticalArrangement = Arrangement.Center, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RoleTag(c.pronoun, c.kind)
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(c.kind.label.takeIf { c.kind != CliticRole.OTHER } ?: c.role) }
                    c.refersTo?.takeIf { it.isNotBlank() }?.let { append(" → $it") }
                    if (!attached && !c.pronoun.equals(written, ignoreCase = true)) append(" (before the verb)")
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        c.note?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp)) }
    }
}

@Composable
private fun RoleTag(pronoun: String, role: CliticRole) {
    Box(Modifier.background(CliticColors.of(role).copy(alpha = 0.35f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp)) {
        Text(pronoun, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}
