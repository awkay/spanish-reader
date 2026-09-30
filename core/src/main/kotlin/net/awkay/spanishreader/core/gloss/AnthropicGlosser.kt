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
    /** max_tokens for a single word; batches scale up by [tokensPerItem], capped at [maxTokensCap]. */
    private val maxTokens: Int = 1024,
    private val tokensPerItem: Int = 600,
    private val maxTokensCap: Int = 16_000,
    baseUrl: String = "https://api.anthropic.com",
) : LlmGlosser(httpClient, options) {
    private val endpoint = baseUrl.trimEnd('/') + "/v1/messages"

    override fun buildRequest(system: String, user: String, itemCount: Int): Request {
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", (tokensPerItem * itemCount).coerceIn(maxTokens, maxOf(maxTokens, maxTokensCap)))
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
        val blocks = (root?.get("content") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val text = blocks.firstOrNull { (it["type"] as? JsonPrimitive)?.content == "text" }?.get("text") as? JsonPrimitive
        return text?.content
            ?: throw MalformedGlossResponseException("No text content block in response: ${responseBody.take(200)}")
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
