package net.awkay.spanishreader.core.gloss

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GlosserHttpTest {
    private lateinit var server: MockWebServer
    private val sleeps = mutableListOf<Long>()
    private val options get() = GlossOptions(retry = RetryPolicy(sleep = { sleeps += it }))

    @BeforeTest
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @AfterTest
    fun tearDown() = server.close()

    private fun glossText(id: String, form: String, meaning: String) =
        """{"glosses":[{"id":"$id","form":"$form","lemma":"$form","partOfSpeech":"noun","meaningInContext":"$meaning"}]}"""

    private fun openAiBody(content: String) = buildJsonObject {
        put("choices", buildJsonArray { add(buildJsonObject { put("message", buildJsonObject { put("role", "assistant"); put("content", content) }) }) })
    }.toString()

    private fun anthropicBody(text: String) = buildJsonObject {
        put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", text) }) })
    }.toString()

    private fun ok(body: String) = MockResponse.Builder().code(200).body(body).build()

    private fun requestJson() = server.takeRequest().let { it to Json.parseToJsonElement(it.body!!.utf8()).jsonObject }

    @Test
    fun `openai compatible request shape and parsing`() = runTest {
        server.enqueue(ok(openAiBody(glossText("1", "casa", "house"))))
        val glosser = OpenAiCompatibleGlosser(server.url("/v1/").toString(), "secret", "llama3", options = options)
        val result = glosser.gloss("casa", "Mi casa es tu casa.")
        assertEquals("house", assertIs<GlossResult.Success>(result).gloss.meaningInContext)

        val (req, body) = requestJson()
        assertEquals("/v1/chat/completions", req.url.encodedPath)
        assertEquals("POST", req.method)
        assertEquals("Bearer secret", req.headers["Authorization"])
        assertEquals("llama3", body["model"]!!.jsonPrimitive.content)
        assertEquals("json_object", body["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val messages = body["messages"]!!.jsonArray
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertTrue("Mi casa es tu casa." in messages[1].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `openai without api key sends no authorization`() = runTest {
        server.enqueue(ok(openAiBody(glossText("casa", "casa", "house"))))
        OpenAiCompatibleGlosser(server.url("/v1").toString(), null, "m", options = options).gloss("casa", "Casa.")
        assertEquals(null, server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `anthropic request shape and parsing`() = runTest {
        server.enqueue(ok(anthropicBody("```json\n" + glossText("casa", "casa", "home") + "\n```")))
        val glosser = AnthropicGlosser(apiKey = "sk-test", baseUrl = httpClientBaseUrl(), options = options)
        val result = glosser.gloss("casa", "Vuelvo a casa.")
        assertEquals("home", assertIs<GlossResult.Success>(result).gloss.meaningInContext)

        val (req, body) = requestJson()
        assertEquals("/v1/messages", req.url.encodedPath)
        assertEquals("sk-test", req.headers["x-api-key"])
        assertEquals("2023-06-01", req.headers["anthropic-version"])
        assertTrue(req.headers["content-type"]!!.startsWith("application/json"))
        assertEquals("claude-haiku-4-5", body["model"]!!.jsonPrimitive.content)
        assertEquals(1024, body["max_tokens"]!!.jsonPrimitive.int)
        assertTrue("Vuelvo a casa." in body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content)
    }

    private fun httpClientBaseUrl() = server.url("/").toString()

    @Test
    fun `retries 429 honoring Retry-After then succeeds`() = runTest {
        server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "7").build())
        server.enqueue(MockResponse.Builder().code(503).build())
        server.enqueue(ok(anthropicBody(glossText("casa", "casa", "house"))))
        val result = AnthropicGlosser(apiKey = "k", baseUrl = httpClientBaseUrl(), options = options).gloss("casa", "Casa.")
        assertIs<GlossResult.Success>(result)
        assertEquals(listOf(7000L, 2000L), sleeps)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `non retryable error becomes failure`() = runTest {
        server.enqueue(MockResponse.Builder().code(401).body("bad key").build())
        val result = OpenAiCompatibleGlosser(server.url("/v1").toString(), "x", "m", options = options).gloss("casa", "Casa.")
        assertTrue("401" in assertIs<GlossResult.Failure>(result).error)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `malformed reply is retried once`() = runTest {
        server.enqueue(ok(openAiBody("Sorry, here is the meaning: house")))
        server.enqueue(ok(openAiBody(glossText("casa", "casa", "house"))))
        val result = OpenAiCompatibleGlosser(server.url("/v1").toString(), null, "m", options = options).gloss("casa", "Casa.")
        assertIs<GlossResult.Success>(result)
        server.takeRequest()
        val retry = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertTrue("valid JSON only" in retry["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `malformed twice gives failure`() = runTest {
        repeat(2) { server.enqueue(ok(openAiBody("nope"))) }
        val result = OpenAiCompatibleGlosser(server.url("/v1").toString(), null, "m", options = options).gloss("casa", "Casa.")
        assertIs<GlossResult.Failure>(result)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `batches requests and keeps order`() = runTest {
        val reqs = (1..5).map { GlossRequest("w$it", "Frase $it.", "id$it") }
        fun reply(ids: List<Int>) = openAiBody(
            buildJsonObject {
                put("glosses", buildJsonArray {
                    ids.forEach { add(buildJsonObject { put("id", "id$it"); put("form", "w$it"); put("lemma", "w$it"); put("partOfSpeech", "noun"); put("meaningInContext", "m$it") }) }
                })
            }.toString(),
        )
        server.enqueue(ok(reply(listOf(2, 1)))) // out of order within a batch
        server.enqueue(ok(reply(listOf(3, 4))))
        server.enqueue(ok(reply(listOf(5))))
        val glosser = OpenAiCompatibleGlosser(server.url("/v1").toString(), null, "m", options = options.copy(maxBatchSize = 2, maxConcurrency = 1))
        val results = glosser.gloss(reqs)
        assertEquals(reqs.map { it.id }, results.map { it.id })
        assertEquals((1..5).map { "m$it" }, results.map { (it as GlossResult.Success).gloss.meaningInContext })
        assertEquals(3, server.requestCount)
        val firstUser = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject["messages"]!!.jsonArray[1]
            .jsonObject["content"]!!.jsonPrimitive.content
        assertTrue("Frase 1." in firstUser && "Frase 2." in firstUser && "Frase 3." !in firstUser)
    }

    @Test
    fun `caching glosser serves hits and caches successes`() = runTest {
        server.enqueue(ok(openAiBody(glossText("casa", "casa", "house"))))
        val cache = InMemoryGlossCache()
        val glosser = CachingGlosser(OpenAiCompatibleGlosser(server.url("/v1").toString(), null, "m", options = options), cache)
        glosser.gloss("casa", "Mi casa.")
        val again = glosser.gloss("casa", "Mi   casa.")
        assertEquals("house", (again as GlossResult.Success).gloss.meaningInContext)
        assertEquals(1, server.requestCount)
        assertEquals("house", cache.getByForm("Casa")?.meaningInContext)
        assertEquals(null, cache.get("casa", "Otra frase."))
    }
}
