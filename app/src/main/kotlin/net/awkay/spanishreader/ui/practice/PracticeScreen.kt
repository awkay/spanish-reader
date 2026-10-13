package net.awkay.spanishreader.ui.practice

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.awkay.spanishreader.core.practice.PracticePrompt
import net.awkay.spanishreader.core.practice.sameAnswer
import net.awkay.spanishreader.ui.BackButton
import net.awkay.spanishreader.ui.appViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PracticeScreen(onBack: () -> Unit) {
    val vm = appViewModel { PracticeViewModel(it) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    var answer by remember(ui.spec) { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val ok = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFF81C995) else Color(0xFF2E7D32)

    Scaffold(topBar = { TopAppBar(title = { Text("Verb practice") }, navigationIcon = { BackButton(onBack) }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            when {
                ui.verbCount == null -> Text("Loading your verbs…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                ui.verbCount == 0 && ui.error == null -> Text(
                    "No verbs yet. Verbs you tap or read past (with their meaning looked up) show up here.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val spec = ui.spec
            val exercise = ui.exercise
            if (spec != null) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        val meaning = spec.verb.meaning ?: exercise?.verbMeaning
                        Text(
                            spec.verb.infinitive + (meaning?.let { " — $it" } ?: ""),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            "${spec.tense.label} (e.g. ${spec.tense.example}) · ${spec.subject.label}",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (exercise == null && ui.busy) Text("Writing a sentence…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (exercise != null) {
                    Text(exercise.english, style = MaterialTheme.typography.headlineSmall)
                    ui.tries.forEachIndexed { i, t ->
                        Column {
                            Text(
                                (if (t.check.correct) "✓ " else "✗ ") + t.answer,
                                color = if (t.check.correct) ok else MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(t.check.feedback, style = MaterialTheme.typography.bodyMedium)
                            t.check.hint?.let { hint ->
                                val current = !ui.done && i == ui.tries.lastIndex
                                Text(
                                    "Hint: $hint",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = if (current) Modifier.padding(top = 4.dp)
                                        .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(8.dp))
                                        .padding(8.dp) else Modifier,
                                    color = if (current) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (!ui.done) {
                        LaunchedEffect(exercise) { runCatching { focus.requestFocus() } }
                        OutlinedTextField(
                            answer, { answer = it }, Modifier.fillMaxWidth().focusRequester(focus),
                            placeholder = { Text("En español… (try ${ui.tries.size + 1} of ${PracticePrompt.MAX_TRIES})") },
                            textStyle = MaterialTheme.typography.titleMedium,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { vm.check(answer) }),
                            enabled = !ui.busy,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button({ vm.check(answer) }, enabled = !ui.busy && answer.isNotBlank()) { Text(if (ui.busy) "Checking…" else "Check") }
                            TextButton(vm::giveUp, enabled = !ui.busy) { Text("Show answer") }
                        }
                    } else {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                val last = ui.tries.lastOrNull()?.answer
                                when {
                                    ui.solved && last != null && !sameAnswer(last, exercise.spanish) ->
                                        Text("Correct! Another way to say it: ${exercise.spanish}")
                                    ui.solved -> Text("Correct!", color = ok, fontWeight = FontWeight.Medium)
                                    else -> Text("Answer: ${exercise.spanish}", fontWeight = FontWeight.Medium)
                                }
                                exercise.verbForm?.let {
                                    Text("Verb: $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (ui.done || ui.error != null) {
                    Button(vm::next, Modifier.fillMaxWidth(), enabled = !ui.busy) { Text("Next verb") }
                } else if (exercise != null) {
                    TextButton(vm::next, enabled = !ui.busy) { Text("Skip this one") }
                }
            } else {
                ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
