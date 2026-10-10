package net.awkay.spanishreader.ui.importer

import android.net.Uri
import androidx.core.content.FileProvider
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
import net.awkay.spanishreader.share.Downloaded
import net.awkay.spanishreader.share.PhotoPrep
import java.io.File
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
    /** Photos of Spanish text to read (a sign, a menu, pages of a book, in order); when set they replace the text. */
    var photos by mutableStateOf<List<Uri>>(emptyList())
        private set
    /** Progress of a YouTube or photo import (both are done by the web server). */
    var progress by mutableStateOf<String?>(null)
        private set

    init {
        takeShared()
        viewModelScope.launch { joinWrappedLines = app.settings.current().joinWrappedLines }
    }

    /** Picks up text shared into the app while this screen is open or before it opened. */
    fun takeShared() {
        ShareInbox.take()?.let {
            if (it.photos.isNotEmpty()) {
                addPhotos(it.photos)
                return
            }
            title = it.title
            text = it.text
            error = null
        }
    }

    fun addPhotos(uris: List<Uri>) {
        val room = MAX_PHOTOS - photos.size
        photos = photos + uris.take(room.coerceAtLeast(0))
        error = if (uris.size > room) "At most $MAX_PHOTOS photos per lesson" else null
    }

    fun removePhoto(uri: Uri) {
        photos = photos - uri
        ownFile(uri)?.delete()
    }

    /** A new file for the camera app to write into, as a content URI it can be granted. */
    fun newCameraUri(): Uri {
        val dir = File(app.cacheDir, "photos").apply { mkdirs() }
        val file = File.createTempFile("camera-", ".jpg", dir)
        return FileProvider.getUriForFile(app, app.packageName + ".photos", file)
    }

    /** The camera finished: keep its photo, or drop the empty file it was given. */
    fun cameraResult(uri: Uri, saved: Boolean) {
        if (saved) addPhotos(listOf(uri)) else ownFile(uri)?.delete()
    }

    /** Our own camera file behind [uri] (shared and picked photos belong to other apps and are never deleted). */
    private fun ownFile(uri: Uri): File? =
        if (uri.authority == app.packageName + ".photos") File(File(app.cacheDir, "photos"), uri.lastPathSegment ?: return null) else null

    /** Shrinks the photos, has the web server read their Spanish into a lesson, then adds it here. */
    fun importPhotos(onSaved: (Long) -> Unit) = runImport(onSaved) {
        progress = "Preparing ${if (photos.size == 1) "the photo" else "${photos.size} photos"}…"
        val jpegs = withContext(Dispatchers.Default) { photos.map { PhotoPrep.jpeg(app, it) } }
        app.webShare.importPhotos(jpegs, title, app.lessons, onProgress = { progress = it }).also {
            photos.forEach { ownFile(it)?.delete() }
            photos = emptyList()
        }
    }

    val isJustUrl: Boolean get() = ImportCleaner.isJustUrl(text)
    val isYouTube: Boolean get() = ImportCleaner.isYouTubeUrl(text)

    /** Downloads the YouTube link's audio, has the web server transcribe it into a lesson, then adds it here. */
    fun importYouTube(onSaved: (Long) -> Unit) = runImport(onSaved) {
        app.webShare.importYouTube(text, app.lessons, onProgress = { progress = it }, workDir = app.cacheDir)
    }

    /** Runs an import done by the web server, then pre-glosses the new lesson and opens it. */
    private fun runImport(onSaved: (Long) -> Unit, import: suspend () -> Downloaded) {
        if (saving) return
        saving = true
        error = null
        progress = "Starting…"
        viewModelScope.launch {
            try {
                val result = import()
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
                    else -> e.message?.replaceFirstChar { it.uppercase() } ?: e.toString()
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

    private companion object {
        const val MAX_PHOTOS = 6 // the server's limit
    }
}
