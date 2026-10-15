package net.awkay.spanishreader.drill

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.awkay.spanishreader.SpanishReaderApp
import net.awkay.spanishreader.core.practice.CommonVerb
import net.awkay.spanishreader.core.practice.CommonVerbs
import net.awkay.spanishreader.core.practice.Drill
import net.awkay.spanishreader.core.practice.DrillCard
import net.awkay.spanishreader.core.practice.DrillProgress

data class DrillUi(
    val sets: Map<String, List<DrillCard>> = emptyMap(),
    val progress: Map<String, DrillProgress> = emptyMap(),
    /** The verb being drilled; null on the start screen. */
    val verb: CommonVerb? = null,
    val queue: List<DrillCard> = emptyList(),
    val index: Int = 0,
    val score: Int = 0,
    val learnedNow: Int = 0,
    val feedback: DrillAnswer? = null,
    /** What was typed for the card on screen (shown after a miss). */
    val typed: String = "",
    val loading: String? = null,
    val error: String? = null,
) {
    val card: DrillCard? get() = queue.getOrNull(index)
    val finished: Boolean get() = verb != null && index >= queue.size
    val current: CommonVerb get() = Drill.currentVerb({ sets[it] }, progress)

    fun learnedOf(verb: String): Pair<Int, Int>? =
        sets[verb]?.let { cards -> cards.count { Drill.isLearned(progress[it.id]) } to cards.size }
}

class DrillViewModel(private val app: SpanishReaderApp) : ViewModel() {
    private val _ui = MutableStateFlow(DrillUi())
    val ui: StateFlow<DrillUi> = _ui
    private var advanceJob: Job? = null

    init {
        viewModelScope.launch { _ui.update { it.copy(sets = app.drill.cardSets(), progress = app.drill.progress()) } }
        viewModelScope.launch { app.drill.observeProgress().collect { p -> _ui.update { it.copy(progress = p) } } }
    }

    fun start(verb: CommonVerb) {
        if (_ui.value.loading != null) return
        _ui.update { it.copy(error = null, loading = if (verb.infinitive in it.sets) null else "Writing cards for ${verb.infinitive}…") }
        viewModelScope.launch {
            try {
                val cards = app.drill.cardsFor(verb)
                val progress = app.drill.progress()
                _ui.update {
                    it.copy(
                        sets = it.sets + (verb.infinitive to cards), progress = progress, verb = verb, queue = Drill.round(cards, progress),
                        index = 0, score = 0, learnedNow = 0, feedback = null, typed = "", loading = null,
                    )
                }
                // Get the next verb's cards ready while this one is drilled.
                CommonVerbs.ALL.getOrNull(CommonVerbs.ALL.indexOf(verb) + 1)?.let { next ->
                    launch {
                        runCatching { app.drill.cardsFor(next) }.getOrNull()?.let { c -> _ui.update { it.copy(sets = it.sets + (next.infinitive to c)) } }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(loading = null, error = e.message ?: e.toString()) }
            }
        }
    }

    fun submit(typed: String) {
        val s = _ui.value
        val card = s.card ?: return
        if (s.feedback != null || typed.isBlank()) return
        viewModelScope.launch {
            val r = app.drill.answer(card, typed)
            _ui.update {
                it.copy(
                    feedback = r, typed = typed.trim(),
                    score = it.score + if (r.verdict == net.awkay.spanishreader.core.practice.DrillVerdict.CORRECT) 1 else 0,
                    learnedNow = it.learnedNow + if (r.learned) 1 else 0,
                )
            }
            if (r.verdict == net.awkay.spanishreader.core.practice.DrillVerdict.CORRECT) {
                advanceJob = launch {
                    delay(if (r.learned) 1200 else 650)
                    next()
                }
            }
        }
    }

    fun next() {
        advanceJob?.cancel()
        _ui.update { if (it.card == null) it else it.copy(index = it.index + 1, feedback = null, typed = "") }
    }

    fun hideCard() {
        val card = _ui.value.card ?: return
        viewModelScope.launch {
            app.drill.hide(card)
            next()
        }
    }

    fun leave() {
        advanceJob?.cancel()
        _ui.update { it.copy(verb = null, queue = emptyList(), index = 0, feedback = null) }
    }
}
