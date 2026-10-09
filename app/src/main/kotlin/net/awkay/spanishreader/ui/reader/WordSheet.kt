package net.awkay.spanishreader.ui.reader

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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

/** How far the sheet opens at first: the header, the status row and the start of the meaning. */
private val PEEK_HEIGHT = 300.dp

/**
 * The word sheet. Non-modal: the page behind it stays tappable, so tapping another word switches the sheet to it.
 * The header (word, Prev/Next, status chips) stays fixed; the explanation below it scrolls. Drag the handle to
 * resize (down past the peek closes it), tap it to toggle peek/expanded.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WordSheet(
    selection: WordSelection,
    status: WordStatus,
    phrases: List<PhraseSpan>,
    canPrevious: Boolean,
    canNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onStatus: (WordStatus) -> Unit,
    onRetry: () -> Unit,
    onImprove: () -> Unit,
    onTranslate: () -> Unit,
    onSpeak: () -> Unit,
    onDismiss: () -> Unit,
    /** Height of the area the sheet sits in; it peeks at part of it and expands to most of it. */
    maxHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val peekPx = with(density) { minOf(PEEK_HEIGHT, maxHeight * 0.6f).toPx() }
    val fullPx = with(density) { (maxHeight * 0.9f).toPx() }.coerceAtLeast(peekPx)
    var heightPx by remember { mutableStateOf(peekPx) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    fun settle(target: Float) {
        settleJob?.cancel()
        settleJob = scope.launch { animate(heightPx, target) { value, _ -> heightPx = value } }
    }
    BackHandler(onBack = onDismiss)

    Surface(
        modifier = modifier.fillMaxWidth().height(with(density) { heightPx.toDp() }),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        tonalElevation = 2.dp,
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Box(
                Modifier.fillMaxWidth().height(36.dp)
                    .pointerInput(peekPx, fullPx) {
                        detectVerticalDragGestures(
                            onDragStart = { settleJob?.cancel() },
                            onDragEnd = {
                                when {
                                    heightPx < peekPx * 0.6f -> onDismiss()
                                    heightPx > (peekPx + fullPx) / 2 -> settle(fullPx)
                                    else -> settle(peekPx)
                                }
                            },
                        ) { change, dragAmount ->
                            change.consume()
                            heightPx = (heightPx - dragAmount).coerceIn(0f, fullPx)
                        }
                    }
                    .clickable { settle(if (heightPx < (peekPx + fullPx) / 2) fullPx else peekPx) },
            ) {
                Box(
                    Modifier.align(Alignment.Center).size(width = 36.dp, height = 4.dp)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(2.dp)),
                )
                IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp).size(36.dp)) {
                    Icon(Icons.Default.Close, "Close")
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        selection.token.text,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onSpeak) { Icon(Icons.AutoMirrored.Filled.VolumeUp, "Pronounce") }
                    TextButton(onClick = onPrevious, enabled = canPrevious, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("‹ Prev") }
                    TextButton(onClick = onNext, enabled = canNext, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Next ›") }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                selection.familyLemma?.let {
                    Text(
                        "Same word family as “$it”, so it starts at that status.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val details = rememberScrollState()
            LaunchedEffect(selection.token) { details.scrollTo(0) }
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(details).padding(horizontal = 20.dp).padding(top = 6.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                WordDetails(selection, phrases, onRetry, onImprove, onTranslate)
            }
        }
    }
}

/** Everything below the sheet's header: meaning, lemma, grammar cards, the sentence and its translation. */
@Composable
private fun WordDetails(
    selection: WordSelection,
    phrases: List<PhraseSpan>,
    onRetry: () -> Unit,
    onImprove: () -> Unit,
    onTranslate: () -> Unit,
) {
    val found = ((selection.gloss as? GlossState.Done)?.result as? LookupResult.Found)

    // Expressions found by the phrase scan or by this word's own gloss.
    val expressions = buildList {
        phrases.forEach { add(it.phrase to it.meaning) }
        found?.gloss?.let { g ->
            val p = g.phrase
            if (g.isIdiomOrPhrase && p != null && none { it.first.equals(p, ignoreCase = true) }) add(p to g.phraseMeaning.orEmpty())
        }
    }.distinctBy { it.first.lowercase() }

    when (val g = selection.gloss) {
        GlossState.Loading -> {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.padding(4.dp))
                Text("Looking up…")
            }
            expressions.forEach { (phrase, meaning) -> PhraseCard(phrase, meaning) }
        }
        is GlossState.Done -> when (val r = g.result) {
            is LookupResult.Found -> GlossDetails(r, selection.token.text, expressions)
            is LookupResult.Failed -> {
                expressions.forEach { (phrase, meaning) -> PhraseCard(phrase, meaning) }
                Text(r.message, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRetry) { Text("Retry") }
            }
        }
    }
    if (selection.gloss is GlossState.Done) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selection.improving) {
                CircularProgressIndicator(Modifier.size(24.dp).padding(2.dp), strokeWidth = 2.dp)
                Text("  Improving…", style = MaterialTheme.typography.bodySmall)
            } else {
                TextButton(onClick = onImprove) {
                    Icon(Icons.Default.AutoFixHigh, null, Modifier.size(18.dp))
                    Text(" Improve answer")
                }
            }
        }
    }
    selection.improveError?.let { Text("Improve failed: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    Text("“${selection.sentence}”", style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic)
    when (val t = selection.translation) {
        TranslationState.Hidden -> Unit
        TranslationState.Loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text("Translating…", style = MaterialTheme.typography.bodySmall)
        }
        is TranslationState.Shown -> Text(t.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        is TranslationState.Failed -> TextButton(onClick = onTranslate) {
            Text("${t.message} — tap to retry", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
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
private fun GlossDetails(r: LookupResult.Found, written: String, expressions: List<Pair<String, String>>) {
    val g = r.gloss
    // Meaning first so it shows while the sheet only peeks.
    Text(g.meaningInContext, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    Text(
        listOfNotNull(g.lemma.takeIf { it.isNotBlank() && !it.equals(g.form, ignoreCase = true) }?.let { "from $it" }, g.partOfSpeech.takeIf { it.isNotBlank() })
            .joinToString(" · "),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (r.fromOtherSentence) {
        Text("Couldn't reach the glosser; this meaning is from another sentence.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    if (g.lacksDetail) {
        Text("Saved before detailed explanations existed — tap Improve for conjugation, pronouns and roots.", style = MaterialTheme.typography.bodySmall)
    }
    expressions.forEach { (phrase, meaning) -> PhraseCard(phrase, meaning) }
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
