package net.awkay.spanishreader.core.gloss

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

enum class GlossProvider(
    val label: String,
    val defaultBaseUrl: String?,
    val requiresApiKey: Boolean,
    /** Only Anthropic gets a default: hosted open-model lineups change too often to bake one in. */
    val defaultModel: String?,
) {
    OLLAMA_CLOUD("Ollama Cloud", "https://ollama.com/v1", true, null),
    OLLAMA_LOCAL("Ollama (local network)", null, false, null),
    /**
     * GLM Coding Plan via the OpenAI Responses API. Unlike the chat-completions coding endpoint, it serves the model
     * actually requested (e.g. glm-4.6) instead of rerouting to the newest one.
     */
    ZAI_RESPONSES("z.ai GLM Coding Plan (Responses API)", "https://api.z.ai/api/v1", true, null),

    /** GLM Coding Plan subscription keys only work on the coding endpoints. */
    ZAI_CODING("z.ai GLM Coding Plan", "https://api.z.ai/api/coding/paas/v4", true, null),

    /** Pay-as-you-go z.ai balance; Coding Plan keys get HTTP 429 "Insufficient balance" here. */
    ZAI("z.ai GLM (pay-as-you-go)", "https://api.z.ai/api/paas/v4", true, null),
    ANTHROPIC("Anthropic Claude", "https://api.anthropic.com", true, "claude-haiku-4-5"),
    OPENAI_COMPATIBLE("Other OpenAI-compatible", null, false, null),
    ;

    val isZai: Boolean get() = this == ZAI || this == ZAI_CODING || this == ZAI_RESPONSES
}

/** User-editable glossing settings. Blank [baseUrl]/[model] mean "use the provider default". */
data class GlosserConfig(
    val provider: GlossProvider = GlossProvider.OLLAMA_CLOUD,
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
) {
    val effectiveBaseUrl: String get() = baseUrl.trim().ifEmpty { provider.defaultBaseUrl.orEmpty() }
    val effectiveModel: String get() = model.trim().ifEmpty { provider.defaultModel.orEmpty() }

    /** Human-readable reasons this config cannot be used; empty when it is complete. */
    fun problems(): List<String> = buildList {
        if (effectiveBaseUrl.isEmpty()) add("${provider.label}: base URL is required")
        else if (!effectiveBaseUrl.startsWith("http://") && !effectiveBaseUrl.startsWith("https://")) {
            add("${provider.label}: base URL must start with http:// or https://")
        }
        if (effectiveModel.isEmpty()) add("${provider.label}: model name is required")
        if (provider.requiresApiKey && apiKey.isBlank()) add("${provider.label}: API key is required")
    }

    val isComplete: Boolean get() = problems().isEmpty()
}

object GlosserFactory {
    /**
     * Builds the glosser for [primary], wrapped in a [FallbackGlosser] when a complete [fallback] is given.
     * @throws IllegalArgumentException if [primary] is incomplete.
     */
    fun create(
        primary: GlosserConfig,
        fallback: GlosserConfig? = null,
        httpClient: OkHttpClient = defaultHttpClient,
        options: GlossOptions = GlossOptions(),
    ): Glosser {
        val problems = primary.problems()
        require(problems.isEmpty()) { problems.joinToString("; ") }
        val main = single(primary, httpClient, options)
        return if (fallback != null && fallback.isComplete) FallbackGlosser(main, single(fallback, httpClient, options)) else main
    }

    /** The idiom finder for [config] (every provider supports it). */
    fun createPhraseFinder(config: GlosserConfig, httpClient: OkHttpClient = defaultHttpClient, options: GlossOptions = GlossOptions()): PhraseFinder {
        val problems = config.problems()
        require(problems.isEmpty()) { problems.joinToString("; ") }
        return single(config, httpClient, options) as PhraseFinder
    }

    private fun single(c: GlosserConfig, http: OkHttpClient, options: GlossOptions): Glosser = when (c.provider) {
        GlossProvider.ZAI_RESPONSES -> OpenAiResponsesGlosser(
            baseUrl = c.effectiveBaseUrl, apiKey = c.apiKey.trim().ifEmpty { null }, model = c.effectiveModel,
            httpClient = http, options = options,
        )
        GlossProvider.ANTHROPIC -> AnthropicGlosser(
            apiKey = c.apiKey.trim(), model = c.effectiveModel, httpClient = http, options = options, baseUrl = c.effectiveBaseUrl,
        )
        else -> OpenAiCompatibleGlosser(
            baseUrl = c.effectiveBaseUrl, apiKey = c.apiKey.trim().ifEmpty { null }, model = c.effectiveModel,
            httpClient = http, options = options,
            // GLM models reason by default; glossing doesn't need it and it roughly doubles latency.
            extraParams = if (c.provider.isZai) mapOf("thinking" to buildJsonObject { put("type", "disabled") }) else emptyMap(),
        )
    }

    /** LLM batches can take tens of seconds; OkHttp's 10 s read timeout is far too short. */
    val defaultHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .build()
    }
}

/** Sends whatever [primary] fails to gloss to [fallback]. When both fail, both errors are reported. */
class FallbackGlosser(private val primary: Glosser, private val fallback: Glosser) : Glosser {
    override suspend fun gloss(requests: List<GlossRequest>): List<GlossResult> {
        val first = primary.gloss(requests)
        val failedIds = first.filterIsInstance<GlossResult.Failure>().associateBy { it.id }
        if (failedIds.isEmpty()) return first
        val retried = fallback.gloss(requests.filter { it.id in failedIds }).associateBy { it.id }
        return first.map { r ->
            if (r !is GlossResult.Failure) return@map r
            when (val second = retried.getValue(r.id)) {
                is GlossResult.Success -> second
                is GlossResult.Failure -> GlossResult.Failure(r.id, "${r.error}; fallback: ${second.error}", second.cause ?: r.cause)
            }
        }
    }
}
