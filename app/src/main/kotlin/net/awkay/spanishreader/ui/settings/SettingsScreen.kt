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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.awkay.spanishreader.core.gloss.GlossProvider
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
            Section("Word meanings (LLM)")
            Dropdown("Provider", s.provider.label, GlossProvider.entries.map { it.label }) { i ->
                vm.update { it.copy(provider = GlossProvider.entries[i]) }
            }
            ProviderFields(s, s.provider, vm)
            OutlinedButton(onClick = { vm.test(s.primaryGlosser) }, enabled = !vm.testing) { Text(if (vm.testing) "Testing…" else "Test connection") }
            if (s.provider != GlossProvider.ANTHROPIC) {
                Toggle("Fall back to Claude when this provider fails", s.fallbackToAnthropic) { v -> vm.update { it.copy(fallbackToAnthropic = v) } }
                if (s.fallbackToAnthropic) ProviderFields(s, GlossProvider.ANTHROPIC, vm)
            }
            TextSetting("Model for “Improve answer” (optional)", s.improveModel, placeholder = "e.g. glm-5.3 — blank: Claude if set up, else same model") { v ->
                vm.update { it.copy(improveModel = v) }
            }
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

@Composable
private fun ProviderFields(s: AppSettings, provider: GlossProvider, vm: SettingsViewModel) {
    val c = s.config(provider)
    if (provider != s.provider) Text(provider.label, style = MaterialTheme.typography.labelLarge)
    TextSetting("Base URL", c.baseUrl, placeholder = provider.defaultBaseUrl ?: "http://192.168.1.x:11434/v1", keyboard = KeyboardType.Uri) {
        vm.updateProvider(c.copy(baseUrl = it))
    }
    TextSetting("Model", c.model, placeholder = provider.defaultModel ?: modelHint(provider)) { vm.updateProvider(c.copy(model = it)) }
    TextSetting("API key${if (provider.requiresApiKey) "" else " (optional)"}", c.apiKey, secret = true) { vm.updateProvider(c.copy(apiKey = it)) }
}

private fun modelHint(p: GlossProvider) = when (p) {
    GlossProvider.OLLAMA_CLOUD -> "e.g. a Kimi model from ollama.com/search?c=cloud"
    GlossProvider.ZAI, GlossProvider.ZAI_CODING, GlossProvider.ZAI_RESPONSES -> "e.g. glm-5.3-flash or glm-4.6"
    else -> "model name"
}

@Composable
private fun Section(title: String) = Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

@Composable
private fun TextSetting(
    label: String,
    value: String,
    placeholder: String? = null,
    secret: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    // Local state so typing isn't disturbed by the DataStore round-trip.
    var text by remember(label) { mutableStateOf(value) }
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
