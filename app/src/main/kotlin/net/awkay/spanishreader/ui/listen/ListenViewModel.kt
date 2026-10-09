package net.awkay.spanishreader.ui.listen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.awkay.spanishreader.SpanishReaderApp
import net.awkay.spanishreader.audio.AudioState
import net.awkay.spanishreader.core.text.ListenScript
import net.awkay.spanishreader.core.text.Paginator
import net.awkay.spanishreader.core.text.Tokenizer

data class ListenUi(
    val loaded: Boolean = false,
    val missing: Boolean = false,
    val audio: AudioState = AudioState(),
    val showTranslations: Boolean = false,
    /** Stored English translations, by sentence text. */
    val translations: Map<String, String> = emptyMap(),
)

/** Sentence-list view of [net.awkay.spanishreader.audio.LessonAudio] for one lesson. */
class ListenViewModel(private val app: SpanishReaderApp, private val lessonId: Long) : ViewModel() {
    private val status = MutableStateFlow(ListenUi())
    val audio get() = app.audio

    private val showTranslations = MutableStateFlow(false)
    private val script = MutableStateFlow<List<String>>(emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    private val translations = script.flatMapLatest { app.phrases.observeTranslations(it) }

    val ui: StateFlow<ListenUi> = combine(status, app.audio.state, showTranslations, translations) { s, a, show, tr ->
        s.copy(audio = if (a.isFor(lessonId)) a else AudioState(), showTranslations = show, translations = tr)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ListenUi())

    init {
        // With translations on, make sure the sentence being played has one (fetched once, then stored).
        viewModelScope.launch {
            combine(showTranslations, app.audio.state) { show, a -> if (show && a.isFor(lessonId)) a.currentSentence?.text else null }
                .distinctUntilChanged()
                .collect { sentence ->
                    if (sentence != null && ui.value.translations[sentence] == null) app.glossService.translate(sentence)
                }
        }
    }

    fun toggleTranslations() {
        showTranslations.value = !showTranslations.value
    }

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
            app.audio.load(lessonId, lesson.title, script, startAt, lesson.videoId)
            this@ListenViewModel.script.value = script.map { it.text }
            status.value = ListenUi(loaded = true)
        }
    }
}
