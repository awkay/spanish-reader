package net.awkay.spanishreader.phrases

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.awkay.spanishreader.core.practice.Drill
import net.awkay.spanishreader.core.practice.PhraseCard
import net.awkay.spanishreader.core.practice.PhraseDeck
import net.awkay.spanishreader.core.practice.PhraseMode
import net.awkay.spanishreader.ui.appViewModel

/**
 * Say it: English, then Spanish with the target words blank; say them out loud, then "Show me".
 * Listen: play the Spanish sentence, translate it in your head, then "Show answer".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhraseScreen(deck: PhraseDeck, mode: PhraseMode, onExit: () -> Unit) {
    val vm = appViewModel(key = "phrases-${deck.id}-${mode.id}") { PhraseViewModel(it, deck, mode) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val card = ui.card

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${mode.label}: ${deck.title}") },
                navigationIcon = { IconButton(onExit) { Icon(Icons.Default.Close, "Stop") } },
                actions = {
                    if (card != null) Text("${ui.index + 1}/${ui.cards.size}", Modifier.padding(end = 12.dp), style = MaterialTheme.typography.bodyMedium)
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (card == null) {
                Text("That's all ${ui.cards.size}. ¡Muy bien!", style = MaterialTheme.typography.headlineSmall)
                Button(vm::again, Modifier.fillMaxWidth()) { Text("Go again ›") }
                TextButton(onExit) { Text("Done") }
                return@Column
            }
            if (mode == PhraseMode.SAY) {
                card.hint?.let { Hint(it) }
                Text(card.english, style = MaterialTheme.typography.headlineSmall)
                Sentence(card, ui.shown)
            } else {
                Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                    FilledIconButton(vm::play, Modifier.size(120.dp), shape = CircleShape, enabled = ui.audio != PhraseAudio.LOADING) {
                        when (ui.audio) {
                            PhraseAudio.LOADING -> CircularProgressIndicator(Modifier.size(40.dp))
                            PhraseAudio.PLAYING -> Icon(Icons.AutoMirrored.Filled.VolumeUp, "Playing", Modifier.size(56.dp))
                            PhraseAudio.IDLE -> Icon(Icons.Default.PlayArrow, "Play", Modifier.size(64.dp))
                        }
                    }
                }
                if (ui.shown) {
                    Sentence(card, true)
                    Text(card.english, style = MaterialTheme.typography.headlineSmall)
                    card.hint?.let { Hint(it) }
                }
            }
            if (ui.shown) card.note?.let {
                Card(Modifier.fillMaxWidth()) { Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium) }
            }
            ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (!ui.shown) {
                Button(vm::show, Modifier.fillMaxWidth()) { Text(if (mode == PhraseMode.SAY) "Show me" else "Show answer") }
                if (mode == PhraseMode.LISTEN) TextButton(vm::next) { Text("Next ›") }
            } else {
                Button(vm::next, Modifier.fillMaxWidth()) { Text(if (mode == PhraseMode.SAY) "Continue ›" else "Next ›") }
            }
        }
    }
}

@Composable
private fun Hint(text: String) =
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** The Spanish sentence with the blank empty, or filled in and highlighted. */
@Composable
private fun Sentence(card: PhraseCard, shown: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    Text(
        buildAnnotatedString {
            append(card.spanish.substringBefore(Drill.BLANK))
            withStyle(SpanStyle(color = accent, fontWeight = if (shown) FontWeight.SemiBold else null, textDecoration = TextDecoration.Underline)) {
                append(if (shown) card.answer else " ".repeat(10))
            }
            append(card.spanish.substringAfter(Drill.BLANK))
        },
        style = MaterialTheme.typography.headlineSmall,
    )
}
