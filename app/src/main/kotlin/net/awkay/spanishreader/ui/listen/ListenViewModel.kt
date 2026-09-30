package net.awkay.spanishreader.ui.listen

import android.content.ComponentName
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import net.awkay.spanishreader.SpanishReaderApp
import net.awkay.spanishreader.audio.PlaybackService
import net.awkay.spanishreader.core.text.ListenScript
import net.awkay.spanishreader.core.text.ListenSentence
import net.awkay.spanishreader.core.text.Paginator
import net.awkay.spanishreader.core.text.Tokenizer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ListenState(
    val title: String = "",
    val sentences: List<ListenSentence> = emptyList(),
    /** Index into [sentences]. */
    val current: Int = 0,
    val isPlaying: Boolean = false,
    val loop: Boolean = false,
    val speed: Float = 1f,
    /** Generating audio for the sentence about to play. */
    val preparing: Boolean = false,
    val error: String? = null,
    val loaded: Boolean = false,
)

/**
 * Drives [PlaybackService] through a MediaController. The playlist holds one item per sentence, starting at
 * [playlistStart]; audio is synthesized (and cached) sentence by sentence and appended as it becomes ready.
 * Jumping to a sentence outside the playlist rebuilds it from there.
 */
class ListenViewModel(private val app: SpanishReaderApp, private val lessonId: Long) : ViewModel() {
    private val _state = MutableStateFlow(ListenState())
    val state: StateFlow<ListenState> = _state.asStateFlow()

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var synthJob: Job? = null
    private var playlistStart = 0

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = syncFromPlayer()
    }

    init {
        viewModelScope.launch {
            val lesson = app.lessons.get(lessonId) ?: run {
                _state.update { it.copy(loaded = true, error = "This lesson no longer exists.") }
                return@launch
            }
            val settings = app.settings.current()
            val script = withContext(Dispatchers.Default) {
                val text = Tokenizer.tokenize(lesson.text)
                ListenScript.build(text, Paginator.paginate(text, settings.wordsPerPage))
            }
            val startAt = script.indexOfFirst { it.pageIndex >= lesson.currentPage }.coerceAtLeast(0)
            _state.update { it.copy(title = lesson.title, sentences = script, current = startAt, speed = settings.playbackSpeed, loaded = true) }
            try {
                val c = connect()
                controller = c
                c.addListener(listener)
                adoptExistingPlaylist(c)
                syncFromPlayer()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = "Could not start the audio service: ${e.message}") }
            }
        }
    }

    private suspend fun connect(): MediaController {
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        controllerFuture = future
        return suspendCancellableCoroutine { cont ->
            future.addListener({
                try {
                    cont.resume(future.get())
                } catch (e: Exception) {
                    cont.resumeWithException(e.cause ?: e)
                }
            }, ContextCompat.getMainExecutor(app))
            cont.invokeOnCancellation { MediaController.releaseFuture(future) }
        }
    }

    /** If the service is already playing this lesson (screen reopened), keep its playlist. */
    private fun adoptExistingPlaylist(c: MediaController) {
        if (c.mediaItemCount == 0) return
        val first = parseId(c.getMediaItemAt(0).mediaId)
        if (first != null && first.first == lessonId) {
            playlistStart = first.second
            // Keep appending the rest of the lesson behind what is already queued.
            val next = playlistStart + c.mediaItemCount
            if (next < _state.value.sentences.size) synthesizeFrom(next, startPlayback = false, clear = false)
        } else {
            c.stop()
            c.clearMediaItems()
        }
    }

    private fun mediaId(index: Int) = "$lessonId:$index"

    private fun parseId(id: String): Pair<Long, Int>? =
        id.split(':').takeIf { it.size == 2 }?.let { (l, i) -> l.toLongOrNull()?.let { lid -> i.toIntOrNull()?.let { lid to it } } }

    private fun syncFromPlayer() {
        val c = controller ?: return
        val index = c.currentMediaItem?.mediaId?.let(::parseId)?.takeIf { it.first == lessonId }?.second
        _state.update { s ->
            s.copy(
                current = index ?: s.current,
                isPlaying = c.isPlaying,
                loop = c.repeatMode == Player.REPEAT_MODE_ONE,
                preparing = s.preparing && !c.isPlaying,
            )
        }
    }

    private fun item(index: Int, file: java.io.File): MediaItem {
        val s = _state.value
        val uri = Uri.fromFile(file)
        return MediaItem.Builder()
            .setMediaId(mediaId(index))
            .setUri(uri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(s.sentences[index].text)
                    .setArtist(s.title)
                    .setTrackNumber(index + 1)
                    .setTotalTrackCount(s.sentences.size)
                    .build(),
            )
            .build()
    }

    /** Rebuilds (when [clear]) or extends the playlist, synthesizing from sentence [from] to the end of the lesson. */
    private fun synthesizeFrom(from: Int, startPlayback: Boolean, clear: Boolean) {
        val c = controller ?: return
        synthJob?.cancel()
        if (clear) {
            c.stop()
            c.clearMediaItems()
            playlistStart = from
            _state.update { it.copy(current = from, preparing = true, error = null) }
        }
        synthJob = viewModelScope.launch {
            val settings = app.settings.current()
            val synth = app.synthesizer(settings)
            c.setPlaybackSpeed(settings.playbackSpeed)
            val sentences = _state.value.sentences
            for (j in from until sentences.size) {
                val file = try {
                    withContext(Dispatchers.IO) { app.audioCache.ensure(synth, sentences[j].text) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.update { it.copy(preparing = false, error = "Speech synthesis failed: ${e.message}") }
                    return@launch
                }
                c.addMediaItem(item(j, file))
                if (clear && j == from) {
                    c.prepare()
                    if (startPlayback) c.play()
                    _state.update { it.copy(preparing = false) }
                } else if (c.playbackState == Player.STATE_ENDED && c.playWhenReady) {
                    // Playback caught up with synthesis; continue with the sentence just added.
                    c.seekTo(c.mediaItemCount - 1, 0)
                    c.prepare()
                }
            }
        }
    }

    private val playlistRange: IntRange get() = playlistStart until playlistStart + (controller?.mediaItemCount ?: 0)

    fun playPause() {
        val c = controller ?: return
        when {
            c.mediaItemCount == 0 -> synthesizeFrom(_state.value.current, startPlayback = true, clear = true)
            c.isPlaying -> c.pause()
            c.playbackState == Player.STATE_ENDED && _state.value.current >= _state.value.sentences.lastIndex ->
                synthesizeFrom(0, startPlayback = true, clear = true)
            else -> {
                if (c.playbackState == Player.STATE_IDLE) c.prepare()
                c.play()
            }
        }
    }

    fun seekTo(index: Int) {
        val c = controller ?: return
        if (index !in _state.value.sentences.indices) return
        if (index in playlistRange) {
            c.seekTo(index - playlistStart, 0)
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        } else {
            synthesizeFrom(index, startPlayback = true, clear = true)
        }
    }

    fun next() = seekTo(_state.value.current + 1)

    fun previous() = seekTo(_state.value.current - 1)

    /** Replays the current sentence from its start. */
    fun replay() = seekTo(_state.value.current)

    fun toggleLoop() {
        val c = controller ?: return
        c.repeatMode = if (c.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE
    }

    fun setSpeed(speed: Float) {
        val s = (Math.round(speed * 20) / 20f).coerceIn(0.5f, 2f)
        controller?.setPlaybackSpeed(s)
        _state.update { it.copy(speed = s) }
        viewModelScope.launch { app.settings.update { it.copy(playbackSpeed = s) } }
    }

    override fun onCleared() {
        controller?.removeListener(listener)
        controllerFuture?.let(MediaController::releaseFuture)
        controller = null
    }
}
