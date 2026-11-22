package net.awkay.spanishreader.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.awkay.spanishreader.core.gloss.GlossProvider
import net.awkay.spanishreader.data.AiProfile
import net.awkay.spanishreader.data.AppSettings
import net.awkay.spanishreader.data.TtsEngine
import net.awkay.spanishreader.ui.BackButton
import net.awkay.spanishreader.ui.appViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val vm = appViewModel { SettingsViewModel(it) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(vm::export) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::import) }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }, navigationIcon = { BackButton(onBack) }) }) { padding ->
        val s = settings ?: run {
            Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Section("AI accounts and models")
            AiProfiles(s, vm)
            Toggle("Pre-gloss in the background as you read", s.preGlossOnImport) { v -> vm.update { it.copy(preGlossOnImport = v) } }
            NumberSlider("Pages pre-glossed ahead (0 = whole lesson)", s.preGlossPagesAhead, 0..20) { v -> vm.update { it.copy(preGlossPagesAhead = v) } }
            NumberSlider("Sentences pre-glossed per word", s.preGlossSentencesPerWord, 1..10) { v -> vm.update { it.copy(preGlossSentencesPerWord = v) } }

            HorizontalDivider()
            Section("Reading")
            NumberSlider("Words per page", s.wordsPerPage, 100..500, step = 25) { v -> vm.update { it.copy(wordsPerPage = v) } }
            NumberSlider("Text size", s.readerFontSize, 14..32) { v -> vm.update { it.copy(readerFontSize = v) } }

            HorizontalDivider()
            Section("Listening")
            Dropdown("Voice engine", s.ttsEngine.label, TtsEngine.entries.map { it.label }) { i -> vm.update { it.copy(ttsEngine = TtsEngine.entries[i]) } }
            when (s.ttsEngine) {
                TtsEngine.DEVICE -> {
                    val locales = listOf("es-MX", "es-US", "es-419", "es-CO", "es-AR", "es-ES")
                    Dropdown("Accent", s.deviceTtsLocale, locales) { i -> vm.update { it.copy(deviceTtsLocale = locales[i], deviceTtsVoice = "") } }
                    val voices = listOf("") + vm.voices.filter { it.startsWith(s.deviceTtsLocale.lowercase()) || it.lowercase().contains(s.deviceTtsLocale.lowercase().replace('-', '_')) }
                    Dropdown("Voice", s.deviceTtsVoice.ifBlank { "Default for accent" }, voices.map { it.ifBlank { "Default for accent" } }) { i ->
                        vm.update { it.copy(deviceTtsVoice = voices[i]) }
                    }
                }
                TtsEngine.GOOGLE_CLOUD -> {
                    TextSetting("Google Cloud API key", s.googleTtsApiKey, secret = true) { v -> vm.update { it.copy(googleTtsApiKey = v) } }
                    TextSetting("Voice name", s.googleTtsVoice) { v -> vm.update { it.copy(googleTtsVoice = v) } }
                    Text(
                        "e.g. es-US-Chirp3-HD-Aoede. Without a key, on-device TTS is used.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::testVoice) { Text("Test voice") }
                OutlinedButton(onClick = vm::clearAudioCache) { Text("Clear audio cache") }
            }

            HorizontalDivider()
            Section("Web app (Share to web)")
            TextSetting("Web app address", s.webUrl, keyboard = KeyboardType.Uri) { v -> vm.update { it.copy(webUrl = v, webToken = "") } }
            TextSetting("Access code", s.webAccessCode, secret = true) { v -> vm.update { it.copy(webAccessCode = v, webToken = "") } }
            TextSetting("Account (one lowercase word, like tony)", s.webUser) { v ->
                vm.update { it.copy(webUser = v.lowercase().filter { c -> c in 'a'..'z' }, webToken = "") }
            }
            TextSetting("Your name (shown on shared lessons)", s.webName) { v -> vm.update { it.copy(webName = v) } }
            Text(
                "A lesson's ⋮ menu → Share to web uploads the text and its AI results. The sync button in the library " +
                    "moves your lessons and vocabulary to and from your account, when you ask.",
                style = MaterialTheme.typography.bodySmall,
            )

            HorizontalDivider()
            Section("Backup")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    exportLauncher.launch("spanish-reader-${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.json")
                }) { Text("Export") }
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) }) { Text("Restore") }
            }
            Text("Restore merges lessons and vocabulary into what's here; nothing is deleted.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = vm::clearGlossCache) { Text("Clear cached meanings") }
        }
    }

    vm.message?.let { m ->
        AlertDialog(
            onDismissRequest = { vm.message = null },
            text = { Text(m) },
            confirmButton = { TextButton(onClick = { vm.message = null }) { Text("OK") } },
        )
    }
}

