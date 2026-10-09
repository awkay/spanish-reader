package net.awkay.spanishreader.ui.importer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.awkay.spanishreader.ShareInbox
import net.awkay.spanishreader.ui.BackButton
import net.awkay.spanishreader.ui.appViewModel

@Suppress("DEPRECATION") // LocalClipboardManager: the suspend Clipboard API adds nothing for plain text.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(onBack: () -> Unit, onImported: (Long) -> Unit) {
    val vm = appViewModel { ImportViewModel(it) }
    val clipboard = LocalClipboardManager.current
    val pending by ShareInbox.pending.collectAsStateWithLifecycle()
    LaunchedEffect(pending) { if (pending != null) vm.takeShared() }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::loadFile) }

    Scaffold(topBar = { TopAppBar(title = { Text("New lesson") }, navigationIcon = { BackButton(onBack) }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                vm.title, { vm.title = it }, Modifier.fillMaxWidth(),
                label = { Text("Title (optional)") }, singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { clipboard.getText()?.text?.let { vm.text = it } }) {
                    Icon(Icons.Default.ContentPaste, null)
                    Text("  Paste")
                }
                OutlinedButton(onClick = { openFile.launch(arrayOf("text/*")) }) {
                    Icon(Icons.Default.FileOpen, null)
                    Text("  Open .txt")
                }
            }
            OutlinedTextField(
                vm.text, { vm.text = it }, Modifier.fillMaxWidth().weight(1f),
                label = { Text("Spanish text") },
            )
            if (vm.isYouTube) {
                Text(
                    "A YouTube video: the web app's server downloads and transcribes it (a few minutes for a long " +
                        "video), and you'll hear the real speaker. Uses the web app address and code from Settings.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (vm.isJustUrl) {
                Text(
                    "That looks like a link. Article extraction isn't supported yet: open the article, select its text and share that instead.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                )
            }
            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            vm.progress?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Join wrapped lines")
                    Text("For text copied from PDFs or emails", style = MaterialTheme.typography.bodySmall)
                }
                Switch(vm.joinWrappedLines, { vm.joinWrappedLines = it })
            }
            if (vm.isYouTube) {
                Button(onClick = { vm.importYouTube(onImported) }, enabled = !vm.saving, modifier = Modifier.fillMaxWidth()) {
                    Text("Import from YouTube")
                }
            } else {
                Button(onClick = { vm.save(onImported) }, enabled = !vm.saving, modifier = Modifier.fillMaxWidth()) {
                    Text("Create lesson")
                }
            }
        }
    }
}
