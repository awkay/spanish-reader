package net.awkay.spanishreader.ui.practice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.awkay.spanishreader.SpanishReaderApp
import net.awkay.spanishreader.core.practice.AnswerCheck
import net.awkay.spanishreader.core.practice.PracticePrompt
import net.awkay.spanishreader.core.practice.PracticeSpec
import net.awkay.spanishreader.core.practice.PracticeVerb
import net.awkay.spanishreader.core.practice.VerbExercise
import net.awkay.spanishreader.core.practice.VerbPool
import net.awkay.spanishreader.core.practice.VerbPracticeAi
import net.awkay.spanishreader.core.vocab.VocabEntry

data class PracticeTry(val answer: String, val check: AnswerCheck)

data class PracticeUi(
    /** Null while loading. */
    val verbCount: Int? = null,
    val spec: PracticeSpec? = null,
    val exercise: VerbExercise? = null,
    val tries: List<PracticeTry> = emptyList(),
    val gaveUp: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
) {
    val solved: Boolean get() = tries.any { it.check.correct }
    val done: Boolean get() = solved || gaveUp || tries.size >= PracticePrompt.MAX_TRIES
}

/** Verb practice: the verb, tense and subject are picked from the vocabulary; the AI writes and checks the sentence. */
class PracticeViewModel(private val app: SpanishReaderApp) : ViewModel() {
    private val _ui = MutableStateFlow(PracticeUi())
    val ui: StateFlow<PracticeUi> = _ui

    private var verbs: List<PracticeVerb> = emptyList()
    private var entries: List<VocabEntry> = emptyList()
    private val recent = ArrayDeque<String>()
    private var job: Job? = null

    init {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.Default) {
                    entries = app.vocab.all()
                    val glosses = app.glossCache.glossesByForm(entries.filter(VerbPool::isExposed).map { it.form })
                    verbs = VerbPool.verbs(entries) { glosses[it].orEmpty() }
                }
                _ui.update { it.copy(verbCount = verbs.size) }
                next()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(verbCount = 0, error = e.message ?: e.toString()) }
            }
        }
    }

    fun next() {
        val spec = VerbPool.pick(verbs, entries, recent = recent)
        job?.cancel()
        _ui.value = PracticeUi(verbCount = verbs.size, spec = spec)
        if (spec == null) return
        recent.addFirst(spec.verb.infinitive)
        while (recent.size > minOf(5, verbs.size - 1)) recent.removeLast()
        job = viewModelScope.launch {
            val ai = ai() ?: return@launch
            _ui.update { it.copy(busy = true) }
            val result = runCatching { ai.exercise(spec) }
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            _ui.update { it.copy(busy = false, exercise = result.getOrNull(), error = result.exceptionOrNull()?.let(::message)) }
        }
    }

    fun check(answer: String) {
        val s = _ui.value
        val spec = s.spec ?: return
        val exercise = s.exercise ?: return
        val a = answer.trim()
        if (a.isEmpty() || s.busy || s.done) return
        job = viewModelScope.launch {
            val ai = ai() ?: return@launch
            _ui.update { it.copy(busy = true, error = null) }
            val result = runCatching { ai.check(spec, exercise, s.tries.map { it.answer }, a) }
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            _ui.update { u ->
                result.fold(
                    { u.copy(busy = false, tries = u.tries + PracticeTry(a, it)) },
                    { u.copy(busy = false, error = message(it)) },
                )
            }
        }
    }

    fun giveUp() = _ui.update { it.copy(gaveUp = true) }

    private suspend fun ai(): VerbPracticeAi? {
        val completer = app.glossService.completer()
        if (completer == null) _ui.update { it.copy(error = "Glossing is not configured. Set it up in Settings.") }
        return completer?.let(::VerbPracticeAi)
    }

    private fun message(e: Throwable) = e.message ?: e.toString()
}
