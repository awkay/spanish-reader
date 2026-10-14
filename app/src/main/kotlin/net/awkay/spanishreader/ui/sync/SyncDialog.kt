package net.awkay.spanishreader.ui.sync

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.awkay.spanishreader.core.backup.SyncMeta
import net.awkay.spanishreader.share.SyncOutcome
import net.awkay.spanishreader.ui.appViewModel

private fun ago(ms: Long): String =
    DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

private fun SyncMeta.describe() = "revision $revision, from ${device.ifBlank { "a device" }}, ${ago(updatedAt)}"

/** Manual sync with the web app account: up replaces the server's copy, down replaces this device's. */
@Composable
fun SyncDialog(onDismiss: () -> Unit, onSettings: () -> Unit) {
    val vm = appViewModel { SyncViewModel(it) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }

    ui.confirm?.let { (direction, outcome) ->
        val text = when (outcome) {
            is SyncOutcome.ServerNewer ->
                "The server has a newer copy (${outcome.current.describe()}) that this device hasn't synced down.\n\nOverwrite it with this device's lessons and words?"
            is SyncOutcome.LocalChanges ->
                "This device has changes${outcome.state?.let { " since " + ago(it.atMillis) } ?: ""} that aren't on the server.\n\nReplace them with the server's copy?"
            is SyncOutcome.Done -> ""
        }
        AlertDialog(
            onDismissRequest = vm::dismissConfirm,
            title = { Text(if (direction == "up") "Overwrite the server?" else "Replace this device's data?") },
            text = { Text(text) },
            confirmButton = {
                TextButton({ if (direction == "up") vm.up(force = true) else vm.down(force = true) }) {
                    Text(if (direction == "up") "Overwrite" else "Replace")
                }
            },
            dismissButton = { TextButton(vm::dismissConfirm) { Text("Cancel") } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = { if (ui.busy == null) onDismiss() },
        title = { Text("Sync") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ui.user.isBlank()) {
                    Text("Set your account name (one lowercase word, like tony) in Settings → Web app, then sync from here.")
                } else {
                    Text(
                        "Account: ${ui.user}. Sync moves your lessons (with the page you're on) and your words with their statuses. " +
                            "Sync up replaces the server's copy with this device's; sync down replaces this device's with the server's.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    val s = ui.status
                    Text(
                        "Server: " + when {
                            s == null -> "…"
                            s.server == null -> s.serverError ?: "unavailable"
                            s.server.revision == 0 -> "nothing synced up yet"
                            else -> s.server.describe()
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "This device: " + when {
                            s == null -> "…"
                            s.state == null -> "never synced"
                            else -> "synced ${s.state.direction} revision ${s.state.revision}, ${ago(s.state.atMillis)}"
                        } + if (s?.localChanges == true) " · changed since" else "",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ vm.up() }, enabled = ui.busy == null) { Text("Sync up") }
                        Button({ vm.down() }, enabled = ui.busy == null) { Text("Sync down") }
                    }
                }
                ui.busy?.let {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                ui.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onDismiss, enabled = ui.busy == null) { Text("Close") } },
        dismissButton = if (ui.user.isBlank()) ({ TextButton(onSettings) { Text("Settings") } }) else null,
    )
}
