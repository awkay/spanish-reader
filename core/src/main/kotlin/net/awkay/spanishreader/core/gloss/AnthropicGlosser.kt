package net.awkay.spanishreader.core.gloss

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Anthropic Messages API over raw HTTP (no SDK, to keep core light). */
class AnthropicGlosser(
    private val apiKey: String,
    private val model: String = "claude-haiku-4-5",
    httpClient: OkHttpClient = OkHttpClient(),
    options: GlossOptions = GlossOptions(),
    baseUrl: String = "https://api.anthropic.com",
) : LlmGlosser(httpClient, options) {
    private val endpoint = baseUrl.trimEnd('/') + "/v1/messages"

    override fun buildRequest(system: String, user: String, itemCount: Int): Request {
        val body = buildJsonObject {
            put("model", model)
            // Required by the API. Only tokens produced are billed, so the answer-length limit itself is sent: the old
            // 1024 for single-item calls could cut off drill/say-it batches, which are one "item".
            put("max_tokens", options.maxOutputTokens ?: GlossOptions.DEFAULT_MAX_OUTPUT_TOKENS)
            put("temperature", options.temperature)
            put("system", system)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", user)
                }
            }
        }
        return Request.Builder()
            .url(endpoint)
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .post(body.toString().toRequestBody(JSON))
            .build()
    }

    override fun replyText(responseBody: String): String {
        val root = Json.parseToJsonElement(responseBody) as? JsonObject
        if ((root?.get("stop_reason") as? JsonPrimitive)?.content == "max_tokens") throw TruncatedResponseException(options.maxOutputTokens)
        val blocks = (root?.get("content") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val text = blocks.firstOrNull { (it["type"] as? JsonPrimitive)?.content == "text" }?.get("text") as? JsonPrimitive
        return text?.content
            ?: throw MalformedGlossResponseException("No text content block in response: ${responseBody.take(200)}")
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
