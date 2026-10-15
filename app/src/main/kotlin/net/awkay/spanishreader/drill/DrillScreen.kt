package net.awkay.spanishreader.drill

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.awkay.spanishreader.core.practice.CommonVerbs
import net.awkay.spanishreader.core.practice.Drill
import net.awkay.spanishreader.core.practice.DrillVerdict
import net.awkay.spanishreader.ui.BackButton
import net.awkay.spanishreader.ui.appViewModel

/** Lightning verb drill: type just the verb form, one try, as fast as you can. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrillScreen(onBack: () -> Unit) {
    val vm = appViewModel { DrillViewModel(it) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ok = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFF81C995) else Color(0xFF2E7D32)
    BackHandler(enabled = ui.verb != null) { vm.leave() }

    val verb = ui.verb
    val title = verb?.let { if (ui.finished) "Round done" else it.infinitive } ?: "Lightning verbs"
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(title)
                        if (verb != null && !ui.finished) {
                            Text(verb.meaning, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = { BackButton { if (verb != null) vm.leave() else onBack() } },
                actions = {
                    if (verb != null && !ui.finished) {
                        Text("${ui.index + 1}/${ui.queue.size} · ✓${ui.score}", Modifier.padding(end = 12.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val card = ui.card
            when {
                verb != null && card != null -> {
                    var typed by remember(card.id) { mutableStateOf("") }
                    val focus = remember { FocusRequester() }
                    LaunchedEffect(card.id) { runCatching { focus.requestFocus() } }
                    val fb = ui.feedback
                    Text("${card.tenseLabel} · ${card.subjectLabel}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(card.english, style = MaterialTheme.typography.headlineSmall)
                    val color = when (fb?.verdict) {
                        null -> MaterialTheme.colorScheme.primary
                        DrillVerdict.CORRECT -> ok
                        else -> MaterialTheme.colorScheme.error
                    }
                    Text(
                        buildAnnotatedString {
                            append(card.spanish.substringBefore(Drill.BLANK))
                            withStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.Underline)) {
                                append(if (fb != null) card.answer else " ".repeat(10))
                            }
                            append(card.spanish.substringAfter(Drill.BLANK))
                        },
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    OutlinedTextField(
                        typed, { if (fb == null) typed = it }, Modifier.fillMaxWidth().focusRequester(focus),
                        placeholder = { Text("${verb.infinitive} →") }, singleLine = true, textStyle = MaterialTheme.typography.titleLarge,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                            imeAction = if (fb == null) ImeAction.Done else ImeAction.Next,
                        ),
                        // The keyboard stays up: its action key checks, and after a miss moves on.
                        keyboardActions = KeyboardActions(onAny = { if (fb == null) vm.submit(typed) else if (fb.verdict != DrillVerdict.CORRECT) vm.next() }),
                    )
                    when {
                        fb == null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button({ vm.submit(typed) }, enabled = typed.isNotBlank()) { Text("Check") }
                            TextButton(vm::hideCard) { Text("Bad card") }
                        }
                        fb.verdict == DrillVerdict.CORRECT ->
                            Text(if (fb.learned) "✓ Learned!" else "✓", color = ok, style = MaterialTheme.typography.headlineSmall)
                        else -> {
                            Text(
                                (if (fb.verdict == DrillVerdict.ACCENT) "Check the accent: " else "✗ ") + card.answer +
                                    if (ui.typed.isNotEmpty()) "  (you typed ${ui.typed})" else "",
                                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium,
                            )
                            Text(card.filled, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(vm::next, Modifier.fillMaxWidth()) { Text("Next ›") }
                        }
                    }
                    val right = ui.progress[card.id]?.correct ?: 0
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        repeat(Drill.TO_LEARN) { k ->
                            Box(
                                Modifier.size(12.dp).background(
                                    if (k < right) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, CircleShape,
                                ),
                            )
                        }
                    }
                }
                verb != null && ui.finished -> {
                    val vp = ui.learnedOf(verb.infinitive)
                    val mastered = Drill.mastered(ui.sets[verb.infinitive].orEmpty(), ui.progress)
                    val next = if (mastered) ui.current else verb
                    Text("${ui.score} of ${ui.queue.size} right", style = MaterialTheme.typography.headlineSmall)
                    if (ui.learnedNow > 0) Text("${ui.learnedNow} card${if (ui.learnedNow > 1) "s" else ""} learned this round.")
                    vp?.let { (l, t) ->
                        Text("${verb.infinitive}: $l of $t cards learned" + if (mastered) " — mastered!" else "", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button({ vm.start(next) }, Modifier.fillMaxWidth(), enabled = ui.loading == null) {
                        Text(if (mastered) "Next verb: ${next.infinitive} ›" else "Another round ›")
                    }
                    ui.loading?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                else -> {
                    val current = ui.current
                    Text(
                        "Type just the verb form for each sentence. One try each; a card is learned after ${Drill.TO_LEARN} right " +
                            "answers (a miss takes one back), and learned forms go into your vocabulary as Known. Master a verb's cards to move on.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("${current.infinitive} — ${current.meaning}", style = MaterialTheme.typography.titleLarge)
                            Text(
                                ui.learnedOf(current.infinitive)?.let { (l, t) -> "$l of $t cards learned" } ?: "new verb",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Button({ vm.start(current) }, Modifier.fillMaxWidth(), enabled = ui.loading == null) { Text("⚡ Start") }
                    if (ui.loading != null) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(ui.loading!!, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Text("All verbs", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    LazyColumn(Modifier.fillMaxWidth()) {
                        items(CommonVerbs.ALL, key = { it.infinitive }) { v ->
                            val vp = ui.learnedOf(v.infinitive)
                            val done = Drill.mastered(ui.sets[v.infinitive].orEmpty(), ui.progress)
                            Row(
                                Modifier.fillMaxWidth().clickable(enabled = ui.loading == null) { vm.start(v) }.padding(vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(v.infinitive, fontWeight = FontWeight.SemiBold)
                                Text(v.meaning, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    if (done) "✓ mastered" else vp?.let { (l, t) -> "$l/$t" } ?: "",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}
