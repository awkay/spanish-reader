package net.awkay.spanishreader.core.chat

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.awkay.spanishreader.core.gloss.MalformedGlossResponseException
import net.awkay.spanishreader.core.gloss.TextCompleter
import net.awkay.spanishreader.core.text.ImportCleaner
import net.awkay.spanishreader.core.text.Tokenizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatTest {
    @Test
    fun `the partner prompt carries the topic, the recent turns and what the learner just said`() {
        val history = (1..30).map { ChatLine(if (it % 2 == 1) ChatRole.LEARNER else ChatRole.PARTNER, "turno $it") }
        val user = ChatPrompt.partnerUser("la comida", history, "Me gusta tacos")
        val o = Json.parseToJsonElement(user.substringBefore("\n\n")).jsonObject
        assertEquals("la comida", o["topic"]!!.jsonPrimitive.content)
        val conv = o["conversation"]!!.jsonArray
        assertEquals(ChatPrompt.HISTORY, conv.size)
        assertEquals("turno 7", conv.first().jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("you", conv.last().jsonObject["who"]!!.jsonPrimitive.content)
        assertEquals("Me gusta tacos", o["learnerJustSaid"]!!.jsonPrimitive.content)
        assertFalse("could not be read" in user)
        assertTrue("could not be read" in ChatPrompt.partnerUser("x", emptyList(), "hola", retry = true))
    }

    @Test
    fun `the correction prompt says whether the text came from speech`() {
        val o = Json.parseToJsonElement(ChatPrompt.correctUser("Yo fui al playa", "¿Qué hiciste?", voice = true)).jsonObject
        assertEquals("voice", o["source"]!!.jsonPrimitive.content)
        assertEquals("¿Qué hiciste?", o["partnerSaid"]!!.jsonPrimitive.content)
        val typed = Json.parseToJsonElement(ChatPrompt.correctUser("Hola", null, voice = false)).jsonObject
        assertEquals("typed", typed["source"]!!.jsonPrimitive.content)
        assertFalse("partnerSaid" in typed)
    }

    @Test
    fun `a reply is read sentence by sentence, from a fence or a bare array`() {
        val fenced = "Claro:\n```json\n{\"reply\": [{\"es\": \"¡Qué rico!\", \"en\": \"Yum!\"}, {\"es\": \"¿Qué pediste?\", \"en\": \"What did you order?\"}]}\n```"
        assertEquals(listOf(PartnerSentence("¡Qué rico!", "Yum!"), PartnerSentence("¿Qué pediste?", "What did you order?")), ChatParser.reply(fenced))
        assertEquals(listOf(PartnerSentence("Hola.", "")), ChatParser.reply("""[{"spanish": "Hola."}]"""))
        assertFailsWith<MalformedGlossResponseException> { ChatParser.reply("""{"reply": []}""") }
    }

    @Test
    fun `a correction keeps the learner's text when nothing changed`() {
        val c = ChatParser.correction(
            """{"english": "I went to the beach.", "corrected": "Fui a la playa.", "changes": [{"from": "al playa", "to": "a la playa", "why": "Playa is feminine."}], "ok": false}""",
            "Fui al playa.",
        )
        assertEquals(Correction("I went to the beach.", "Fui a la playa.", listOf(ChatChange("al playa", "a la playa", "Playa is feminine.")), false), c)
        // The model echoes the text with a change listed anyway: same text means nothing to fix.
        val same = ChatParser.correction("""{"english": "I like it.", "corrected": "Me  gusta.", "changes": [{"from": "x", "to": "y"}]}""", "Me gusta.")
        assertTrue(same.ok)
        assertEquals(emptyList(), same.changes)
        assertEquals("Me gusta.", same.corrected)
        // A missing corrected text falls back to what they said.
        assertEquals("Hola", ChatParser.correction("""{"english": "Hi"}""", "Hola").corrected)
        assertFailsWith<MalformedGlossResponseException> { ChatParser.correction("no idea", "Hola") }
    }

    @Test
    fun `reply and correction retry once on an unreadable answer`() = runTest {
        val answers = ArrayDeque(listOf("sorry", """{"reply": [{"es": "¡Hola!", "en": "Hi!"}]}"""))
        val prompts = mutableListOf<String>()
        val ai = ChatAi(object : TextCompleter {
            override suspend fun complete(system: String, user: String): String {
                prompts += user
                return answers.removeFirst()
            }
        })
        assertEquals(listOf(PartnerSentence("¡Hola!", "Hi!")), ai.reply("saludos", emptyList(), "Hola"))
        assertTrue("could not be read" in prompts[1])
    }

    @Test
    fun `corrections can use a different model than the partner`() = runTest {
        fun fixed(text: String) = object : TextCompleter {
            override suspend fun complete(system: String, user: String) = text
        }
        val ai = ChatAi(fixed("""{"reply": [{"es": "Sí.", "en": "Yes."}]}"""), fixed("""{"english": "Yes", "corrected": "Sí", "ok": true}"""))
        assertEquals("Yes", ai.correct("Si", null, voice = false).english)
        assertEquals("Sí.", ai.reply("t", emptyList(), "x").single().es)
    }

    @Test
    fun `the lesson is one paragraph per turn, and its sentences are the turns' sentences`() {
        val turns = listOf(
            "Fui a la playa con mi familia y comimos sándwiches.",
            "¡Qué bien! ¿Hacía calor? Me encanta la playa en verano.",
            "Sí, hacía mucho calor.",
        )
        val text = ChatTranscript.text(turns + "  ")
        assertEquals(turns.joinToString("\n\n"), text)
        // The import cleaner leaves it as is, so the sentences (and their audio and translations) match the lesson's.
        assertEquals(text, ImportCleaner.clean(text, joinWrappedLines = false))
        assertEquals(turns.flatMap(ChatTranscript::sentences), Tokenizer.tokenize(text).sentences())
        assertEquals(listOf("¡Qué bien!", "¿Hacía calor?", "Me encanta la playa en verano."), ChatTranscript.sentences(turns[1]))
    }

    @Test
    fun `translations pair each lesson sentence with the AI's English`() {
        val pieces = listOf(PartnerSentence("¡Qué bien!", "Great!"), PartnerSentence("¿Hacía  calor?", "Was it hot?"))
        assertEquals(listOf("¡Qué bien!" to "Great!", "¿Hacía calor?" to "Was it hot?"),
            ChatTranscript.translations("¡Qué bien! ¿Hacía calor? Otra frase.", pieces))
        assertEquals(listOf("Fui a la playa." to "I went to the beach."),
            ChatTranscript.translations("Fui a la playa.", emptyList(), whole = "I went to the beach."))
        // Two sentences and one English for the whole turn: nothing to pair.
        assertEquals(emptyList(), ChatTranscript.translations("Fui. Comí.", emptyList(), whole = "I went. I ate."))
    }

    @Test
    fun `the title names the topic and the day`() {
        assertEquals("Conversación: la comida mexicana · 17 nov", ChatTranscript.title("  la comida\n mexicana ", 17, 11))
    }
}
