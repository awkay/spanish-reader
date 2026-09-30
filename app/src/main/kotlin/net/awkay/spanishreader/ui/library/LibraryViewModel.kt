package net.awkay.spanishreader.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.awkay.spanishreader.SpanishReaderApp
import net.awkay.spanishreader.core.text.Paginator
import net.awkay.spanishreader.core.text.Tokenizer
import net.awkay.spanishreader.core.vocab.LessonStats
import net.awkay.spanishreader.data.LessonEntity
import net.awkay.spanishreader.gloss.PreGlossWorker

sealed interface PreGlossState {
    data class Running(val done: Int, val total: Int) : PreGlossState
    data object Waiting : PreGlossState
    data class Failed(val error: String) : PreGlossState
}

data class LessonRow(
    val lesson: LessonEntity,
    val stats: LessonStats,
    val pageCount: Int,
    val preGloss: PreGlossState?,
)

class LibraryViewModel(private val app: SpanishReaderApp) : ViewModel() {
    private class Analysis(val text: String, val wordsPerPage: Int, val forms: List<String>, val pageCount: Int)

    private val analyses = HashMap<Long, Analysis>()

    private fun analyze(lesson: LessonEntity, wordsPerPage: Int): Analysis {
        analyses[lesson.id]?.let { if (it.text == lesson.text && it.wordsPerPage == wordsPerPage) return it }
        val tokens = Tokenizer.tokenize(lesson.text)
        return Analysis(lesson.text, wordsPerPage, tokens.tokens.mapNotNull { it.normalized }, Paginator.paginate(tokens, wordsPerPage).size)
            .also { analyses[lesson.id] = it }
    }

    private val preGlossStates = WorkManager.getInstance(app).getWorkInfosByTagFlow(PreGlossWorker.TAG).map { infos ->
        infos.filter { it.state != WorkInfo.State.CANCELLED }
            .groupBy { info -> info.tags.firstNotNullOfOrNull { it.removePrefix("pregloss-lesson-").takeIf { t -> t != it }?.toLongOrNull() } }
            .mapNotNull { (id, list) ->
                val info = list.firstOrNull { it.state == WorkInfo.State.RUNNING }
                    ?: list.firstOrNull { !it.state.isFinished }
                    ?: list.firstOrNull { it.state == WorkInfo.State.FAILED }?.takeIf { list.none { i -> i.state == WorkInfo.State.SUCCEEDED } }
                val state = when (info?.state) {
                    WorkInfo.State.RUNNING -> PreGlossState.Running(
                        info.progress.getInt(PreGlossWorker.KEY_DONE, 0), info.progress.getInt(PreGlossWorker.KEY_TOTAL, 0),
                    )
                    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> PreGlossState.Waiting
                    WorkInfo.State.FAILED -> PreGlossState.Failed(info.outputData.getString(PreGlossWorker.KEY_ERROR) ?: "failed")
                    else -> null
                }
                if (id == null || state == null) null else id to state
            }.toMap()
    }

    val rows: StateFlow<List<LessonRow>?> = combine(
        app.lessons.observeAll(), app.vocab.observeStatuses(), preGlossStates, app.settings.settings.map { it.wordsPerPage },
    ) { lessons, statuses, gloss, wpp ->
        lessons.map { l ->
            val a = analyze(l, wpp)
            LessonRow(l, LessonStats.compute(a.forms, statuses), a.pageCount, gloss[l.id])
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun delete(id: Long) = viewModelScope.launch {
        PreGlossWorker.cancel(app, id)
        app.lessons.delete(id)
    }

    fun rename(id: Long, title: String) = viewModelScope.launch { if (title.isNotBlank()) app.lessons.rename(id, title) }

    fun preGloss(id: Long) = PreGlossWorker.enqueue(app, id)
}
