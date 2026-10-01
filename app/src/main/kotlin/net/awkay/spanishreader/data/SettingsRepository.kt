package net.awkay.spanishreader.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import net.awkay.spanishreader.core.gloss.GlossProvider
import net.awkay.spanishreader.core.gloss.GlosserConfig
import net.awkay.spanishreader.core.text.Paginator

enum class TtsEngine(val label: String) { DEVICE("On-device (Android TTS)"), GOOGLE_CLOUD("Google Cloud TTS") }

data class AppSettings(
    val provider: GlossProvider = GlossProvider.OLLAMA_CLOUD,
    /** Stored per provider so switching back and forth keeps keys and model names. */
    val providerConfigs: Map<GlossProvider, GlosserConfig> = emptyMap(),
    val fallbackToAnthropic: Boolean = false,
    /** Model (same provider) for "Improve answer"; blank = Claude if an Anthropic key is set, else the usual model. */
    val improveModel: String = "",
    val preGlossOnImport: Boolean = true,
    val preGlossSentencesPerWord: Int = 3,
    /** Pages glossed ahead of the one being read; 0 = the whole lesson at import. */
    val preGlossPagesAhead: Int = 3,
    val wordsPerPage: Int = Paginator.DEFAULT_WORDS_PER_PAGE,
    val readerFontSize: Int = 20,
    val joinWrappedLines: Boolean = true,
    val ttsEngine: TtsEngine = TtsEngine.DEVICE,
    val deviceTtsLocale: String = "es-MX",
    /** Blank = engine default for the locale. */
    val deviceTtsVoice: String = "",
    val googleTtsApiKey: String = "",
    val googleTtsVoice: String = "es-US-Chirp3-HD-Aoede",
    val playbackSpeed: Float = 1.0f,
    /** Household web app, for "Share to web". */
    val webUrl: String = "https://spanish-reader.fulcrologic.com",
    val webAccessCode: String = "",
    val webName: String = "",
    /** Session token from the web app's login, reused until it stops working. */
    val webToken: String = "",
) {
    fun config(p: GlossProvider): GlosserConfig = providerConfigs[p] ?: GlosserConfig(p)

    val primaryGlosser: GlosserConfig get() = config(provider)

    /** The Anthropic config to fall back to, when enabled and different from the primary. */
    val fallbackGlosser: GlosserConfig?
        get() = if (fallbackToAnthropic && provider != GlossProvider.ANTHROPIC) config(GlossProvider.ANTHROPIC) else null

    /** What "Improve answer" asks: the improve model, else Claude when configured, else the primary. */
    val improveGlosser: GlosserConfig
        get() = when {
            improveModel.isNotBlank() -> primaryGlosser.copy(model = improveModel.trim())
            provider != GlossProvider.ANTHROPIC && config(GlossProvider.ANTHROPIC).isComplete -> config(GlossProvider.ANTHROPIC)
            else -> primaryGlosser
        }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.dataStore)

    val settings: Flow<AppSettings> = store.data.map(::read)

    suspend fun current(): AppSettings = settings.first()

    private fun read(p: Preferences): AppSettings {
        val d = AppSettings()
        return AppSettings(
            provider = p[PROVIDER]?.let { runCatching { GlossProvider.valueOf(it) }.getOrNull() } ?: d.provider,
            providerConfigs = GlossProvider.entries.associateWith { gp ->
                GlosserConfig(
                    provider = gp,
                    baseUrl = p[providerKey(gp, "baseUrl")].orEmpty(),
                    apiKey = p[providerKey(gp, "apiKey")].orEmpty(),
                    model = p[providerKey(gp, "model")].orEmpty(),
                )
            },
            fallbackToAnthropic = p[FALLBACK] ?: d.fallbackToAnthropic,
            improveModel = p[IMPROVE_MODEL] ?: d.improveModel,
            preGlossOnImport = p[PREGLOSS] ?: d.preGlossOnImport,
            preGlossSentencesPerWord = p[PREGLOSS_SENTENCES] ?: d.preGlossSentencesPerWord,
            preGlossPagesAhead = p[PREGLOSS_AHEAD] ?: d.preGlossPagesAhead,
            wordsPerPage = p[WORDS_PER_PAGE] ?: d.wordsPerPage,
            readerFontSize = p[FONT_SIZE] ?: d.readerFontSize,
            joinWrappedLines = p[JOIN_LINES] ?: d.joinWrappedLines,
            ttsEngine = p[TTS_ENGINE]?.let { runCatching { TtsEngine.valueOf(it) }.getOrNull() } ?: d.ttsEngine,
            deviceTtsLocale = p[TTS_LOCALE] ?: d.deviceTtsLocale,
            deviceTtsVoice = p[TTS_VOICE] ?: d.deviceTtsVoice,
            googleTtsApiKey = p[GOOGLE_KEY] ?: d.googleTtsApiKey,
            googleTtsVoice = p[GOOGLE_VOICE] ?: d.googleTtsVoice,
            playbackSpeed = p[SPEED] ?: d.playbackSpeed,
            webUrl = p[WEB_URL] ?: d.webUrl,
            webAccessCode = p[WEB_CODE] ?: d.webAccessCode,
            webName = p[WEB_NAME] ?: d.webName,
            webToken = p[WEB_TOKEN] ?: d.webToken,
        )
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { p ->
            val s = transform(read(p))
            p[PROVIDER] = s.provider.name
            for ((gp, c) in s.providerConfigs) {
                p[providerKey(gp, "baseUrl")] = c.baseUrl
                p[providerKey(gp, "apiKey")] = c.apiKey
                p[providerKey(gp, "model")] = c.model
            }
            p[FALLBACK] = s.fallbackToAnthropic
            p[IMPROVE_MODEL] = s.improveModel
            p[PREGLOSS] = s.preGlossOnImport
            p[PREGLOSS_SENTENCES] = s.preGlossSentencesPerWord.coerceIn(1, 10)
            p[PREGLOSS_AHEAD] = s.preGlossPagesAhead.coerceIn(0, 50)
            p[WORDS_PER_PAGE] = s.wordsPerPage.coerceIn(50, 1000)
            p[FONT_SIZE] = s.readerFontSize.coerceIn(12, 40)
            p[JOIN_LINES] = s.joinWrappedLines
            p[TTS_ENGINE] = s.ttsEngine.name
            p[TTS_LOCALE] = s.deviceTtsLocale
            p[TTS_VOICE] = s.deviceTtsVoice
            p[GOOGLE_KEY] = s.googleTtsApiKey
            p[GOOGLE_VOICE] = s.googleTtsVoice
            p[SPEED] = s.playbackSpeed.coerceIn(0.5f, 2.0f)
            p[WEB_URL] = s.webUrl
            p[WEB_CODE] = s.webAccessCode
            p[WEB_NAME] = s.webName
            p[WEB_TOKEN] = s.webToken
        }
    }

    /**
     * Switches the glossing provider. Its base URL (and model, for providers with a default) is filled in from the
     * provider's defaults only when still empty, so anything the user typed is kept.
     */
    suspend fun selectProvider(provider: GlossProvider) = update { s ->
        val c = s.config(provider)
        val filled = c.copy(
            baseUrl = c.baseUrl.ifBlank { provider.defaultBaseUrl.orEmpty() },
            model = c.model.ifBlank { provider.defaultModel.orEmpty() },
        )
        s.copy(provider = provider, providerConfigs = s.providerConfigs + (provider to filled))
    }

    /** Replaces the stored config for [config]'s provider. */
    suspend fun updateProvider(config: GlosserConfig) =
        update { it.copy(providerConfigs = it.providerConfigs + (config.provider to config)) }

    private companion object {
        val PROVIDER = stringPreferencesKey("gloss.provider")
        val FALLBACK = booleanPreferencesKey("gloss.fallbackToAnthropic")
        val IMPROVE_MODEL = stringPreferencesKey("gloss.improveModel")
        val PREGLOSS = booleanPreferencesKey("gloss.preGlossOnImport")
        val PREGLOSS_SENTENCES = intPreferencesKey("gloss.preGlossSentencesPerWord")
        val PREGLOSS_AHEAD = intPreferencesKey("gloss.preGlossPagesAhead")
        val WORDS_PER_PAGE = intPreferencesKey("reader.wordsPerPage")
        val FONT_SIZE = intPreferencesKey("reader.fontSize")
        val JOIN_LINES = booleanPreferencesKey("import.joinWrappedLines")
        val TTS_ENGINE = stringPreferencesKey("tts.engine")
        val TTS_LOCALE = stringPreferencesKey("tts.device.locale")
        val TTS_VOICE = stringPreferencesKey("tts.device.voice")
        val GOOGLE_KEY = stringPreferencesKey("tts.google.apiKey")
        val GOOGLE_VOICE = stringPreferencesKey("tts.google.voice")
        val SPEED = floatPreferencesKey("tts.speed")
        val WEB_URL = stringPreferencesKey("web.url")
        val WEB_CODE = stringPreferencesKey("web.accessCode")
        val WEB_NAME = stringPreferencesKey("web.name")
        val WEB_TOKEN = stringPreferencesKey("web.token")

        fun providerKey(p: GlossProvider, field: String) = stringPreferencesKey("gloss.${p.name}.$field")
    }
}
