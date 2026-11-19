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
import net.awkay.spanishreader.core.practice.PhrasePool
import net.awkay.spanishreader.core.practice.PhraseProgress
import net.awkay.spanishreader.core.practice.PhraseMode
import java.io.File

enum class PhraseAudio { IDLE, LOADING, PLAYING }

data class PhraseUi(
    val cards: List<PhraseCard> = emptyList(),
    val index: Int = 0,
    /** The pool is still being read. */
    val loading: Boolean = true,
    /** The answer is showing ("Show me" / "Show answer" was pressed). */
    val shown: Boolean = false,
    val audio: PhraseAudio = PhraseAudio.IDLE,
    val error: String? = null,
    /** Scores in this mode, by card id. */
    val scores: Map<String, PhraseProgress> = emptyMap(),
    val right: Int = 0,
    val wrong: Int = 0,
    val retired: Int = 0,
    /** Shown once after a sentence retires. */
    val flash: String? = null,
) {
    val card: PhraseCard? get() = if (loading) null else cards.getOrNull(index)
    val score: Int get() = card?.let { scores[it.id]?.score } ?: 0
}

/**
 * Say it / listen practice over the deck's pool (core PhrasePool): after the answer the learner says whether they got
 * it; three more rights than misses retire a sentence and a new one takes its place. Listening plays the sentence with
 * the voice from Settings.
 */
class PhraseViewModel(private val app: SpanishReaderApp, val deck: PhraseDeck, val mode: PhraseMode) : ViewModel() {
    private val _ui = MutableStateFlow(PhraseUi())
    val ui: StateFlow<PhraseUi> = _ui
    private var player: MediaPlayer? = null
    private val service get() = app.phraseService

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val progress = service.progress()
            val cards = PhrasePool.session(service.pool(deck, mode), mode, progress)
            val scores = progress.values.filter { it.mode == mode.id }.associateBy { it.cardId }
            _ui.value = PhraseUi(cards, loading = false, scores = scores)
            prefetch()
            topUp()
        }
    }

    /** Has the AI write more sentences in the background when retirements left the deck short. */
    private fun topUp() {
        viewModelScope.launch {
            try {
                service.topUp(deck)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The hand-written reserve fills in; the next session tries again.
            }
        }
    }

    fun show() = _ui.update { it.copy(shown = true) }

    /** "Got it" / "Missed it": scores the sentence, then moves on. */
    fun answer(right: Boolean) {
        val card = _ui.value.card ?: return
        viewModelScope.launch {
            val a = service.answer(mode, card, right)
            _ui.update {
                it.copy(
                    scores = it.scores + (card.id to a.progress),
                    right = it.right + if (right) 1 else 0,
                    wrong = it.wrong + if (right) 0 else 1,
                    retired = it.retired + if (a.retired) 1 else 0,
                    flash = if (a.retired) "Retired ✓ A new sentence will take its place." else null,
                )
            }
            advance()
            if (a.retired) topUp()
        }
    }

    /** Skips the sentence without scoring it. */
    fun next() {
        _ui.update { it.copy(flash = null) }
        advance()
    }

    private fun advance() {
        stop()
        _ui.update { it.copy(index = it.index + 1, shown = false, error = null, audio = PhraseAudio.IDLE) }
        prefetch()
    }

    fun again() {
        stop()
        load()
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
