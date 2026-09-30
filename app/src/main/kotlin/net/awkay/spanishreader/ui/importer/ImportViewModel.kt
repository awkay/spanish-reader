package net.awkay.spanishreader.ui.importer

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.awkay.spanishreader.ShareInbox
import net.awkay.spanishreader.SpanishReaderApp
import net.awkay.spanishreader.core.text.ImportCleaner
import net.awkay.spanishreader.gloss.PreGlossWorker

class ImportViewModel(private val app: SpanishReaderApp) : ViewModel() {
    var title by mutableStateOf("")
    var text by mutableStateOf("")
    var joinWrappedLines by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)
        private set
    var saving by mutableStateOf(false)
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
                if (app.settings.current().preGlossOnImport) PreGlossWorker.enqueue(app, id)
                onSaved(id)
            } catch (e: IllegalArgumentException) {
                error = e.message
            } finally {
                saving = false
            }
        }
    }
}
