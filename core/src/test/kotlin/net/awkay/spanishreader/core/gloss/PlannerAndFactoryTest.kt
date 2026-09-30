package net.awkay.spanishreader.core.gloss

import kotlinx.coroutines.test.runTest
import net.awkay.spanishreader.core.text.Tokenizer
import net.awkay.spanishreader.core.vocab.WordStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlannerAndFactoryTest {
    @Test
    fun `planner skips known and ignored and caps sentences per form`() {
        val text = Tokenizer.tokenize("El gato come. El gato duerme. El gato corre. El gato salta. Juan ríe.")
        val plan = PreGlossPlanner.plan(
            text,
            mapOf("el" to WordStatus.KNOWN, "juan" to WordStatus.IGNORED, "come" to WordStatus.LEVEL_1),
            maxSentencesPerForm = 2,
        )
        val forms = plan.map { Tokenizer.normalize(it.form) }
        assertEquals(2, forms.count { it == "gato" })
        assertTrue("come" in forms && "ríe" in forms)
        assertTrue("el" !in forms && "juan" !in forms)
        assertEquals(plan.size, plan.map { it.id }.toSet().size)
        assertEquals("El gato duerme.", plan.filter { it.form == "gato" }[1].sentence)
    }

    @Test
    fun `planner does not repeat an identical sentence`() {
        val plan = PreGlossPlanner.plan(Tokenizer.tokenize("Hola hola.\nHola hola."), emptyMap())
        assertEquals(1, plan.size)
        assertEquals("Hola", plan.single().form)
    }

    @Test
    fun `config validation`() {
        assertEquals(
            listOf("Ollama Cloud: model name is required", "Ollama Cloud: API key is required"),
            GlosserConfig().problems(),
        )
        assertTrue(GlosserConfig(GlossProvider.OLLAMA_CLOUD, apiKey = "k", model = "kimi").isComplete)
        assertEquals("https://ollama.com/v1", GlosserConfig().effectiveBaseUrl)
        assertTrue(GlosserConfig(GlossProvider.OLLAMA_LOCAL, model = "m").problems().single().contains("base URL"))
        assertTrue(GlosserConfig(GlossProvider.OLLAMA_LOCAL, baseUrl = "10.0.0.2:11434", model = "m").problems().single().contains("http"))
        assertEquals("claude-haiku-4-5", GlosserConfig(GlossProvider.ANTHROPIC, apiKey = "k").effectiveModel)
        assertTrue(GlosserConfig(GlossProvider.ANTHROPIC, apiKey = "k").isComplete)
    }

    @Test
    fun `factory builds the right client and wraps a complete fallback`() {
        val ollama = GlosserConfig(GlossProvider.OLLAMA_LOCAL, baseUrl = "http://10.0.0.2:11434/v1", model = "m")
        assertIs<OpenAiCompatibleGlosser>(GlosserFactory.create(ollama))
        assertIs<AnthropicGlosser>(GlosserFactory.create(GlosserConfig(GlossProvider.ANTHROPIC, apiKey = "k")))
        assertIs<FallbackGlosser>(GlosserFactory.create(ollama, GlosserConfig(GlossProvider.ANTHROPIC, apiKey = "k")))
        assertIs<OpenAiCompatibleGlosser>(GlosserFactory.create(ollama, GlosserConfig(GlossProvider.ANTHROPIC)))
        assertFailsWith<IllegalArgumentException> { GlosserFactory.create(GlosserConfig()) }
    }

    private fun gloss(meaning: String) = Gloss("a", "a", "noun", meaning)

    private class FakeGlosser(val fail: Set<String>, val meaning: String) : Glosser {
        val seen = mutableListOf<String>()
        override suspend fun gloss(requests: List<GlossRequest>) = requests.map {
            seen += it.id
            if (it.id in fail) GlossResult.Failure(it.id, "$meaning failed") else GlossResult.Success(it.id, Gloss(it.form, it.form, "x", meaning))
        }
    }

    @Test
    fun `fallback only receives failures and results keep order`() = runTest {
        val primary = FakeGlosser(fail = setOf("b", "c"), meaning = "primary")
        val fallback = FakeGlosser(fail = setOf("c"), meaning = "fallback")
        val results = FallbackGlosser(primary, fallback).gloss(listOf("a", "b", "c").map { GlossRequest(it, "s", it) })
        assertEquals(listOf("b", "c"), fallback.seen)
        assertEquals(listOf("a", "b", "c"), results.map { it.id })
        assertEquals("primary", assertIs<GlossResult.Success>(results[0]).gloss.meaningInContext)
        assertEquals("fallback", assertIs<GlossResult.Success>(results[1]).gloss.meaningInContext)
        assertEquals("primary failed; fallback: fallback failed", assertIs<GlossResult.Failure>(results[2]).error)
    }

    @Test
    fun `fallback is not called when everything succeeds`() = runTest {
        val fallback = FakeGlosser(emptySet(), "fallback")
        FallbackGlosser(FakeGlosser(emptySet(), "p"), fallback).gloss(listOf(GlossRequest("a", "s")))
        assertTrue(fallback.seen.isEmpty())
    }
}
