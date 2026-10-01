package net.awkay.spanishreader.ui.settings

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.awkay.spanishreader.SpanishReaderApp
import net.awkay.spanishreader.core.gloss.GlossProvider
import net.awkay.spanishreader.core.gloss.GlossRequest
import net.awkay.spanishreader.core.gloss.GlossResult
import net.awkay.spanishreader.core.gloss.GlosserFactory
import net.awkay.spanishreader.core.gloss.GlosserConfig
import net.awkay.spanishreader.data.AppSettings
import net.awkay.spanishreader.gloss.GlossService

class SettingsViewModel(private val app: SpanishReaderApp) : ViewModel() {
    val settings: StateFlow<AppSettings?> = app.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    var message by mutableStateOf<String?>(null)
    var testing by mutableStateOf(false)
        private set
    var voices by mutableStateOf<List<String>>(emptyList())
        private set

    init {
        viewModelScope.launch { voices = runCatching { app.deviceTts.spanishVoices().map { it.name } }.getOrDefault(emptyList()) }
    }

    fun update(transform: (AppSettings) -> AppSettings) = viewModelScope.launch { app.settings.update(transform) }

    fun updateProvider(config: GlosserConfig) = viewModelScope.launch { app.settings.updateProvider(config) }

    fun selectProvider(provider: GlossProvider) = viewModelScope.launch { app.settings.selectProvider(provider) }

    /** Glosses one word with [config] alone (no cache, no fallback) and reports the result. */
    fun test(config: GlosserConfig) {
        testing = true
        viewModelScope.launch {
            message = try {
                val glosser = GlosserFactory.create(config, httpClient = GlossService.sharedHttp)
                when (val r = glosser.gloss(listOf(GlossRequest("banco", "Me senté en un banco del parque."))).single()) {
                    is GlossResult.Success -> "Works: banco → “${r.gloss.meaningInContext}”"
                    is GlossResult.Failure -> "Failed: ${r.error}"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Failed: ${e.message}"
            } finally {
                testing = false
            }
        }
    }

    fun testVoice() = viewModelScope.launch {
        val s = app.settings.current()
        message = try {
            withContext(Dispatchers.IO) { app.audioCache.ensure(app.synthesizer(s), "Hola, ¿cómo estás? Hoy vamos a leer en español.") }
            "Voice works (audio generated)."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Voice failed: ${e.message}"
        }
        runCatching { app.deviceTts.speak("Hola, ¿cómo estás?", s.deviceTtsLocale, s.deviceTtsVoice) }
    }

    fun clearAudioCache() = viewModelScope.launch {
        withContext(Dispatchers.IO) { app.audioCache.clear() }
        message = "Audio cache cleared."
    }

    fun clearGlossCache() = viewModelScope.launch {
        app.database.glosses().clear()
        app.phrases.clear()
        message = "Gloss cache cleared."
    }

    fun export(uri: Uri) = viewModelScope.launch {
        message = try {
            val json = app.backup.exportJson()
            withContext(Dispatchers.IO) {
                app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray()) } ?: error("Cannot write file")
            }
            "Backup saved."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Export failed: ${e.message}"
        }
    }

    fun import(uri: Uri) = viewModelScope.launch {
        message = try {
            val json = withContext(Dispatchers.IO) {
                app.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: error("Cannot read file")
            }
            app.backup.importJson(json).toString()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Restore failed: ${e.message}"
        }
    }
}