/**
 * Saved AI accounts and models: pick the one in use with its radio button; edit, copy (same key, another model) or
 * delete each; choose which one "Improve answer" uses and which one is tried when the selected one fails.
 */
@Composable
private fun AiProfiles(s: AppSettings, vm: SettingsViewModel) {
    var editing by remember { mutableStateOf<AiProfile?>(null) }
    var deleting by remember { mutableStateOf<AiProfile?>(null) }
    Text(
        "The selected one is used for word meanings, translations, practice and conversation. Keep as many as you like " +
            "and switch at any time; copy one to try another model with the same key.",
        style = MaterialTheme.typography.bodySmall,
    )
    if (s.aiProfiles.isEmpty()) Text("None yet. Add one to get word meanings.", style = MaterialTheme.typography.bodyMedium)
    for (p in s.aiProfiles) {
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(p.id == s.activeProfileId, onClick = { vm.activate(p.id) }, Modifier.semantics { contentDescription = "Use ${p.label}" })
                Column(Modifier.weight(1f).clickable { vm.activate(p.id) }) {
                    Text(p.label, style = MaterialTheme.typography.bodyLarge)
                    // An unnamed profile's label already is the service and model.
                    val detail = listOfNotNull(
                        p.glossProvider.label.takeIf { p.name.isNotBlank() },
                        p.config.effectiveModel.takeIf { it.isNotEmpty() && p.name.isNotBlank() },
                        if (p.config.isComplete) null else "not complete",
                    ).joinToString(" · ")
                    if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton({ editing = p }) { Icon(Icons.Default.Edit, "Edit ${p.label}") }
                IconButton({ editing = p.copy(id = AiProfile.newId(), name = p.label + " (copy)") }) { Icon(Icons.Default.ContentCopy, "Copy ${p.label}") }
                IconButton({ deleting = p }) { Icon(Icons.Default.Delete, "Delete ${p.label}") }
            }
        }
    }
    val e = editing
    if (e != null) {
        ProfileEditor(e, vm, onDone = { editing = null })
    } else {
        OutlinedButton({ editing = AiProfile(AiProfile.newId(), provider = GlossProvider.OPENROUTER.name, baseUrl = GlossProvider.OPENROUTER.defaultBaseUrl.orEmpty()) }) {
            Icon(Icons.Default.Add, null)
            Text(" Add account / model")
        }
    }
    if (s.aiProfiles.size > 1) {
        val others = s.aiProfiles
        Dropdown("“Improve answer” and card reviews use", s.profile(s.improveProfileId)?.label ?: "The selected one", listOf("The selected one") + others.map { it.label }) { i ->
            vm.update { it.copy(improveProfileId = if (i == 0) "" else others[i - 1].id) }
        }
        Dropdown("If the selected one fails, try", s.profile(s.fallbackProfileId)?.label ?: "Nothing", listOf("Nothing") + others.map { it.label }) { i ->
            vm.update { it.copy(fallbackProfileId = if (i == 0) "" else others[i - 1].id) }
        }
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            text = { Text("Delete “${p.label}”? Its API key is deleted with it.") },
            confirmButton = { TextButton({ vm.deleteProfile(p.id); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton({ deleting = null }) { Text("Cancel") } },
        )
    }
}

