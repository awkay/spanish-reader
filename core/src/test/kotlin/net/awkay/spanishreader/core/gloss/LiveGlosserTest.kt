package net.awkay.spanishreader.core.gloss

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Hits a real endpoint. Skipped unless LIVE_GLOSS_BASE_URL, LIVE_GLOSS_API_KEY and LIVE_GLOSS_MODEL are set, e.g.
 * z.ai: https://api.z.ai/api/v1 (Responses API, Coding Plan), https://api.z.ai/api/coding/paas/v4,
 * https://api.z.ai/api/paas/v4, or the Anthropic-compatible https://api.z.ai/api/anthropic.
 */
class LiveGlosserTest {
    private val baseUrl = System.getenv("LIVE_GLOSS_BASE_URL").orEmpty()
    private val apiKey = System.getenv("LIVE_GLOSS_API_KEY").orEmpty()
    private val model = System.getenv("LIVE_GLOSS_MODEL").orEmpty()

    private fun config(): GlosserConfig {
        assumeTrue(baseUrl.isNotEmpty() && apiKey.isNotEmpty() && model.isNotEmpty(), "live gloss env not set")
        val provider = when {
            "/anthropic" in baseUrl || "anthropic.com" in baseUrl -> GlossProvider.ANTHROPIC
            "z.ai/api/v1" in baseUrl -> GlossProvider.ZAI_RESPONSES
            "z.ai/api/coding" in baseUrl -> GlossProvider.ZAI_CODING
            "z.ai" in baseUrl -> GlossProvider.ZAI
            else -> GlossProvider.OPENAI_COMPATIBLE
        }
        return GlosserConfig(provider, baseUrl, apiKey, model)
    }

    @Test
    fun `glosses a batch with verb, clitic and roots detail`() = runBlocking {
        val glosser = GlosserFactory.create(config())
        val requests = listOf(
            GlossRequest("banco", "Me senté en un banco del parque.", "1"),
            GlossRequest("dáselo", "Si Juan quiere el libro, dáselo mañana.", "2"),
            GlossRequest("echar", "Voy a echar de menos a mi familia.", "3"),
            GlossRequest("observándome", "Ella estaba observándome desde la puerta.", "4"),
            GlossRequest("olvidó", "Se me olvidó la llave en casa.", "5"),
            GlossRequest("encaja", "Esa pieza no encaja en el rompecabezas.", "6"),
        )
        val started = System.nanoTime()
        val results = glosser.gloss(requests)
        println("Live gloss took ${(System.nanoTime() - started) / 1_000_000} ms")
        results.forEach { println(it) }
        assertEquals(requests.map { it.id }, results.map { it.id })
        val g = results.map { assertIs<GlossResult.Success>(it).gloss }
        assertTrue("bench" in g[0].meaningInContext.lowercase() || "seat" in g[0].meaningInContext.lowercase(), g[0].meaningInContext)
        assertEquals("dar", g[1].verb?.infinitive)
        assertEquals(setOf(CliticRole.INDIRECT_OBJECT, CliticRole.DIRECT_OBJECT), g[1].clitics.map { it.kind }.toSet())
        assertTrue(g[2].isIdiomOrPhrase, "echar de menos should be an idiom")
        assertEquals(CliticRole.DIRECT_OBJECT, g[3].clitics.single().kind)
        assertTrue(g[4].clitics.any { it.kind == CliticRole.INDIRECT_OBJECT }, "me in 'se me olvidó' is indirect")
        assertTrue(g[5].roots != null, "encaja should get roots")
    }

    @Test
    fun `finds idioms made of common words`() = runBlocking {
        val finder = GlosserFactory.createPhraseFinder(config())
        val sentences = listOf(
            "Sin embargo, no se dio cuenta de nada.",
            "A lo mejor llueve mañana.",
            "El perro come pan.",
        )
        val found = finder.findPhrases(sentences)
        found.forEachIndexed { i, p -> println("${sentences[i]} -> $p") }
        val first = found[0]!!.map { it.phrase.lowercase() }
        assertTrue(first.any { "sin embargo" in it } && first.any { "cuenta" in it }, first.toString())
        assertTrue(found[1]!!.any { "a lo mejor" in it.phrase.lowercase() })
        assertTrue(found[2]!!.isEmpty())
    }
}
