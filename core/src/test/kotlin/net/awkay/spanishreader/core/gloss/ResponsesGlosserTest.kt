package net.awkay.spanishreader.core.gloss

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ResponsesGlosserTest {
    private val server = MockWebServer().apply { start() }

    @AfterTest
    fun tearDown() = server.close()

    private val glossJson = """{"glosses":[{"id":"casa","form":"casa","lemma":"casa","partOfSpeech":"noun","meaningInContext":"house"}]}"""

    /** Shape returned by z.ai's /api/v1/responses: a reasoning item, then a message with output_text. */
    private fun responseBody(text: String) = """
        {"id":"resp_1","object":"response","model":"glm-4.6","error":null,"output":[
          {"type":"reasoning","id":"rs_1","content":[{"type":"reasoning_text","text":"thinking..."}],"summary":[]},
          {"type":"message","id":"msg_1","role":"assistant","status":"completed",
           "content":[{"type":"output_text","annotations":[],"text":${Json.encodeToString(kotlinx.serialization.serializer<String>(), text)}}]}
        ]}
    """.trimIndent()

    private fun options() = GlossOptions(retry = RetryPolicy(sleep = {}))

    @Test
    fun `request shape and parsing`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body(responseBody(glossJson)).build())
        val g = OpenAiResponsesGlosser(server.url("/api/v1/").toString(), "key", "glm-4.6", options = options())
        val r = g.gloss("casa", "Mi casa.")
        assertEquals("house", assertIs<GlossResult.Success>(r).gloss.meaningInContext)

        val req = server.takeRequest()
        assertEquals("/api/v1/responses", req.url.encodedPath)
        assertEquals("Bearer key", req.headers["Authorization"])
        val body = Json.parseToJsonElement(req.body!!.utf8()).jsonObject
        assertEquals("glm-4.6", body["model"]!!.jsonPrimitive.content)
        assertTrue(body["instructions"]!!.jsonPrimitive.content.isNotBlank())
        assertTrue("Mi casa." in body["input"]!!.jsonPrimitive.content)
        assertEquals("low", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals("json_object", body["text"]!!.jsonObject["format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `fenced json in output_text is accepted`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body(responseBody("```json\n$glossJson\n```")).build())
        val r = OpenAiResponsesGlosser(server.url("/v1").toString(), null, "m", options = options()).gloss("casa", "Mi casa.")
        assertIs<GlossResult.Success>(r)
        assertTrue(server.takeRequest().headers["Authorization"] == null)
    }

    @Test
    fun `reply without a message item is a failure, not a crash`() = runTest {
        repeat(2) { server.enqueue(MockResponse.Builder().code(200).body("""{"output":[{"type":"reasoning","content":[]}]}""").build()) }
        val r = OpenAiResponsesGlosser(server.url("/v1").toString(), "k", "m", options = options()).gloss("casa", "Mi casa.")
        assertIs<GlossResult.Failure>(r)
    }

    @Test
    fun `factory builds it for the z_ai responses preset`() {
        val c = GlosserConfig(GlossProvider.ZAI_RESPONSES, apiKey = "k", model = "glm-4.6")
        assertEquals("https://api.z.ai/api/v1", c.effectiveBaseUrl)
        assertIs<OpenAiResponsesGlosser>(GlosserFactory.create(c))
        assertTrue(Json.parseToJsonElement("{}") is JsonObject)
    }
}
