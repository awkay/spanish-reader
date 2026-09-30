package net.awkay.spanishreader.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.awkay.spanishreader.SpanishReaderApp
import net.awkay.spanishreader.audio.AudioState
import net.awkay.spanishreader.core.text.ListenScript
import net.awkay.spanishreader.core.text.ListenSentence
import net.awkay.spanishreader.core.text.Page
import net.awkay.spanishreader.core.text.Paginator
import net.awkay.spanishreader.core.text.PhraseLocator
import net.awkay.spanishreader.core.text.Token
import net.awkay.spanishreader.core.text.TokenizedText
import net.awkay.spanishreader.core.text.Tokenizer
import net.awkay.spanishreader.core.vocab.WordStatus
import net.awkay.spanishreader.gloss.LookupResult
import net.awkay.spanishreader.gloss.PreGlossWorker

data class ReaderContent(
    val title: String,
    val text: TokenizedText,
    val pages: List<Page>,
    val initialPage: Int,
    /** Spoken sentences, for follow-along audio. */
    val script: List<ListenSentence>,
) {
    /** Index into [script] of the first sentence on [page] (or the next page that has one). */
    fun firstSentenceOn(page: Int): Int = script.indexOfFirst { it.pageIndex >= page }.coerceAtLeast(0)
}

sealed interface GlossState {
    data object Loading : GlossState
    data class Done(val result: LookupResult) : GlossState
}

data class WordSelection(
    val token: Token,
    val sentence: String,
    val gloss: GlossState = GlossState.Loading,
    /** An "Improve answer" request is in flight; the current gloss stays visible meanwhile. */
    val improving: Boolean = false,
    val improveError: String? = null,
) {
    val form: String get() = token.normalized!!
}

/** An expression located in the text: which word tokens it covers, for underlining and the word sheet. */
data class PhraseSpan(val phrase: String, val meaning: String, val tokenIndices: Set<Int>)

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

    /** Pages up to (exclusive) this one have already been queued for pre-glossing in this session. */
    private var preGlossedUpTo = -1

    /** The shared audio engine's state, when it is playing this lesson. */
    val audio: StateFlow<AudioState> = app.audio.state.map { if (it.isFor(lessonId)) it else AudioState() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AudioState())

    /** Whether the pager should follow the sentence being spoken. Swiping away while playing turns it off. */
    val following = MutableStateFlow(false)

    /** Known expressions in this lesson, by the index of every token they cover. Fills in as scans find them. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val phraseSpans: StateFlow<Map<Int, List<PhraseSpan>>> = _content.filterNotNull().flatMapLatest { c ->
        val sentences = c.text.sentenceRanges.indices.map { it to c.text.sentenceText(it) }
        app.phrases.observe(sentences.map { it.second }).map { byText ->
            val out = HashMap<Int, MutableList<PhraseSpan>>()
            for ((index, text) in sentences) {
                val found = byText[text] ?: continue
                val tokens = c.text.sentenceTokens(index)
                for (p in found) {
                    val hit = PhraseLocator.locate(tokens, p.phrase) ?: continue
                    val span = PhraseSpan(p.phrase, p.meaning, hit.map { it.index }.toSet())
                    span.tokenIndices.forEach { out.getOrPut(it) { ArrayList() }.add(span) }
                }
            }
            out as Map<Int, List<PhraseSpan>>
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    init {
        viewModelScope.launch {
            val lesson = app.lessons.get(lessonId) ?: run { missing.value = true; return@launch }
            val wpp = app.settings.current().wordsPerPage
            val c = withContext(Dispatchers.Default) {
                val text = Tokenizer.tokenize(lesson.text)
                val pages = Paginator.paginate(text, wpp)
                ReaderContent(
                    lesson.title, text, pages, lesson.currentPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0)),
                    ListenScript.build(text, pages),
                )
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
            preGlossAround(page)
        }
    }

    /** Keeps [PreGlossWorker] a few pages ahead of the reader instead of glossing a whole chapter at import. */
    private suspend fun preGlossAround(page: Int) {
        val s = app.settings.current()
        if (!s.preGlossOnImport || s.preGlossPagesAhead <= 0) return
        val end = page + s.preGlossPagesAhead
        if (end <= preGlossedUpTo) return
        val from = maxOf(page, preGlossedUpTo)
        preGlossedUpTo = end
        PreGlossWorker.enqueue(app, lessonId, from, end - from)
    }

    private fun loadAudio(c: ReaderContent, page: Int) = app.audio.load(lessonId, c.title, c.script, c.firstSentenceOn(page))

    /** Play/pause from the reader. Starts at the visible page unless paused somewhere on it already. */
    fun playPause(visiblePage: Int) {
        val c = _content.value ?: return
        loadAudio(c, visiblePage)
        val a = app.audio.state.value
        when {
            a.isPlaying -> app.audio.pause()
            a.started && a.currentSentence?.pageIndex == visiblePage -> {
                following.value = true
                app.audio.play()
            }
            else -> {
                following.value = true
                app.audio.playFrom(c.firstSentenceOn(visiblePage))
            }
        }
    }

    fun nextSentence() {
        following.value = true
        app.audio.next()
    }

    fun previousSentence() {
        following.value = true
        app.audio.previous()
    }

    fun toggleLoop() = app.audio.toggleLoop()

    fun follow() {
        following.value = true
    }

    fun stopFollowing() {
        following.value = false
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
        // Looking a word up while listening: pause so the audio doesn't run away from you.
        if (audio.value.isPlaying) app.audio.pause()
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

    /** Asks again with the stronger "improve" model, showing it the current answer; replaces the cached gloss. */
    fun improve() {
        val sel = _selection.value ?: return
        if (sel.improving) return
        val previous = ((sel.gloss as? GlossState.Done)?.result as? LookupResult.Found)?.gloss
        _selection.update { it?.copy(improving = true, improveError = null) }
        lookupJob?.cancel()
        lookupJob = viewModelScope.launch {
            val result = app.glossService.improve(sel.token.text, sel.sentence, previous)
            _selection.update { cur ->
                if (cur?.token != sel.token) cur
                else when (result) {
                    is LookupResult.Found -> cur.copy(gloss = GlossState.Done(result), improving = false)
                    is LookupResult.Failed -> cur.copy(improving = false, improveError = result.message)
                }
            }
        }
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

}
