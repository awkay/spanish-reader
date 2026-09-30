package net.awkay.spanishreader.core.gloss

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Any OpenAI-compatible `/chat/completions` endpoint, e.g.
 * Ollama local (`http://host:11434/v1`), Ollama cloud (`https://ollama.com/v1` + API key),
 * z.ai GLM (`https://api.z.ai/api/paas/v4` + API key).
 */
class OpenAiCompatibleGlosser(
    baseUrl: String,
    private val apiKey: String?,
    private val model: String,
    httpClient: OkHttpClient = OkHttpClient(),
    options: GlossOptions = GlossOptions(),
    /** Provider-specific top-level request fields, e.g. z.ai's `thinking` switch. */
    private val extraParams: Map<String, JsonElement> = emptyMap(),
) : LlmGlosser(httpClient, options) {
    private val endpoint = baseUrl.trimEnd('/') + "/chat/completions"

    override fun buildRequest(system: String, user: String, itemCount: Int): Request {
        val body = buildJsonObject {
            put("model", model)
            put("temperature", options.temperature)
            putJsonObject("response_format") { put("type", "json_object") }
            extraParams.forEach { (k, v) -> put(k, v) }
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", system)
                }
                addJsonObject {
                    put("role", "user")
                    put("content", user)
                }
            }
        }
        return Request.Builder()
            .url(endpoint)
            .apply { if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey") }
            .post(body.toString().toRequestBody(JSON))
            .build()
    }

    override fun replyText(responseBody: String): String {
        val root = Json.parseToJsonElement(responseBody) as? JsonObject
        val message = ((root?.get("choices") as? JsonArray)?.firstOrNull() as? JsonObject)?.get("message") as? JsonObject
        return (message?.get("content") as? JsonPrimitive)?.content
            ?: throw MalformedGlossResponseException("No choices[0].message.content in response: ${responseBody.take(200)}")
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
