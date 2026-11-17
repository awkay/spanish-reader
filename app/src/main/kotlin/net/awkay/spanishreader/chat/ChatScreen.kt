package net.awkay.spanishreader.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.awkay.spanishreader.ui.appViewModel

private val TOPIC_IDEAS = listOf("Mi día", "La comida", "El fin de semana", "Viajes", "La familia", "El trabajo")

/**
 * Conversation mode. The learner names a topic and speaks first (typing, or the microphone: tap to start, tap to
 * stop; the recognized text lands in the box to fix before sending). Partner replies are heard first: the Spanish
 * shows on tap, then the English via the translate icon. The translate icon on the learner's own bubbles shows what
 * they said in English and a minimal correction. Leaving or Finish saves the conversation as a lesson.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(onExit: () -> Unit, onOpenLesson: (Long) -> Unit) {
    val vm = appViewModel { ChatViewModel(it) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.toggleRecording() else vm.showError("The microphone permission was not given. You can still type.")
    }
    val leave: () -> Unit = { vm.stopAudio(); vm.saveThen { onExit() } }
    BackHandler(onBack = leave)
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.saveInBackground() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ui.topic ?: "Conversation", maxLines = 1) },
                navigationIcon = { IconButton(leave) { Icon(Icons.Default.Close, "Stop") } },
                actions = {
                    if (ui.exchanges > 0) {
                        TextButton({ vm.stopAudio(); vm.saveThen { id -> if (id != null) onOpenLesson(id) } }) {
                            Icon(Icons.Default.Check, null)
                            Text("Finish")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (ui.topic == null) {
                TopicPicker(vm::start, Modifier.weight(1f))
                return@Column
            }
            Conversation(ui, vm, Modifier.weight(1f))
            ui.error?.let { ErrorBar(it, retry = if (ui.unanswered != null) vm::retryReply else null, onDismiss = vm::dismissError) }
            Composer(ui, vm, onMic = {
                if (ui.recording || ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    vm.toggleRecording()
                } else {
                    mic.launch(Manifest.permission.RECORD_AUDIO)
                }
            })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TopicPicker(onStart: (String) -> Unit, modifier: Modifier) {
    var topic by rememberSaveable { mutableStateOf("") }
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("What do you want to talk about?", style = MaterialTheme.typography.titleLarge)
        Text(
            "You start the conversation. Type or tap the microphone and speak; English words are fine. " +
                "Replies are spoken first: tap a reply to read it.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(topic, { topic = it }, Modifier.fillMaxWidth(), label = { Text("Topic") }, singleLine = true)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TOPIC_IDEAS.forEach { idea -> AssistChip(onClick = { topic = idea }, label = { Text(idea) }) }
        }
        Button({ onStart(topic) }, Modifier.fillMaxWidth(), enabled = topic.isNotBlank()) { Text("Start") }
    }
}

@Composable
private fun Conversation(ui: ChatUi, vm: ChatViewModel, modifier: Modifier) {
    val list = rememberLazyListState()
    val count = ui.items.size + (if (ui.thinking) 1 else 0)
    LaunchedEffect(count) { if (count > 0) list.animateScrollToItem(count - 1) }
    LazyColumn(modifier.fillMaxWidth(), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (ui.items.isEmpty() && !ui.thinking) {
            item { Text("Say something about “${ui.topic}” to begin.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(ui.items, key = { it.id }) { item ->
            when (item) {
                is LearnerItem -> LearnerBubble(item, onRetry = { vm.retryCheck(item.id) })
                is PartnerItem -> PartnerBubble(item, playing = ui.playing == item.id, vm)
            }
        }
        if (ui.thinking) item(key = "thinking") { Thinking() }
    }
}

@Composable
private fun LearnerBubble(item: LearnerItem, onRetry: () -> Unit) {
    var open by rememberSaveable(item.id) { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Card(
            Modifier.widthIn(max = 320.dp),
            shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Column(Modifier.padding(start = 12.dp, top = 4.dp, end = 4.dp, bottom = 10.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(item.said, Modifier.weight(1f, fill = false).padding(top = 8.dp), style = MaterialTheme.typography.bodyLarge)
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        when {
                            item.checking -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            item.correction == null -> IconButton(onRetry) { Icon(Icons.Default.Refresh, "Check again", Modifier.size(20.dp)) }
                            else -> IconButton({ open = !open }) {
                                Box {
                                    Icon(Icons.Outlined.Translate, "What I said", Modifier.size(20.dp))
                                    if (!item.correction.ok) Surface(Modifier.size(8.dp).align(Alignment.TopEnd), shape = RoundedCornerShape(4.dp), color = AMBER) {}
                                }
                            }
                        }
                    }
                }
                val c = item.correction
                if (open && c != null) {
                    HorizontalDivider(Modifier.padding(vertical = 6.dp, horizontal = 0.dp).padding(end = 8.dp))
                    Column(Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(c.english, fontStyle = FontStyle.Italic, style = MaterialTheme.typography.bodyMedium)
                        if (c.ok) {
                            Text("✓ Good as it is", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        } else {
                            Text("Better: ${c.corrected}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            c.changes.forEach { ch ->
                                Text(
                                    (if (ch.from.isNotBlank()) "${ch.from} → ${ch.to}" else ch.to) + (if (ch.why.isNotBlank()) ": ${ch.why}" else ""),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PartnerBubble(item: PartnerItem, playing: Boolean, vm: ChatViewModel) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Card(
            Modifier.widthIn(max = 320.dp),
            shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Row(Modifier.padding(4.dp), verticalAlignment = Alignment.Top) {
                IconButton({ if (playing) vm.stopAudio() else vm.replay(item.id) }) {
                    if (playing) Icon(Icons.Default.Stop, "Stop audio") else Icon(Icons.Default.PlayArrow, "Play reply")
                }
                Column(Modifier.weight(1f, fill = false).padding(top = 10.dp, bottom = 8.dp)) {
                    if (!item.revealed) {
                        Text(
                            "Tap to show text",
                            Modifier.clickable { vm.reveal(item.id) },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(item.spanish, style = MaterialTheme.typography.bodyLarge)
                        if (item.english && item.translation.isNotEmpty()) {
                            Text(item.translation, Modifier.padding(top = 6.dp), fontStyle = FontStyle.Italic, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (item.audioFailed) {
                            Text("Audio unavailable", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                if (item.revealed) {
                    IconButton({ vm.toggleEnglish(item.id) }) { Icon(Icons.Outlined.Translate, "English", Modifier.size(20.dp)) }
                } else {
                    Spacer(Modifier.width(12.dp))
                }
            }
        }
    }
}

@Composable
private fun Thinking() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(16.dp)) {
        Text("…", Modifier.padding(horizontal = 18.dp, vertical = 8.dp), style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun ErrorBar(message: String, retry: (() -> Unit)?, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (retry != null) TextButton(retry) { Text("Retry") }
        IconButton(onDismiss) { Icon(Icons.Default.Close, "Dismiss", Modifier.size(18.dp)) }
    }
}

@Composable
private fun Composer(ui: ChatUi, vm: ChatViewModel, onMic: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            if (ui.recording) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("● ${ui.recordMillis / 1000}s", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
                    LinearProgressIndicator({ ui.level }, Modifier.weight(1f).padding(horizontal = 12.dp))
                    TextButton(vm::cancelRecording) { Text("Cancel") }
                }
            } else if (ui.transcribing) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text("Listening to what you said…", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelLarge)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    ui.draft, vm::setDraft, Modifier.weight(1f),
                    placeholder = { Text("Escribe o habla…") }, maxLines = 4,
                )
                IconButton(onMic, enabled = !ui.transcribing) {
                    if (ui.recording) Icon(Icons.Default.Stop, "Stop recording", tint = MaterialTheme.colorScheme.error)
                    else Icon(Icons.Default.Mic, "Speak")
                }
                IconButton(vm::send, enabled = ui.draft.isNotBlank() && !ui.thinking && !ui.recording) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send")
                }
            }
        }
    }
}

private val AMBER = Color(0xFFF2A900)
