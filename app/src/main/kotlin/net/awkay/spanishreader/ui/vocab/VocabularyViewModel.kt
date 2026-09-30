package net.awkay.spanishreader.ui.vocab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.awkay.spanishreader.SpanishReaderApp
import net.awkay.spanishreader.core.text.Tokenizer
import net.awkay.spanishreader.core.vocab.VocabEntry
import net.awkay.spanishreader.core.vocab.WordStatus

enum class VocabFilter(val label: String, val matches: (WordStatus) -> Boolean) {
    LEARNING("Learning", { it.isLearning }),
    LEVEL_1("1", { it == WordStatus.LEVEL_1 }),
    LEVEL_2("2", { it == WordStatus.RECOGNIZED }),
    LEVEL_3("3", { it == WordStatus.FAMILIAR }),
    LEVEL_4("4", { it == WordStatus.LEARNED }),
    KNOWN("Known", { it == WordStatus.KNOWN }),
    IGNORED("Ignored", { it == WordStatus.IGNORED }),
    ALL("All", { true }),
}

data class VocabUi(val entries: List<VocabEntry>, val counts: Map<VocabFilter, Int>)

class VocabularyViewModel(private val app: SpanishReaderApp) : ViewModel() {
    val filter = MutableStateFlow(VocabFilter.LEARNING)
    val query = MutableStateFlow("")

    val ui: StateFlow<VocabUi?> = combine(app.vocab.observeAll(), filter, query) { all, f, q ->
        val needle = Tokenizer.normalize(q.trim())
        val shown = all.filter { e ->
            f.matches(e.status) && (needle.isEmpty() || needle in e.form || e.lemma?.let { needle in Tokenizer.normalize(it) } == true ||
                e.translation?.lowercase()?.contains(needle) == true)
        }
        VocabUi(shown, VocabFilter.entries.associateWith { vf -> all.count { vf.matches(it.status) } })
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setStatus(form: String, status: WordStatus) = viewModelScope.launch { app.vocab.setStatus(form, status) }

    fun delete(form: String) = viewModelScope.launch { app.vocab.delete(form) }
}
