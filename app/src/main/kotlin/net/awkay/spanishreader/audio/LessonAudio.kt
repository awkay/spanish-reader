package net.awkay.spanishreader.audio

import android.content.ComponentName
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.awkay.spanishreader.SpanishReaderApp
import net.awkay.spanishreader.core.text.ListenSentence
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class AudioState(
    val lessonId: Long? = null,
    val title: String = "",
    val sentences: List<ListenSentence> = emptyList(),
    /** Index into [sentences] of the sentence playing (or last played). */
    val current: Int = 0,
    /** True once something has been queued for [lessonId]. */
    val started: Boolean = false,
    val isPlaying: Boolean = false,
    val loop: Boolean = false,
    val speed: Float = 1f,
    /** Generating audio for the sentence about to play. */
    val preparing: Boolean = false,
    val error: String? = null,
) {
    val currentSentence: ListenSentence? get() = sentences.getOrNull(current)

    fun isFor(id: Long) = lessonId == id
}

/**
 * App-wide listen-mode engine shared by the reader and the listen screen. It drives [PlaybackService] through one
 * MediaController: the playlist holds one audio file per sentence starting at [playlistStart]; audio is synthesized
 * (and cached) sentence by sentence and appended as it becomes ready. Living in the application scope, synthesis
 * keeps going when the user leaves a screen. Jumping outside the queued range rebuilds the playlist from there.
 */
class LessonAudio(private val app: SpanishReaderApp) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(AudioState())
    val state: StateFlow<AudioState> = _state.asStateFlow()

    private val connectLock = Mutex()
    private var controller: MediaController? = null
    private var synthJob: Job? = null
    private var playlistStart = 0

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = sync()
    }

    private suspend fun controller(): MediaController = connectLock.withLock {
        controller?.let { return it }
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        val c = suspendCancellableCoroutine { cont ->
            future.addListener({
                try {
                    cont.resume(future.get())
                } catch (e: Exception) {
                    cont.resumeWithException(e.cause ?: e)
                }
            }, ContextCompat.getMainExecutor(app))
            cont.invokeOnCancellation { MediaController.releaseFuture(future) }
        }
        c.addListener(listener)
        controller = c
        c
    }

    /**
     * Makes [lessonId] the current lesson. Switching lessons stops the previous one; reloading the same lesson keeps
     * whatever is playing. [startAt] is where playback will begin if nothing has been queued yet.
     */
    fun load(lessonId: Long, title: String, sentences: List<ListenSentence>, startAt: Int = 0) {
        val s = _state.value
        if (s.lessonId == lessonId && s.sentences == sentences) {
            if (!s.started) _state.update { it.copy(current = startAt.coerceIn(0, (sentences.size - 1).coerceAtLeast(0))) }
            return
        }
        synthJob?.cancel()
        controller?.run {
            stop()
            clearMediaItems()
        }
        playlistStart = 0
        scope.launch {
            val speed = app.settings.current().playbackSpeed
            _state.update { it.copy(speed = speed) }
        }
        _state.value = AudioState(
            lessonId = lessonId, title = title, sentences = sentences,
            current = startAt.coerceIn(0, (sentences.size - 1).coerceAtLeast(0)),
            speed = s.speed, loop = s.loop,
        )
    }

    private fun parseIndex(mediaId: String?): Int? {
        val parts = mediaId?.split(':') ?: return null
        if (parts.size != 2 || parts[0].toLongOrNull() != _state.value.lessonId) return null
        return parts[1].toIntOrNull()
    }

    private fun sync() {
        val c = controller ?: return
        val index = parseIndex(c.currentMediaItem?.mediaId)
        _state.update { s ->
            s.copy(
                current = index ?: s.current,
                isPlaying = c.isPlaying,
                loop = c.repeatMode == Player.REPEAT_MODE_ONE,
                preparing = s.preparing && !c.isPlaying,
            )
        }
        // Playback caught up with synthesis after a failure or cancellation: carry on from the next sentence.
        val queuedEnd = playlistStart + c.mediaItemCount
        if (c.playbackState == Player.STATE_ENDED && c.playWhenReady && synthJob?.isActive != true &&
            c.mediaItemCount > 0 && queuedEnd < _state.value.sentences.size && _state.value.error == null
        ) {
            synthesize(queuedEnd, startPlayback = true, clear = false)
        }
    }

    private fun item(index: Int, file: File): MediaItem {
        val s = _state.value
        val uri = Uri.fromFile(file)
        return MediaItem.Builder()
            .setMediaId("${s.lessonId}:$index")
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

    private fun synthesize(from: Int, startPlayback: Boolean, clear: Boolean) {
        synthJob?.cancel()
        val lessonId = _state.value.lessonId ?: return
        if (clear) _state.update { it.copy(current = from, preparing = true, error = null, started = true) }
        synthJob = scope.launch {
            val c = try {
                controller()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(preparing = false, error = "Could not start the audio service: ${e.message}") }
                return@launch
            }
            if (clear) {
                c.stop()
                c.clearMediaItems()
                playlistStart = from
            }
            val settings = app.settings.current()
            val synth = app.synthesizer(settings)
            c.setPlaybackSpeed(_state.value.speed)
            c.repeatMode = if (_state.value.loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
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
                if (_state.value.lessonId != lessonId) return@launch
                c.addMediaItem(item(j, file))
                if (j == from && (clear || c.mediaItemCount == 1)) {
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

    private val queuedRange: IntRange get() = playlistStart until playlistStart + (controller?.mediaItemCount ?: 0)

    /** Plays from sentence [index], reusing already-queued audio when possible. */
    fun playFrom(index: Int) {
        val s = _state.value
        if (index !in s.sentences.indices) return
        val c = controller
        if (c != null && index in queuedRange) {
            c.seekTo(index - playlistStart, 0)
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        } else {
            synthesize(index, startPlayback = true, clear = true)
        }
    }

    fun play() {
        val s = _state.value
        val c = controller
        when {
            c == null || c.mediaItemCount == 0 || !s.started -> playFrom(s.current)
            c.playbackState == Player.STATE_ENDED && s.current >= s.sentences.lastIndex -> playFrom(0)
            else -> {
                if (c.playbackState == Player.STATE_IDLE) c.prepare()
                c.play()
            }
        }
    }

    fun pause() {
        controller?.pause()
    }

    fun playPause() = if (_state.value.isPlaying) pause() else play()

    fun next() = playFrom(_state.value.current + 1)

    fun previous() = playFrom(_state.value.current - 1)

    fun replay() = playFrom(_state.value.current)

    fun toggleLoop() {
        val c = controller
        if (c != null) {
            c.repeatMode = if (c.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE
        } else {
            _state.update { it.copy(loop = !it.loop) }
        }
    }

    fun setSpeed(speed: Float) {
        val s = (Math.round(speed * 20) / 20f).coerceIn(0.5f, 2f)
        controller?.setPlaybackSpeed(s)
        _state.update { it.copy(speed = s) }
        scope.launch { app.settings.update { it.copy(playbackSpeed = s) } }
    }
}
