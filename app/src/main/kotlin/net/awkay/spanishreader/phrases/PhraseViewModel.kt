package net.awkay.spanishreader.phrases

import android.media.MediaPlayer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.awkay.spanishreader.SpanishReaderApp
import net.awkay.spanishreader.core.practice.PhraseCard
import net.awkay.spanishreader.core.practice.PhraseDeck
import net.awkay.spanishreader.core.practice.PhraseDecks
import net.awkay.spanishreader.core.practice.PhraseMode
import java.io.File

enum class PhraseAudio { IDLE, LOADING, PLAYING }

data class PhraseUi(
    val cards: List<PhraseCard>,
    val index: Int = 0,
    /** The answer is showing ("Show me" / "Show answer" was pressed). */
    val shown: Boolean = false,
    val audio: PhraseAudio = PhraseAudio.IDLE,
    val error: String? = null,
) {
    val card: PhraseCard? get() = cards.getOrNull(index)
}

/** Say it / listen practice over a fixed deck (core PhraseDecks). Listening plays the sentence with the voice from Settings. */
class PhraseViewModel(private val app: SpanishReaderApp, val deck: PhraseDeck, val mode: PhraseMode) : ViewModel() {
    private val _ui = MutableStateFlow(PhraseUi(PhraseDecks.session(deck)))
    val ui: StateFlow<PhraseUi> = _ui
    private var player: MediaPlayer? = null

    init {
        prefetch()
    }

    fun show() = _ui.update { it.copy(shown = true) }

    fun next() {
        stop()
        _ui.update { it.copy(index = it.index + 1, shown = false, error = null, audio = PhraseAudio.IDLE) }
        prefetch()
    }

    fun again() {
        stop()
        _ui.value = PhraseUi(PhraseDecks.session(deck))
        prefetch()
    }

    fun play() {
        val card = _ui.value.card ?: return
        if (_ui.value.audio == PhraseAudio.LOADING) return
        viewModelScope.launch {
            _ui.update { it.copy(audio = PhraseAudio.LOADING, error = null) }
            try {
                val file = audioFor(card)
                stop()
                val speed = app.settings.current().playbackSpeed
                player = MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    prepare()
                    if (speed != 1f) playbackParams = playbackParams.setSpeed(speed)
                    setOnCompletionListener { _ui.update { u -> u.copy(audio = PhraseAudio.IDLE) } }
                    start()
                }
                _ui.update { it.copy(audio = PhraseAudio.PLAYING) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(audio = PhraseAudio.IDLE, error = "Couldn't play the sentence: ${e.message ?: e}") }
            }
        }
    }

    private val pending = HashMap<String, Deferred<File>>()

    /**
     * Synthesized once per sentence and kept in the sentence audio cache (like lesson audio). A prefetch and a tap on
     * the same sentence share one request: the cache writes through one temp file per sentence.
     */
    private suspend fun audioFor(card: PhraseCard): File {
        val text = card.filled
        val job = pending.getOrPut(text) {
            viewModelScope.async(Dispatchers.IO) { app.audioCache.ensure(app.synthesizer(app.settings.current()), text) }
        }
        return try {
            job.await()
        } catch (e: Exception) {
            if (e !is CancellationException) pending.remove(text)
            throw e
        }
    }

    /** Listening: have the current and next sentences' audio ready before they're needed. */
    private fun prefetch() {
        if (mode != PhraseMode.LISTEN) return
        val s = _ui.value
        val cards = listOfNotNull(s.card, s.cards.getOrNull(s.index + 1))
        viewModelScope.launch { for (c in cards) runCatching { audioFor(c) } }
    }

    private fun stop() {
        player?.release()
        player = null
    }

    override fun onCleared() = stop()
}
