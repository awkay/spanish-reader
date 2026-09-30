package net.awkay.spanishreader.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.awkay.spanishreader.SpanishReaderApp
import net.awkay.spanishreader.core.text.Page
import net.awkay.spanishreader.core.text.Paginator
import net.awkay.spanishreader.core.text.Token
import net.awkay.spanishreader.core.text.TokenizedText
import net.awkay.spanishreader.core.text.Tokenizer
import net.awkay.spanishreader.core.vocab.WordStatus
import net.awkay.spanishreader.gloss.LookupResult

data class ReaderContent(val title: String, val text: TokenizedText, val pages: List<Page>, val initialPage: Int)

sealed interface GlossState {
    data object Loading : GlossState
    data class Done(val result: LookupResult) : GlossState
}

data class WordSelection(val token: Token, val sentence: String, val gloss: GlossState = GlossState.Loading) {
    val form: String get() = token.normalized!!
}

class ReaderViewModel(private val app: SpanishReaderApp, private val lessonId: Long) : ViewModel() {
    private val _content = MutableStateFlow<ReaderContent?>(null)
    val content: StateFlow<ReaderContent?> = _content.asStateFlow()

    val missing = MutableStateFlow(false)

    val statuses: StateFlow<Map<String, WordStatus>> =
        app.vocab.observeStatuses().stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val fontSize: StateFlow<Int> = app.settings.settings.map { it.readerFontSize }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 20)

    private val _selection = MutableStateFlow<WordSelection?>(null)
    val selection: StateFlow<WordSelection?> = _selection.asStateFlow()
    private var lookupJob: Job? = null

    /** Highest page whose words have been promoted, so going back and forth doesn't re-run the rule needlessly. */
    private var lastPage = 0

    init {
        viewModelScope.launch {
            val lesson = app.lessons.get(lessonId) ?: run { missing.value = true; return@launch }
            val wpp = app.settings.current().wordsPerPage
            val c = withContext(Dispatchers.Default) {
                val text = Tokenizer.tokenize(lesson.text)
                val pages = Paginator.paginate(text, wpp)
                ReaderContent(lesson.title, text, pages, lesson.currentPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0)))
            }
            lastPage = c.initialPage
            _content.value = c
        }
    }

    /**
     * The reader settled on [page]. Moving forward finishes every page passed over: its still-NEW words become KNOWN.
     */
    fun onPageSettled(page: Int) {
        val c = _content.value ?: return
        val from = lastPage
        lastPage = page
        viewModelScope.launch {
            if (page > from) {
                for (p in from until page) app.vocab.finishPage(c.pages[p].wordForms)
            }
            app.lessons.setCurrentPage(lessonId, page)
        }
    }

    /** Finishing the last page; returns the number of words promoted to KNOWN. */
    suspend fun finishLesson(): Int {
        val c = _content.value ?: return 0
        val last = c.pages.lastIndex
        if (last < 0) return 0
        val promoted = app.vocab.finishPage(c.pages[last].wordForms).size
        app.lessons.setCurrentPage(lessonId, last)
        return promoted
    }

    fun onWordTapped(token: Token) {
        val c = _content.value ?: return
        val form = token.normalized ?: return
        val sentence = c.text.sentenceFor(token)
        _selection.value = WordSelection(token, sentence)
        lookupJob?.cancel()
        lookupJob = viewModelScope.launch {
            app.vocab.tap(form, sentence)
            lookup()
        }
    }

    fun retryLookup() {
        _selection.update { it?.copy(gloss = GlossState.Loading) }
        lookupJob?.cancel()
        lookupJob = viewModelScope.launch { lookup() }
    }

    private suspend fun lookup() {
        val sel = _selection.value ?: return
        val result = app.glossService.lookup(sel.token.text, sel.sentence)
        if (result is LookupResult.Found && !result.fromOtherSentence) {
            app.vocab.annotate(sel.form, result.gloss.lemma, result.gloss.meaningInContext)
        }
        _selection.update { if (it?.token == sel.token) it.copy(gloss = GlossState.Done(result)) else it }
    }

    fun setStatus(status: WordStatus) {
        val sel = _selection.value ?: return
        viewModelScope.launch { app.vocab.setStatus(sel.form, status, sel.sentence) }
    }

    fun dismissSelection() {
        lookupJob?.cancel()
        _selection.value = null
    }

    fun speak(text: String) = viewModelScope.launch {
        val s = app.settings.current()
        runCatching { app.deviceTts.speak(text, s.deviceTtsLocale, s.deviceTtsVoice) }
    }

    fun changeFontSize(delta: Int) = viewModelScope.launch {
        app.settings.update { it.copy(readerFontSize = it.readerFontSize + delta) }
    }

    suspend fun awaitContent(): ReaderContent = content.first { it != null }!!
}
