package net.awkay.spanishreader.core.gloss

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The answer-length limit: sent to every provider, and a reply cut off by it is recognised and handled. */
class AnswerLengthLimitTest {
    private val server = MockWebServer().apply { start() }

    @AfterTest
    fun tearDown() = server.close()

    private val options = GlossOptions(retry = RetryPolicy(sleep = {}))

    private fun glosses(vararg ids: Int) = buildJsonObject {
        put("glosses", buildJsonArray {
            ids.forEach { add(buildJsonObject { put("id", "id$it"); put("form", "w$it"); put("lemma", "w$it"); put("partOfSpeech", "noun"); put("meaningInContext", "m$it") }) }
        })
    }.toString()

    private fun responses(text: String?, incomplete: Boolean = false) = buildJsonObject {
        put("status", if (incomplete) "incomplete" else "completed")
        if (incomplete) put("incomplete_details", buildJsonObject { put("reason", "max_output_tokens") })
        put("output", buildJsonArray {
            add(buildJsonObject { put("type", "reasoning"); put("content", buildJsonArray {}) })
            if (text != null) add(buildJsonObject {
                put("type", "message")
                put("content", buildJsonArray { add(buildJsonObject { put("type", "output_text"); put("text", text) }) })
            })
        })
    }.toString()

    private fun ok(body: String) = MockResponse.Builder().code(200).body(body).build()
    private fun bodyOf(r: mockwebserver3.RecordedRequest) = Json.parseToJsonElement(r.body!!.utf8()).jsonObject
    private fun requests(n: Int) = (1..n).map { GlossRequest("w$it", "Frase $it.", "id$it") }
    private fun responsesGlosser(o: GlossOptions = options) = OpenAiResponsesGlosser(server.url("/v1").toString(), "k", "m", options = o)

    @Test
    fun `responses API gets max_output_tokens, 16k by default`() = runTest {
        server.enqueue(ok(responses(glosses(1))))
        responsesGlosser().gloss(requests(1))
        assertEquals(16_000, bodyOf(server.takeRequest())["max_output_tokens"]!!.jsonPrimitive.int)
    }

    @Test
    fun `no limit sends no field`() = runTest {
        server.enqueue(ok(responses(glosses(1))))
        responsesGlosser(options.copy(maxOutputTokens = null)).gloss(requests(1))
        assertNull(bodyOf(server.takeRequest())["max_output_tokens"])
    }

    @Test
    fun `config setting overrides the default, 0 means none`() {
        val base = GlossOptions()
        assertEquals(16_000, GlosserConfig().applyTo(base).maxOutputTokens)
        assertEquals(4_000, GlosserConfig(maxOutputTokens = 4_000).applyTo(base).maxOutputTokens)
        assertNull(GlosserConfig(maxOutputTokens = 0).applyTo(base).maxOutputTokens)
    }

    @Test
    fun `factory passes the profile's limit to the client`() = runTest {
        server.enqueue(ok(responses(glosses(1))))
        val config = GlosserConfig(GlossProvider.ZAI_RESPONSES, server.url("/v1").toString(), "k", "m", maxOutputTokens = 6_000)
        GlosserFactory.create(config, options = options).gloss(requests(1))
        assertEquals(6_000, bodyOf(server.takeRequest())["max_output_tokens"]!!.jsonPrimitive.int)
    }

    @Test
    fun `a cut-off batch is split in halves instead of resent whole`() = runTest {
        // Partial JSON, as z.ai returns it when the limit is hit mid-answer.
        server.enqueue(ok(responses(glosses(1, 2, 3, 4).dropLast(40), incomplete = true)))
        server.enqueue(ok(responses(glosses(1, 2))))
        server.enqueue(ok(responses(glosses(3, 4))))
        val results = responsesGlosser().gloss(requests(4))
        assertEquals(listOf("m1", "m2", "m3", "m4"), results.map { assertIs<GlossResult.Success>(it).gloss.meaningInContext })
        assertEquals(3, server.requestCount)
        server.takeRequest()
        val firstHalf = bodyOf(server.takeRequest())["input"]!!.jsonPrimitive.content
        assertTrue("Frase 2." in firstHalf && "Frase 3." !in firstHalf)
    }

    @Test
    fun `reasoning that uses up the limit before any text is a truncation too`() = runTest {
        repeat(2) { server.enqueue(ok(responses(null, incomplete = true))) }
        val r = responsesGlosser(options.copy(maxOutputTokens = 300)).gloss(requests(1)).single()
        assertTrue("cut off at the 300-token" in assertIs<GlossResult.Failure>(r).error)
        assertEquals(2, server.requestCount) // one word can't be split: retried once like any bad reply
    }

    @Test
    fun `a half that still fails is reported, the other half kept`() = runTest {
        server.enqueue(ok(responses("{", incomplete = true)))
        server.enqueue(ok(responses(glosses(1))))
        repeat(2) { server.enqueue(ok(responses("{", incomplete = true))) }
        val results = responsesGlosser().gloss(requests(2))
        assertIs<GlossResult.Success>(results[0])
        assertTrue("cut off" in assertIs<GlossResult.Failure>(results[1]).error)
    }

    @Test
    fun `completer surfaces truncation as a malformed reply`() = runTest {
        server.enqueue(ok(responses("{\"cards\":[", incomplete = true)))
        assertFailsWith<TruncatedResponseException> { responsesGlosser().complete("sys", "user") }
    }

    @Test
    fun `chat completions send max_tokens and treat finish_reason length as truncation`() = runTest {
        fun chat(text: String, finish: String) = buildJsonObject {
            put("choices", buildJsonArray { add(buildJsonObject {
                put("finish_reason", finish); put("message", buildJsonObject { put("role", "assistant"); put("content", text) })
            }) })
        }.toString()
        server.enqueue(ok(chat(glosses(1, 2).dropLast(30), "length")))
        server.enqueue(ok(chat(glosses(1), "stop")))
        server.enqueue(ok(chat(glosses(2), "stop")))
        val results = OpenAiCompatibleGlosser(server.url("/v1").toString(), null, "m", options = options.copy(maxOutputTokens = 8_000)).gloss(requests(2))
        assertTrue(results.all { it is GlossResult.Success })
        assertEquals(8_000, bodyOf(server.takeRequest())["max_tokens"]!!.jsonPrimitive.int)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `openai's own API gets max_completion_tokens`() {
        assertEquals("max_completion_tokens", OpenAiCompatibleGlosser("https://api.openai.com/v1", "k", "gpt-5").lengthField)
        assertEquals("max_tokens", OpenAiCompatibleGlosser("https://openrouter.ai/api/v1", "k", "m").lengthField)
    }

    @Test
    fun `anthropic sends the limit as max_tokens, also for single-item practice calls, and spots stop_reason max_tokens`() = runTest {
        server.enqueue(ok("""{"stop_reason":"max_tokens","content":[{"type":"text","text":"{\"cards\":["}]}"""))
        val g = AnthropicGlosser(apiKey = "k", baseUrl = server.url("/").toString(), options = options)
        assertFailsWith<TruncatedResponseException> { g.complete("sys", "user") }
        assertEquals(16_000, bodyOf(server.takeRequest())["max_tokens"]!!.jsonPrimitive.int)
    }
}
