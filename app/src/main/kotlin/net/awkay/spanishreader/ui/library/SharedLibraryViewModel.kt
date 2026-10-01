package net.awkay.spanishreader.ui.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.awkay.spanishreader.SpanishReaderApp
import net.awkay.spanishreader.gloss.PreGlossWorker
import net.awkay.spanishreader.share.SharedLessonSummary
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** "Get from web": the household web app's shared library, and copying a lesson from it into this phone. */
class SharedLibraryViewModel(private val app: SpanishReaderApp) : ViewModel() {
    var lessons by mutableStateOf<List<SharedLessonSummary>?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** True when the error is missing web app settings, so the screen offers a way to Settings. */
    var needsSettings by mutableStateOf(false)
        private set

    /** Server id of the lesson being added, if any. */
    var adding by mutableStateOf<String?>(null)
        private set

    /** One-shot message for a toast. */
    var message by mutableStateOf<String?>(null)

    /** Titles of local lessons, to mark shared ones that look already added. */
    val localTitles: StateFlow<Set<String>> = app.lessons.observeAll().map { all -> all.map { it.title }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    init {
        refresh()
    }

    fun refresh() {
        if (loading) return
        loading = true
        viewModelScope.launch {
            try {
                lessons = app.webShare.listShared()
                error = null
                needsSettings = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            } finally {
                loading = false
            }
        }
    }

    fun add(lesson: SharedLessonSummary, onAdded: (Long) -> Unit) {
        if (adding != null) return
        adding = lesson.id
        viewModelScope.launch {
            try {
                val result = app.webShare.download(lesson.id, app.lessons)
                val settings = app.settings.current()
                // Pre-gloss after the shared AI results are stored, so it only pays for what the server lacked.
                if (!result.alreadyHad && settings.preGlossOnImport) {
                    PreGlossWorker.enqueue(app, result.lessonId, 0, settings.preGlossPagesAhead)
                }
                message = when {
                    result.alreadyHad && result.aiResults == null -> "“${lesson.title}” is already in your library."
                    result.alreadyHad -> "“${lesson.title}” is already in your library (${result.aiResults} new AI results)."
                    result.aiResults == null -> "Added “${lesson.title}” (couldn't fetch its AI results)."
                    else -> "Added “${lesson.title}” with ${result.aiResults} AI results."
                }
                onAdded(result.lessonId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            } finally {
                adding = null
            }
        }
    }

    private fun fail(e: Exception) {
        needsSettings = e is IllegalArgumentException && e.message.orEmpty().contains("Settings")
        error = when (e) {
            is UnknownHostException, is ConnectException, is SocketTimeoutException ->
                "Can't reach the web app. Check your connection and try again."
            is IOException -> e.message ?: "Network error"
            else -> e.message ?: e.toString()
        }
    }
}
