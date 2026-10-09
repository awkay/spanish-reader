package net.awkay.spanishreader.ui.importer

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.awkay.spanishreader.ShareInbox
import net.awkay.spanishreader.SpanishReaderApp
import net.awkay.spanishreader.core.text.ImportCleaner
import net.awkay.spanishreader.gloss.PreGlossWorker
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class ImportViewModel(private val app: SpanishReaderApp) : ViewModel() {
    var title by mutableStateOf("")
    var text by mutableStateOf("")
    var joinWrappedLines by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)
        private set
    var saving by mutableStateOf(false)
        private set
    /** Progress of a YouTube import (the web server downloads and transcribes the video). */
    var progress by mutableStateOf<String?>(null)
        private set

    init {
        takeShared()
        viewModelScope.launch { joinWrappedLines = app.settings.current().joinWrappedLines }
    }

    /** Picks up text shared into the app while this screen is open or before it opened. */
    fun takeShared() {
        ShareInbox.take()?.let {
            title = it.title
            text = it.text
            error = null
        }
    }

    val isJustUrl: Boolean get() = ImportCleaner.isJustUrl(text)
    val isYouTube: Boolean get() = ImportCleaner.isYouTubeUrl(text)

    /** Has the web server turn the YouTube link in [text] into a lesson, then adds it here. */
    fun importYouTube(onSaved: (Long) -> Unit) {
        if (saving) return
        saving = true
        error = null
        progress = "Starting…"
        viewModelScope.launch {
            try {
                val result = app.webShare.importYouTube(text, app.lessons, onProgress = { progress = it })
                val settings = app.settings.current()
                if (!result.alreadyHad && settings.preGlossOnImport) {
                    PreGlossWorker.enqueue(app, result.lessonId, 0, settings.preGlossPagesAhead)
                }
                onSaved(result.lessonId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = when (e) {
                    is UnknownHostException, is ConnectException, is SocketTimeoutException ->
                        "Can't reach the web app. Check your connection and try again."
                    else -> e.message ?: e.toString()
                }
            } finally {
                saving = false
                progress = null
            }
        }
    }

    fun loadFile(uri: Uri) = viewModelScope.launch {
        val shared = withContext(Dispatchers.IO) { ShareInbox.readUri(app, uri) }
        if (shared == null) error = "Could not read that file" else {
            if (title.isBlank()) title = shared.title
            text = shared.text
            error = null
        }
    }

    fun save(onSaved: (Long) -> Unit) {
        if (saving) return
        if (text.isBlank()) {
            error = "Paste or share some Spanish text first"
            return
        }
        saving = true
        viewModelScope.launch {
            try {
                app.settings.update { it.copy(joinWrappedLines = joinWrappedLines) }
                val id = app.lessons.import(title, text, joinWrappedLines)
                val settings = app.settings.current()
                if (settings.preGlossOnImport) PreGlossWorker.enqueue(app, id, 0, settings.preGlossPagesAhead)
                onSaved(id)
            } catch (e: IllegalArgumentException) {
                error = e.message
            } finally {
                saving = false
            }
        }
    }
}
