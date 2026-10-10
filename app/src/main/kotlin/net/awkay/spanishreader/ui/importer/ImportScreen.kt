package net.awkay.spanishreader.ui.importer

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.awkay.spanishreader.ShareInbox
import net.awkay.spanishreader.share.PhotoPrep
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
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(6)) { uris ->
        if (uris.isNotEmpty()) vm.addPhotos(uris)
    }
    // The file the camera app is writing into; saved so it survives the activity being recreated meanwhile.
    var cameraTarget by rememberSaveable { mutableStateOf<String?>(null) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        cameraTarget?.let { vm.cameraResult(it.toUri(), saved) }
        cameraTarget = null
    }
    val hasPhotos = vm.photos.isNotEmpty()

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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val uri = vm.newCameraUri()
                    cameraTarget = uri.toString()
                    takePhoto.launch(uri)
                }, enabled = !vm.saving) {
                    Icon(Icons.Default.PhotoCamera, null)
                    Text("  Camera")
                }
                OutlinedButton(onClick = {
                    pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }, enabled = !vm.saving) {
                    Icon(Icons.Default.PhotoLibrary, null)
                    Text("  Photos")
                }
            }
            if (hasPhotos) {
                Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (uri in vm.photos) PhotoThumb(uri, onRemove = { vm.removePhoto(uri) }, enabled = !vm.saving)
                    }
                    Text(
                        "The web app's server reads the Spanish in " +
                            (if (vm.photos.size == 1) "this photo" else "these ${vm.photos.size} photos, in this order,") +
                            " and makes a shared lesson; the photos aren't kept. Leave the title blank to use the " +
                            "heading. Add more photos for more pages of the same text.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                OutlinedTextField(
                    vm.text, { vm.text = it }, Modifier.fillMaxWidth().weight(1f),
                    label = { Text("Spanish text") },
                )
            }
            if (!hasPhotos && vm.isYouTube) {
                Text(
                    "A YouTube video: this phone downloads its audio (~0.4 MB per minute), the web app's server " +
                        "transcribes it (a few minutes for a long video), and you'll hear the real speaker. Uses the " +
                        "web app address and code from Settings.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (!hasPhotos && vm.isJustUrl) {
                Text(
                    "That looks like a link. Article extraction isn't supported yet: open the article, select its text and share that instead.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                )
            }
            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            vm.progress?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (!hasPhotos) Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Join wrapped lines")
                    Text("For text copied from PDFs or emails", style = MaterialTheme.typography.bodySmall)
                }
                Switch(vm.joinWrappedLines, { vm.joinWrappedLines = it })
            }
            if (hasPhotos) {
                Button(onClick = { vm.importPhotos(onImported) }, enabled = !vm.saving, modifier = Modifier.fillMaxWidth()) {
                    Text(if (vm.photos.size == 1) "Read the photo" else "Read the ${vm.photos.size} photos")
                }
            } else if (vm.isYouTube) {
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

/** A preview of a chosen photo, with a button to drop it. */
@Composable
private fun PhotoThumb(uri: Uri, onRemove: () -> Unit, enabled: Boolean) {
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) { PhotoPrep.thumbnail(context, uri, 360) }
    }
    Box(Modifier.size(120.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        bitmap?.let {
            Image(it.asImageBitmap(), contentDescription = "Photo", contentScale = ContentScale.Crop, modifier = Modifier.size(120.dp))
        }
        IconButton(
            onClick = onRemove, enabled = enabled,
            modifier = Modifier.align(Alignment.TopEnd).size(32.dp)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), RoundedCornerShape(16.dp)),
        ) { Icon(Icons.Default.Close, "Remove photo") }
    }
}