/** Inline editor (the page scrolls and the keyboard pushes it up, which a dialog handles poorly). */
@Composable
private fun ProfileEditor(start: AiProfile, vm: SettingsViewModel, onDone: () -> Unit) {
    var p by remember(start.id) { mutableStateOf(start) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("AI account / model", style = MaterialTheme.typography.titleSmall)
            Dropdown("Service", p.glossProvider.label, GlossProvider.entries.map { it.label }) { i ->
                val old = p.glossProvider
                val new = GlossProvider.entries[i]
                // A URL or model still at the old service's default follows the new service's default.
                fun follow(cur: String, oldDefault: String?, newDefault: String?) =
                    if (cur.isBlank() || cur == oldDefault) newDefault.orEmpty() else cur
                p = p.copy(
                    provider = new.name,
                    baseUrl = follow(p.baseUrl, old.defaultBaseUrl, new.defaultBaseUrl),
                    model = follow(p.model, old.defaultModel, new.defaultModel),
                )
            }
            Field("Base URL", p.baseUrl, placeholder = p.glossProvider.defaultBaseUrl ?: "http://192.168.1.x:11434/v1", keyboard = KeyboardType.Uri) { p = p.copy(baseUrl = it) }
            Field("Model", p.model, placeholder = p.glossProvider.defaultModel ?: modelHint(p.glossProvider)) { p = p.copy(model = it) }
            Field("API key${if (p.glossProvider.requiresApiKey) "" else " (optional)"}", p.apiKey, secret = true) { p = p.copy(apiKey = it) }
            Field("Name (optional)", p.name, placeholder = p.copy(name = "").label) { p = p.copy(name = it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton({ vm.test(p.config) }, enabled = !vm.testing) { Text(if (vm.testing) "Testing…" else "Test") }
                TextButton(onDone) { Text("Cancel") }
                Button({ vm.saveProfile(p); onDone() }) { Text("Save") }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, placeholder: String? = null, secret: Boolean = false, keyboard: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) =
    OutlinedTextField(
        value, onChange, Modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, maxLines = 1) } },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard),
    )

private fun modelHint(p: GlossProvider) = when (p) {
    GlossProvider.OLLAMA_CLOUD -> "e.g. a Kimi model from ollama.com/search?c=cloud"
    GlossProvider.ZAI, GlossProvider.ZAI_CODING, GlossProvider.ZAI_RESPONSES -> "e.g. glm-5.3-flash or glm-4.6"
    GlossProvider.OPENROUTER -> "e.g. deepseek/deepseek-v4.1-flash"
    else -> "model name"
}

@Composable
private fun Section(title: String) = Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

@Composable
private fun TextSetting(
    label: String,
    value: String,
    /** Resets the field's local text when it changes (e.g. the provider being edited). */
    key: Any? = null,
    placeholder: String? = null,
    secret: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    // Local state so typing isn't disturbed by the DataStore round-trip.
    var text by remember(label, key) { mutableStateOf(value) }
    OutlinedTextField(
        text,
        { text = it; onChange(it) },
        Modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard),
    )
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

@Composable
private fun NumberSlider(label: String, value: Int, range: IntRange, step: Int = 1, onChange: (Int) -> Unit) {
    var v by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column {
        Text("$label: ${v.toInt()}")
        Slider(
            value = v,
            onValueChange = { v = (Math.round(it / step) * step).toFloat() },
            onValueChangeFinished = { onChange(v.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = ((range.last - range.first) / step - 1).coerceAtLeast(0),
        )
    }
}

@Composable
private fun Dropdown(label: String, current: String, options: List<String>, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text(current) }
            DropdownMenu(open, onDismissRequest = { open = false }) {
                options.forEachIndexed { i, o -> DropdownMenuItem(text = { Text(o) }, onClick = { open = false; onSelect(i) }) }
            }
        }
    }
}
