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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.awkay.spanishreader.core.gloss.GlossProvider
import net.awkay.spanishreader.core.gloss.GlosserConfig
import net.awkay.spanishreader.core.text.Paginator

enum class TtsEngine(val label: String) { DEVICE("On-device (Android TTS)"), GOOGLE_CLOUD("Google Cloud TTS") }

/**
 * One saved AI account + model ("OpenRouter · deepseek", "z.ai plan · glm-5.3"). Any number can be kept; one is used
 * for word meanings and the rest of the app, others can serve "Improve answer" and as a fallback.
 */
data class AiProfile(
    val id: String,
    val name: String = "",
    /** [GlossProvider] name; an unknown one reads as "Other OpenAI-compatible". */
    val provider: String = GlossProvider.OPENAI_COMPATIBLE.name,
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    /** Answer-length limit in tokens, as typed; blank = the app default, 0 = no limit. */
    val maxOutputTokens: String = "",
) {
    val glossProvider: GlossProvider get() = runCatching { GlossProvider.valueOf(provider) }.getOrDefault(GlossProvider.OPENAI_COMPATIBLE)

    val config: GlosserConfig
        get() = GlosserConfig(glossProvider, baseUrl.trim(), apiKey.trim(), model.trim(), maxOutputTokens.trim().toIntOrNull()?.coerceAtLeast(0))

    /** [name], or one made from the service and model. */
    val label: String
        get() = name.trim().ifEmpty {
            val host = config.effectiveBaseUrl.substringAfter("://").substringBefore('/').removePrefix("api.")
            val service = if (glossProvider == GlossProvider.OPENAI_COMPATIBLE && host.isNotEmpty()) host else glossProvider.label
            listOf(service, config.effectiveModel).filter { it.isNotEmpty() }.joinToString(" · ")
        }

    companion object {
        fun of(config: GlosserConfig, id: String = newId(), name: String = "") =
            AiProfile(id, name, config.provider.name, config.baseUrl, config.apiKey, config.model, config.maxOutputTokens?.toString().orEmpty())

        fun newId(): String = java.util.UUID.randomUUID().toString()

        /** Stored as a JSON array (by hand: the app module doesn't use the serialization plugin). */
        fun encode(list: List<AiProfile>): String = buildJsonArray {
            for (a in list) add(buildJsonObject {
                put("id", a.id); put("name", a.name); put("provider", a.provider)
                put("baseUrl", a.baseUrl); put("apiKey", a.apiKey); put("model", a.model)
                if (a.maxOutputTokens.isNotBlank()) put("maxOutputTokens", a.maxOutputTokens)
            })
        }.toString()

        fun decode(text: String): List<AiProfile> = runCatching {
            (Json.parseToJsonElement(text) as JsonArray).mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                fun str(k: String) = (o[k] as? JsonPrimitive)?.content.orEmpty()
                str("id").takeIf { it.isNotEmpty() }?.let { AiProfile(it, str("name"), str("provider"), str("baseUrl"), str("apiKey"), str("model"), str("maxOutputTokens")) }
            }
        }.getOrDefault(emptyList())
    }
}

