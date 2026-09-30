package net.awkay.spanishreader.core.gloss

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Hits a real OpenAI-compatible endpoint. Skipped unless LIVE_GLOSS_BASE_URL, LIVE_GLOSS_API_KEY and
 * LIVE_GLOSS_MODEL are set, e.g. z.ai: https://api.z.ai/api/coding/paas/v4 (Coding Plan), https://api.z.ai/api/paas/v4,
 * or the Anthropic-compatible https://api.z.ai/api/anthropic (uses the Anthropic client).
 */
class LiveGlosserTest {
    private val baseUrl = System.getenv("LIVE_GLOSS_BASE_URL").orEmpty()
    private val apiKey = System.getenv("LIVE_GLOSS_API_KEY").orEmpty()
    private val model = System.getenv("LIVE_GLOSS_MODEL").orEmpty()

    @Test
    fun `glosses a small batch against a live endpoint`() = runBlocking {
        assumeTrue(baseUrl.isNotEmpty() && apiKey.isNotEmpty() && model.isNotEmpty(), "live gloss env not set")
        val provider = when {
            "/anthropic" in baseUrl || "anthropic.com" in baseUrl -> GlossProvider.ANTHROPIC
            "z.ai/api/v1" in baseUrl -> GlossProvider.ZAI_RESPONSES
            "z.ai/api/coding" in baseUrl -> GlossProvider.ZAI_CODING
            "z.ai" in baseUrl -> GlossProvider.ZAI
            else -> GlossProvider.OPENAI_COMPATIBLE
        }
        val glosser = GlosserFactory.create(GlosserConfig(provider, baseUrl, apiKey, model))
        val requests = listOf(
            GlossRequest("banco", "Me senté en un banco del parque.", "1"),
            GlossRequest("dáselo", "Si Juan quiere el libro, dáselo mañana.", "2"),
            GlossRequest("echar de menos", "Voy a echar de menos a mi familia.", "3"),
        )
        val started = System.nanoTime()
        val results = glosser.gloss(requests)
        println("Live gloss took ${(System.nanoTime() - started) / 1_000_000} ms")
        results.forEach { println(it) }
        assertEquals(requests.map { it.id }, results.map { it.id })
        val banco = assertIs<GlossResult.Success>(results[0]).gloss
        assertTrue("bench" in banco.meaningInContext.lowercase() || "seat" in banco.meaningInContext.lowercase(), banco.meaningInContext)
        results.forEach { assertIs<GlossResult.Success>(it) }
    }
}
