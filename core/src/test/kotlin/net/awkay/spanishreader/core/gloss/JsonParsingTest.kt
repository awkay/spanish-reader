package net.awkay.spanishreader.core.gloss

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonParsingTest {
    private val glossJson =
        """{"id":"a","form":"dámelo","lemma":"dar","partOfSpeech":"verb","meaningInContext":"give it to me","grammarNote":"da + me + lo","otherMeanings":["to hand"],"isIdiomOrPhrase":false,"phrase":null}"""

    @Test
    fun `extracts from fenced block with prose`() {
        val text = "Sure! Here you go:\n```json\n{\"glosses\": [$glossJson]}\n```\nHope that helps {not json}."
        val obj = assertIs<JsonObject>(JsonExtractor.extract(text))
        assertTrue("glosses" in obj)
    }

    @Test
    fun `extracts first object or array from raw prose`() {
        assertEquals("1", (JsonExtractor.extract("Answer: {\"x\": 1} and {\"y\": 2}") as JsonObject)["x"]!!.jsonPrimitive.content)
        assertIs<JsonArray>(JsonExtractor.extract("list -> [1, 2, 3] done"))
    }

    @Test
    fun `skips braces that are not json and handles braces inside strings`() {
        val el = JsonExtractor.extract("thinking {about it} ... {\"note\": \"a } brace\", \"n\": 2}")
        assertEquals("a } brace", (el as JsonObject)["note"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tolerates trailing commas and returns null when nothing parses`() {
        assertIs<JsonObject>(JsonExtractor.extract("{\"a\": [1, 2,],}"))
        assertNull(JsonExtractor.extract("no json here { unterminated"))
    }

    @Test
    fun `parser accepts wrapped array, bare array, single object and snake case`() {
        val reqs = listOf(GlossRequest("dámelo", "¡Dámelo ya!", id = "a"))
        val expected = Gloss("dámelo", "dar", "verb", "give it to me", "da + me + lo", listOf("to hand"), false, null)
        assertEquals(mapOf("a" to expected), GlossParser.parse("""{"glosses":[$glossJson]}""", reqs))
        assertEquals(mapOf("a" to expected), GlossParser.parse("[$glossJson]", reqs))
        assertEquals(mapOf("a" to expected), GlossParser.parse(glossJson.replace("\"id\":\"a\",", ""), reqs))
        val snake = """{"form":"dámelo","lemma":"dar","part_of_speech":"verb","meaning_in_context":"give it to me"}"""
        assertEquals("give it to me", GlossParser.parse(snake, reqs).getValue("a").meaningInContext)
    }

    @Test
    fun `parser matches by id, then by form, and drops invalid items`() {
        val reqs = listOf(GlossRequest("sí", "Sí, claro.", "1"), GlossRequest("si", "Si puedes, ven.", "2"), GlossRequest("ven", "Si puedes, ven.", "3"))
        val text = """
            {"glosses":[
              {"id":2,"form":"si","lemma":"si","partOfSpeech":"conjunction","meaningInContext":"if"},
              {"form":"sí","lemma":"sí","partOfSpeech":"adverb","meaningInContext":"yes"},
              {"id":"3","form":"ven","lemma":"venir","partOfSpeech":"verb","meaningInContext":"   "}
            ]}
        """
        val parsed = GlossParser.parse(text, reqs)
        assertEquals(setOf("1", "2"), parsed.keys)
        assertEquals("yes", parsed.getValue("1").meaningInContext)
        assertEquals("if", parsed.getValue("2").meaningInContext)
    }

    @Test
    fun `parser throws on unusable output`() {
        assertFailsWith<MalformedGlossResponseException> {
            GlossParser.parse("I'm sorry, I can't help with that.", listOf(GlossRequest("a", "b")))
        }
        assertFailsWith<MalformedGlossResponseException> {
            GlossParser.parse("{\"unrelated\": true}", listOf(GlossRequest("a", "b")))
        }
    }

    @Test
    fun `retry after parsing`() {
        val now = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
        assertEquals(3000, parseRetryAfterMillis("3", now))
        assertEquals(10_000, parseRetryAfterMillis("Thu, 01 Jan 2026 00:00:10 GMT", now))
        assertNull(parseRetryAfterMillis(null, now))
        assertNull(parseRetryAfterMillis("soon", now))
    }

    @Test
    fun `prompt carries items and instructions`() {
        val user = GlossPrompt.user(listOf(GlossRequest("vos", "¿Vos querés mate?", "7")))
        assertTrue("\"sentence\":\"¿Vos querés mate?\"" in user)
        assertTrue("\"id\":\"7\"" in user)
        assertTrue("Latin American" in GlossPrompt.SYSTEM)
        assertTrue("THAT sentence" in GlossPrompt.SYSTEM)
        assertTrue("valid JSON only" in GlossPrompt.user(emptyList(), isRetry = true))
    }
}
