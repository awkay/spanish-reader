package net.awkay.spanishreader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.awkay.spanishreader.core.text.TextDecoding

/** Text handed to the app from outside (share sheet, "open with"), waiting for the import screen to pick it up. */
data class SharedText(val title: String, val text: String)

object ShareInbox {
    private val _pending = MutableStateFlow<SharedText?>(null)
    val pending: StateFlow<SharedText?> = _pending

    fun take(): SharedText? = _pending.value.also { _pending.value = null }

    /** Extracts shared text from [intent]; returns true if the intent carried something to import. */
    fun offer(context: Context, intent: Intent?): Boolean {
        intent ?: return false
        val shared = when (intent.action) {
            Intent.ACTION_SEND -> {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
                if (!text.isNullOrBlank()) SharedText(subject, text)
                else IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { readUri(context, it) }
            }
            Intent.ACTION_VIEW -> intent.data?.let { readUri(context, it) }
            else -> null
        } ?: return false
        _pending.value = shared
        return true
    }

    fun readUri(context: Context, uri: Uri): SharedText? = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        SharedText(displayName(context, uri)?.substringBeforeLast('.').orEmpty(), TextDecoding.decode(bytes))
    }.getOrNull()

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}
