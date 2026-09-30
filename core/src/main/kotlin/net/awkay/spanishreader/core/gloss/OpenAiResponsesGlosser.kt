package net.awkay.spanishreader.core.gloss

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * OpenAI Responses API (`POST {baseUrl}/responses`), e.g. z.ai's `https://api.z.ai/api/v1`, which serves the GLM
 * Coding Plan with the model actually requested (the chat-completions coding endpoint reroutes older models).
 */
class OpenAiResponsesGlosser(
    baseUrl: String,
    private val apiKey: String?,
    private val model: String,
    httpClient: OkHttpClient = OkHttpClient(),
    options: GlossOptions = GlossOptions(),
    /** `reasoning.effort`; glossing needs little reasoning. Null omits the field. */
    private val reasoningEffort: String? = "low",
) : LlmGlosser(httpClient, options) {
    private val endpoint = baseUrl.trimEnd('/') + "/responses"

    override fun buildRequest(system: String, user: String, itemCount: Int): Request {
        val body = buildJsonObject {
            put("model", model)
            put("instructions", system)
            put("input", user)
            put("temperature", options.temperature)
            reasoningEffort?.let { putJsonObject("reasoning") { put("effort", it) } }
            putJsonObject("text") { putJsonObject("format") { put("type", "json_object") } }
        }
        return Request.Builder()
            .url(endpoint)
            .apply { if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey") }
            .post(body.toString().toRequestBody(JSON))
            .build()
    }

    /** Concatenates the `output_text` parts of `message` output items; reasoning items are skipped. */
    override fun replyText(responseBody: String): String {
        val root = Json.parseToJsonElement(responseBody) as? JsonObject
        (root?.get("error") as? JsonObject)?.let {
            throw MalformedGlossResponseException("Responses API error: $it")
        }
        val text = (root?.get("output") as? JsonArray).orEmpty()
            .filterIsInstance<JsonObject>()
            .filter { (it["type"] as? JsonPrimitive)?.content == "message" }
            .flatMap { (it["content"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>() }
            .filter { (it["type"] as? JsonPrimitive)?.content == "output_text" }
            .mapNotNull { (it["text"] as? JsonPrimitive)?.content }
            .joinToString("")
        return text.ifEmpty {
            (root?.get("output_text") as? JsonPrimitive)?.content
                ?: throw MalformedGlossResponseException("No output_text in response: ${responseBody.take(200)}")
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
