package net.awkay.spanishreader.core.gloss

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import net.awkay.spanishreader.core.text.PhraseLocator
import net.awkay.spanishreader.core.text.Tokenizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RichGlossTest {
    private val req = GlossRequest("observándome", "Estaba observándome desde la puerta.", "1")

    private val rich = """
        {"glosses":[{"id":"1","form":"observándome","lemma":"observar","partOfSpeech":"verb","meaningInContext":"watching me",
          "verb":{"infinitive":"observar","tense":null,"mood":"gerund","person":null,"number":null,
                  "formation":"observ- + -ando; accent added on á because 'me' is attached","whyThisForm":"estar + gerund = ongoing action"},
          "clitics":[{"pronoun":"me","role":"direct object","refersTo":"me","note":null}],
          "roots":"Latin observare, 'to watch over' (English observe).",
          "grammarNote":null,"otherMeanings":["to notice"],"isIdiomOrPhrase":false,"phrase":null,"phraseMeaning":null}]}
    """.trimIndent()

    @Test
    fun `rich fields are parsed`() {
        val g = GlossParser.parse(rich, listOf(req)).getValue("1")
        assertEquals("observar", g.verb!!.infinitive)
        assertEquals("gerund", g.verb!!.summary)
        assertEquals(CliticRole.DIRECT_OBJECT, g.clitics.single().kind)
        assertTrue(g.roots!!.startsWith("Latin"))
        assertEquals(false, g.lacksDetail)
    }

    @Test
    fun `a malformed verb object doesn't lose the gloss`() {
        val broken = rich.replace(Regex("\"verb\":\\{[^}]*\\}"), "\"verb\":\"gerund of observar\"")
        val g = GlossParser.parse(broken, listOf(req)).getValue("1")
        assertNull(g.verb)
        assertEquals("watching me", g.meaningInContext)
        assertEquals("me", g.clitics.single().pronoun)
    }

    @Test
    fun `old cached glosses still decode`() {
        val old = """{"form":"hablo","lemma":"hablar","partOfSpeech":"verb","meaningInContext":"I speak","grammarNote":"present"}"""
        val g = Json { ignoreUnknownKeys = true }.decodeFromString(Gloss.serializer(), old)
        assertTrue(g.clitics.isEmpty())
        assertTrue(g.lacksDetail)
    }

    @Test
    fun `verb summary`() {
        assertEquals("preterite indicative · 3rd person singular", VerbForm("ir", "preterite", "indicative", "3rd", "singular").summary)
        assertEquals("present subjunctive · usted singular", VerbForm("ir", "present", "subjunctive", "usted", "singular").summary)
        assertEquals("", VerbForm("ir").summary)
    }

    @Test
    fun `clitic roles are normalized`() {
        val cases = mapOf(
            "direct object" to CliticRole.DIRECT_OBJECT, "Indirect Object" to CliticRole.INDIRECT_OBJECT,
            "indirect object (se replaces le)" to CliticRole.INDIRECT_OBJECT, "reflexive" to CliticRole.REFLEXIVE,
            "reciprocal" to CliticRole.RECIPROCAL, "pronominal verb" to CliticRole.PRONOMINAL,
            "accidental se" to CliticRole.ACCIDENTAL_SE, "impersonal se" to CliticRole.IMPERSONAL_SE,
            "passive se" to CliticRole.IMPERSONAL_SE, "weird" to CliticRole.OTHER,
        )
        cases.forEach { (role, kind) -> assertEquals(kind, CliticRole.of(role), role) }
    }

    private fun clitics(vararg p: String) = p.map { Clitic(it, "x") }

    @Test
    fun `attached clitics are split off the verb`() {
        assertEquals(CliticSplit("observándo", listOf("me")), CliticSplit.of("observándome", clitics("me")))
        assertEquals(CliticSplit("dá", listOf("se", "lo")), CliticSplit.of("dáselo", clitics("se", "lo")))
        assertEquals(CliticSplit("decír", listOf("se", "los")), CliticSplit.of("decírselos", clitics("se", "los")))
        assertEquals(CliticSplit("Di", listOf("me")), CliticSplit.of("Dime", clitics("me")))
        assertEquals(CliticSplit("levantar", listOf("se")), CliticSplit.of("levantarse", clitics("se")))
        // Pronouns before the verb, or a verb that merely ends in "la", are not attached.
        assertEquals(CliticSplit("olvidó", emptyList()), CliticSplit.of("olvidó", clitics("se", "me")))
        assertEquals(CliticSplit("habla", emptyList()), CliticSplit.of("habla", clitics("la")))
    }

    @Test
    fun `sentence replies are parsed and single-word phrases dropped`() {
        val text = """{"sentences":[{"id":"0","translation":"I will miss my family.","phrases":[{"phrase":"echar de menos","meaning":"to miss"},{"phrase":"casa","meaning":"house"}]},{"id":"1","phrases":[]}]}"""
        val parsed = GlossParser.parseSentences(text, listOf("0", "1", "2"))
        assertEquals(SentenceAnalysis("I will miss my family.", listOf(FoundPhrase("echar de menos", "to miss"))), parsed["0"])
        assertEquals(SentenceAnalysis(null, emptyList()), parsed["1"])
        assertNull(parsed["2"])
    }

    @Test
    fun `phrase locator finds contiguous and split expressions`() {
        val s1 = Tokenizer.tokenize("Voy a echar de menos a mi familia.").tokens
        assertEquals(listOf("echar", "de", "menos"), PhraseLocator.locate(s1, "echar de menos")!!.map { it.text })
        val s2 = Tokenizer.tokenize("Me echa mucho de menos.").tokens
        assertEquals(listOf("echa", "de", "menos"), PhraseLocator.locate(s2, "echa de menos")!!.map { it.text })
        val s3 = Tokenizer.tokenize("Sin embargo, no se dio cuenta.").tokens
        assertEquals(listOf("Sin", "embargo"), PhraseLocator.locate(s3, "sin embargo")!!.map { it.text })
        assertEquals(listOf("se", "dio", "cuenta"), PhraseLocator.locate(s3, "se dio cuenta")!!.map { it.text })
        assertNull(PhraseLocator.locate(s3, "a lo mejor"))
        // Accent-insensitive, tightest match.
        val s4 = Tokenizer.tokenize("De repente, de repente llegó.").tokens
        assertEquals(2, PhraseLocator.locate(s4, "de répente")!!.size)
    }

    @Test
    fun `improve requests carry the previous answer and an instruction`() {
        val previous = Gloss("banco", "banco", "noun", "bank")
        val prompt = GlossPrompt.user(listOf(GlossRequest("banco", "Me senté en el banco.", "1", previous = previous)))
        val items = Json.parseToJsonElement(prompt.substringAfter('\n').substringBefore('\n')).jsonObject["items"]!!.jsonArray
        assertEquals("bank", items[0].jsonObject["previousAnswer"]!!.jsonObject["meaningInContext"]!!.jsonPrimitive.content)
        assertTrue("\"previousAnswer\" given for an item unhelpful" in prompt)
        assertTrue("previousAnswer" !in GlossPrompt.user(listOf(GlossRequest("banco", "Me senté en el banco."))))
    }

    @Test
    fun `sentence analyzer batches sentences through the provider`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val reply = """{"sentences":[{"id":"0","translation":"However, he arrived.","phrases":[{"phrase":"sin embargo","meaning":"however"}]},{"id":"1","translation":"Hello.","phrases":[]}]}"""
            val body = """{"choices":[{"message":{"content":${Json.encodeToString(kotlinx.serialization.serializer<String>(), reply)}}}]}"""
            server.enqueue(MockResponse.Builder().code(200).body(body).build())
            val analyzer = GlosserFactory.createSentenceAnalyzer(GlosserConfig(GlossProvider.OPENAI_COMPATIBLE, server.url("/v1").toString(), model = "m"))
            val found = analyzer.analyze(listOf("Sin embargo, llegó.", "Hola."))
            assertEquals(
                listOf(SentenceAnalysis("However, he arrived.", listOf(FoundPhrase("sin embargo", "however"))), SentenceAnalysis("Hello.", emptyList())),
                found,
            )
            val sent = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
            val system = sent["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content
            assertTrue("idioms" in system && "translation" in system)
        }
    }

    @Test
    fun `sentence analyzer reports null for a failed batch`() = runTest {
        MockWebServer().use { server ->
            server.start()
            repeat(2) { server.enqueue(MockResponse.Builder().code(200).body("""{"choices":[{"message":{"content":"no json"}}]}""").build()) }
            val analyzer = GlosserFactory.createSentenceAnalyzer(GlosserConfig(GlossProvider.OPENAI_COMPATIBLE, server.url("/v1").toString(), model = "m"))
            assertEquals(listOf(null), analyzer.analyze(listOf("Hola.")))
        }
    }
}