data class AppSettings(
    /** Saved AI accounts and models (see [AiProfile]). */
    val aiProfiles: List<AiProfile> = emptyList(),
    /** The profile used for word meanings, translations, practice and conversation. */
    val activeProfileId: String = "",
    /** Profile for "Improve answer" and for reviewing AI-written cards; blank = the active one. */
    val improveProfileId: String = "",
    /** Profile asked when the active one fails; blank = none. */
    val fallbackProfileId: String = "",
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
    val webUrl: String = "https://portal.fulcrologic.com",
    val webAccessCode: String = "",
    val webName: String = "",
    /** Session token from the web app's login, reused until it stops working. */
    val webToken: String = "",
    /** Account on the web app (one lowercase word), for sync. */
    val webUser: String = "",
    /** JSON of the last sync ({user, state}), see SyncService. */
    val syncState: String = "",
) {
    fun profile(id: String): AiProfile? = aiProfiles.firstOrNull { it.id == id }

    val activeProfile: AiProfile? get() = profile(activeProfileId)

    /** What word meanings use: the active profile (an empty Ollama Cloud config, i.e. "not set up", when there is none). */
    val primaryGlosser: GlosserConfig get() = activeProfile?.config ?: GlosserConfig(GlossProvider.OLLAMA_CLOUD)

    /** The fallback profile's config, when one is chosen and it isn't the active one. */
    val fallbackGlosser: GlosserConfig?
        get() = profile(fallbackProfileId)?.takeIf { it.id != activeProfileId }?.config

    /** What "Improve answer" (and the review of AI-written cards) asks: the chosen profile, else the active one. */
    val improveGlosser: GlosserConfig get() = profile(improveProfileId)?.config ?: primaryGlosser
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.dataStore)

    val settings: Flow<AppSettings> = store.data.map(::read)

    suspend fun current(): AppSettings = settings.first()

    private fun read(p: Preferences): AppSettings {
        val d = AppSettings()
        val ai = p[PROFILES]?.let { raw ->
            val list = AiProfile.decode(raw)
            AiChoice(list, p[ACTIVE_PROFILE].orEmpty(), p[IMPROVE_PROFILE].orEmpty(), p[FALLBACK_PROFILE].orEmpty())
        } ?: migrate(p)
        return AppSettings(
            aiProfiles = ai.profiles,
            activeProfileId = ai.active,
            improveProfileId = ai.improve,
            fallbackProfileId = ai.fallback,
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
            // Any settings save stored the old default; it never served the app, so move it to the new one.
            webUrl = p[WEB_URL]?.takeUnless { it.trim().trimEnd('/') == OLD_WEB_URL } ?: d.webUrl,
            webAccessCode = p[WEB_CODE] ?: d.webAccessCode,
            webName = p[WEB_NAME] ?: d.webName,
            webToken = p[WEB_TOKEN] ?: d.webToken,
            webUser = p[WEB_USER] ?: d.webUser,
            syncState = p[SYNC_STATE] ?: d.syncState,
        )
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { p ->
            val s = transform(read(p))
            // The old per-provider keys are left as they were (an older build still finds its settings).
            p[PROFILES] = AiProfile.encode(s.aiProfiles)
            p[ACTIVE_PROFILE] = s.activeProfileId
            p[IMPROVE_PROFILE] = s.improveProfileId
            p[FALLBACK_PROFILE] = s.fallbackProfileId
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
            p[WEB_USER] = s.webUser
            p[SYNC_STATE] = s.syncState
        }
    }

    /** Adds [profile], or replaces the one with its id. */
    suspend fun saveProfile(profile: AiProfile) = update { s ->
        val list = if (s.aiProfiles.any { it.id == profile.id }) s.aiProfiles.map { if (it.id == profile.id) profile else it } else s.aiProfiles + profile
        s.copy(aiProfiles = list, activeProfileId = s.activeProfileId.ifEmpty { profile.id })
    }

    /** Removes a profile; if it was in use, the first remaining one becomes active, and "improve"/fallback reset. */
    suspend fun deleteProfile(id: String) = update { s ->
        val list = s.aiProfiles.filter { it.id != id }
        s.copy(
            aiProfiles = list,
            activeProfileId = if (s.activeProfileId == id) list.firstOrNull()?.id.orEmpty() else s.activeProfileId,
            improveProfileId = if (s.improveProfileId == id) "" else s.improveProfileId,
            fallbackProfileId = if (s.fallbackProfileId == id) "" else s.fallbackProfileId,
        )
    }

    suspend fun activate(id: String) = update { s -> if (s.profile(id) != null) s.copy(activeProfileId = id) else s }

    /** Saves [config] as a profile (replacing one with the same provider, URL and model) and makes it the active one. */
    suspend fun useConfig(config: GlosserConfig, name: String = "") = update { s ->
        val same = s.aiProfiles.firstOrNull { it.config.let { c -> c.provider == config.provider && c.baseUrl == config.baseUrl && c.model == config.model } }
        val profile = AiProfile.of(config, id = same?.id ?: AiProfile.newId(), name = name.ifEmpty { same?.name.orEmpty() })
        val list = if (same != null) s.aiProfiles.map { if (it.id == same.id) profile else it } else s.aiProfiles + profile
        s.copy(aiProfiles = list, activeProfileId = profile.id)
    }

    private data class AiChoice(val profiles: List<AiProfile>, val active: String, val improve: String, val fallback: String)

    /**
     * Profiles from the settings of earlier versions (one config per provider, a chosen provider, an "improve" model
     * name and a Claude fallback switch), so an upgrade keeps everything: every provider that had anything typed in
     * becomes a profile; the improve model becomes a copy of the active profile with that model; the implicit
     * "Claude if set up" for improve and the Claude fallback become explicit choices. Ids are fixed, so reading
     * twice before the first save gives the same profiles.
     */
    private fun migrate(p: Preferences): AiChoice {
        fun legacy(gp: GlossProvider) = GlosserConfig(gp, p[providerKey(gp, "baseUrl")].orEmpty(), p[providerKey(gp, "apiKey")].orEmpty(), p[providerKey(gp, "model")].orEmpty())
        val chosen = p[PROVIDER]?.let { runCatching { GlossProvider.valueOf(it) }.getOrNull() }
        val profiles = GlossProvider.entries.mapNotNull { gp ->
            val c = legacy(gp)
            // Earlier versions saved a slot for every service and filled in a service's default URL (and model, for
            // Claude) as soon as it was picked in the dropdown; a slot holding only those defaults was never set up.
            fun typed(value: String, default: String?) = value.isNotBlank() && value.trim() != default
            val used = gp == chosen || c.apiKey.isNotBlank() || typed(c.baseUrl, gp.defaultBaseUrl) || typed(c.model, gp.defaultModel)
            if (used) AiProfile.of(c, id = "legacy-${gp.name}") else null
        }.toMutableList()
        val active = chosen?.let { "legacy-${it.name}" } ?: profiles.firstOrNull()?.id.orEmpty()
        val anthropic = profiles.firstOrNull { it.glossProvider == GlossProvider.ANTHROPIC && it.config.isComplete }
        val improveModel = p[IMPROVE_MODEL].orEmpty().trim()
        val activeProfile = profiles.firstOrNull { it.id == active }
        val improve = when {
            improveModel.isNotEmpty() && activeProfile != null -> {
                profiles += activeProfile.copy(id = "legacy-improve", model = improveModel)
                "legacy-improve"
            }
            anthropic != null && anthropic.id != active -> anthropic.id
            else -> ""
        }
        val fallback = if (p[FALLBACK] == true && anthropic != null && anthropic.id != active) anthropic.id else ""
        return AiChoice(profiles, active, improve, fallback)
    }

    private companion object {
        val PROFILES = stringPreferencesKey("ai.profiles")
        val ACTIVE_PROFILE = stringPreferencesKey("ai.active")
        val IMPROVE_PROFILE = stringPreferencesKey("ai.improve")
        val FALLBACK_PROFILE = stringPreferencesKey("ai.fallback")

        // Settings of earlier versions, read once to make the first profiles.
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
        const val OLD_WEB_URL = "https://spanish-reader.fulcrologic.com"
        val WEB_URL = stringPreferencesKey("web.url")
        val WEB_CODE = stringPreferencesKey("web.accessCode")
        val WEB_NAME = stringPreferencesKey("web.name")
        val WEB_TOKEN = stringPreferencesKey("web.token")
        val WEB_USER = stringPreferencesKey("web.user")
        val SYNC_STATE = stringPreferencesKey("sync.state")

        fun providerKey(p: GlossProvider, field: String) = stringPreferencesKey("gloss.${p.name}.$field")
    }
}
