package net.awkay.spanishreader.ui.listen

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.awkay.spanishreader.audio.AudioState
import net.awkay.spanishreader.audio.LessonAudio
import net.awkay.spanishreader.ui.BackButton
import net.awkay.spanishreader.ui.appViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListenScreen(lessonId: Long, onBack: () -> Unit, onRead: () -> Unit) {
    val vm = appViewModel(key = "listen-$lessonId") { ListenViewModel(it, lessonId) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val s = ui.audio
    val list = rememberLazyListState()

    // Lock-screen / notification controls need notification permission on Android 13+.
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    LaunchedEffect(s.current, ui.loaded) {
        if (s.sentences.isNotEmpty()) list.animateScrollToItem((s.current - 2).coerceAtLeast(0))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { BackButton(onBack) },
                actions = { IconButton(onClick = onRead) { Icon(Icons.AutoMirrored.Filled.MenuBook, "Read") } },
            )
        },
        bottomBar = { Controls(s, vm.audio) },
    ) { padding ->
        if (!ui.loaded || ui.missing) {
            Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                if (ui.missing) Text("This lesson no longer exists.") else CircularProgressIndicator()
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding), state = list) {
            itemsIndexed(s.sentences, key = { _, it -> it.sentenceIndex }) { i, sentence ->
                val current = i == s.current
                Text(
                    sentence.text,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { vm.audio.playFrom(i) }
                        .padding(horizontal = 12.dp, vertical = 2.dp)
                        .background(
                            if (current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                            RoundedCornerShape(8.dp),
                        )
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun Controls(s: AudioState, audio: LessonAudio) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (s.sentences.isNotEmpty()) {
                Text(
                    "Sentence ${s.current + 1} of ${s.sentences.size}" + if (s.preparing) " · preparing audio…" else "",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = audio::previous) { Icon(Icons.Default.SkipPrevious, "Previous sentence") }
                IconButton(onClick = audio::replay) { Icon(Icons.Default.Replay, "Replay sentence") }
                FilledIconButton(onClick = audio::playPause, modifier = Modifier.size(56.dp)) {
                    if (s.preparing) CircularProgressIndicator(Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                    else Icon(if (s.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (s.isPlaying) "Pause" else "Play")
                }
                FilledTonalIconToggleButton(checked = s.loop, onCheckedChange = { audio.toggleLoop() }) {
                    Icon(Icons.Default.RepeatOne, "Loop sentence")
                }
                IconButton(onClick = audio::next) { Icon(Icons.Default.SkipNext, "Next sentence") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Speed %.2f×".format(s.speed), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 8.dp))
                Slider(value = s.speed, onValueChange = audio::setSpeed, valueRange = 0.5f..2f, steps = 29, modifier = Modifier.weight(1f))
            }
        }
    }
}
