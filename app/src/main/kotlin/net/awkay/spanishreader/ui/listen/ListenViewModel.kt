package net.awkay.spanishreader.ui.listen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.awkay.spanishreader.SpanishReaderApp
import net.awkay.spanishreader.audio.AudioState
import net.awkay.spanishreader.core.text.ListenScript
import net.awkay.spanishreader.core.text.Paginator
import net.awkay.spanishreader.core.text.Tokenizer

data class ListenUi(val loaded: Boolean = false, val missing: Boolean = false, val audio: AudioState = AudioState())

/** Sentence-list view of [net.awkay.spanishreader.audio.LessonAudio] for one lesson. */
class ListenViewModel(private val app: SpanishReaderApp, private val lessonId: Long) : ViewModel() {
    private val status = MutableStateFlow(ListenUi())
    val audio get() = app.audio

    val ui: StateFlow<ListenUi> = combine(status, app.audio.state) { s, a ->
        s.copy(audio = if (a.isFor(lessonId)) a else AudioState())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ListenUi())

    init {
        viewModelScope.launch {
            val lesson = app.lessons.get(lessonId) ?: run {
                status.value = ListenUi(loaded = true, missing = true)
                return@launch
            }
            val settings = app.settings.current()
            val script = withContext(Dispatchers.Default) {
                val text = Tokenizer.tokenize(lesson.text)
                ListenScript.build(text, Paginator.paginate(text, settings.wordsPerPage))
            }
            val startAt = script.indexOfFirst { it.pageIndex >= lesson.currentPage }.coerceAtLeast(0)
            app.audio.load(lessonId, lesson.title, script, startAt)
            status.value = ListenUi(loaded = true)
        }
    }
}
