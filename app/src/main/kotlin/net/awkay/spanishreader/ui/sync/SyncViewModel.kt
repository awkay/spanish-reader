package net.awkay.spanishreader.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.awkay.spanishreader.SpanishReaderApp
import net.awkay.spanishreader.share.SyncOutcome
import net.awkay.spanishreader.share.SyncStatus

data class SyncUi(
    val user: String = "",
    val status: SyncStatus? = null,
    val busy: String? = null,
    val message: String? = null,
    val error: String? = null,
    /** Waiting for the reader to confirm a sync that would overwrite newer work. */
    val confirm: Pair<String, SyncOutcome>? = null,
)

class SyncViewModel(private val app: SpanishReaderApp) : ViewModel() {
    private val _ui = MutableStateFlow(SyncUi())
    val ui: StateFlow<SyncUi> = _ui

    init {
        refresh()
    }

    fun refresh() = viewModelScope.launch {
        val user = app.settings.current().webUser
        _ui.update { it.copy(user = user) }
        if (user.isNotBlank()) {
            val s = runCatching { app.sync.status() }
            _ui.update { u -> u.copy(status = s.getOrNull(), error = s.exceptionOrNull()?.message ?: u.error) }
        }
    }

    fun up(force: Boolean = false) = run("up", force)

    fun down(force: Boolean = false) = run("down", force)

    fun dismissConfirm() = _ui.update { it.copy(confirm = null) }

    private fun run(direction: String, force: Boolean) {
        if (_ui.value.busy != null) return
        _ui.update { it.copy(busy = if (direction == "up") "Syncing up…" else "Syncing down…", message = null, error = null, confirm = null) }
        viewModelScope.launch {
            try {
                val progress: (String) -> Unit = { p -> _ui.update { it.copy(busy = p) } }
                when (val r = if (direction == "up") app.sync.up(force, progress) else app.sync.down(force, progress)) {
                    is SyncOutcome.Done -> _ui.update { it.copy(message = r.message) }
                    else -> _ui.update { it.copy(confirm = direction to r) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.message ?: e.toString()) }
            } finally {
                _ui.update { it.copy(busy = null) }
                refresh()
            }
        }
    }
}
