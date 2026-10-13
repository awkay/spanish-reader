package net.awkay.spanishreader.core.practice

import kotlinx.coroutines.test.runTest
import net.awkay.spanishreader.core.gloss.Gloss
import net.awkay.spanishreader.core.gloss.MalformedGlossResponseException
import net.awkay.spanishreader.core.gloss.TextCompleter
import net.awkay.spanishreader.core.gloss.VerbForm
import net.awkay.spanishreader.core.vocab.VocabEntry
import net.awkay.spanishreader.core.vocab.WordStatus
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VerbPracticeTest {
    private fun entry(form: String, status: WordStatus, lemma: String? = null) = VocabEntry(form, lemma, status, firstSeenMillis = 0, lastSeenMillis = 0)

    private fun verbGloss(form: String, inf: String, meaning: String? = null) =
        Gloss(form, inf, "verb", "x", verb = VerbForm(inf), lemmaMeaning = meaning)

    private val entries = listOf(
        entry("prepárate", WordStatus.LEVEL_1, "preparar"),
        entry("preparamos", WordStatus.KNOWN, "preparar"),
        entry("vamos", WordStatus.FAMILIAR, "ir"),
        entry("hablan", WordStatus.NEW, "hablar"),
        entry("comer", WordStatus.IGNORED, "comer"),
        entry("casa", WordStatus.KNOWN, "casa"),
        entry("aeropuerto", WordStatus.LEVEL_1),
        entry("123", WordStatus.KNOWN),
    )
    private val glosses = mapOf(
        "prepárate" to listOf(verbGloss("prepárate", "prepararse")),
        "preparamos" to listOf(verbGloss("preparamos", "preparar", "to prepare")),
        "vamos" to listOf(verbGloss("vamos", "ir", "to go")),
        "hablan" to listOf(verbGloss("hablan", "hablar")),
        "comer" to listOf(verbGloss("comer", "comer")),
        "casa" to listOf(Gloss("casa", "casa", "noun", "house")),
    )

    @Test
    fun `verbs come from exposed vocabulary with a verb gloss, grouped by plain infinitive`() {
        val verbs = VerbPool.verbs(entries) { glosses[it].orEmpty() }
        assertEquals(listOf("ir", "preparar"), verbs.map { it.infinitive })
        val prep = verbs.single { it.infinitive == "preparar" }
        assertEquals("to prepare", prep.meaning)
        assertEquals(listOf("prepárate", "preparamos"), prep.forms)
    }

    @Test
    fun `known words are exposed lemmas, deduplicated, without the excluded verb`() {
        val words = VerbPool.knownWords(entries, exclude = setOf("ir", "vamos"), random = Random(1))
        assertEquals(setOf("preparar", "casa", "aeropuerto"), words.toSet())
        assertEquals(2, VerbPool.knownWords(entries, emptySet(), count = 2).size)
    }

    @Test
    fun `pick avoids recent verbs and respects the tense's subjects`() {
        val verbs = VerbPool.verbs(entries) { glosses[it].orEmpty() }
        repeat(20) { seed ->
            val spec = VerbPool.pick(verbs, entries, Random(seed), recent = listOf("ir"))!!
            assertEquals("preparar", spec.verb.infinitive)
            assertTrue(spec.subject in spec.tense.subjects)
            assertFalse("preparar" in spec.knownWords || "prepárate" in spec.knownWords)
        }
        val cmd = VerbPool.pick(verbs, entries, Random(3), tenses = listOf(VerbTenses.byId("imperative")!!))!!
        assertTrue(cmd.subject != Subject.YO)
        assertNull(VerbPool.pick(emptyList(), entries))
    }

    @Test
    fun `prompts carry the picked verb, tense, subject and words`() {
        val spec = PracticeSpec(PracticeVerb("ir", "to go", listOf("vamos")), VerbTenses.byId("past-near-future")!!, Subject.NOSOTROS, listOf("casa", "aeropuerto"))
        val user = PracticePrompt.exerciseUser(spec)
        assertTrue(user.startsWith("Write one exercise:\n{\"verb\":\"ir\",\"verbMeaning\":\"to go\""))
        assertTrue("\"subject\":\"nosotros (we)\"" in user && "\"knownWords\":[\"casa\",\"aeropuerto\"]" in user)
        val ex = VerbExercise("We were going to go to the airport.", "Íbamos a ir al aeropuerto.")
        val check = PracticePrompt.checkUser(spec, ex, listOf("Vamos al aeropuerto"), "Ibamos a ir al aeropuerto", isRetry = true)
        assertTrue("\"earlierTries\":[\"Vamos al aeropuerto\"],\"try\":2," in check)
        assertTrue(check.endsWith("Reply with valid JSON only."))
        assertTrue("\"howToHint\":\"give the correct verb form exactly and explain" in check)
        assertTrue("(\\\"íbamos a ir\\\")" in PracticePrompt.checkUser(spec, ex.copy(verbForm = "íbamos a ir"), listOf("a"), "b"))
        assertTrue("\"howToHint\":\"none\"" in PracticePrompt.checkUser(spec, ex, listOf("a", "b"), "c"))
    }

    @Test
    fun `replies are parsed leniently`() {
        val ex = PracticeParser.exercise("Sure!\n```json\n{\"english\":\"We were going to go.\",\"spanish\":\"Íbamos a ir.\",\"verb_form\":\"íbamos a ir\"}\n```")
        assertEquals("Íbamos a ir.", ex.spanish)
        assertEquals("íbamos a ir", ex.verbForm)
        assertNull(ex.verbMeaning)
        val wrong = PracticeParser.check("{\"correct\":\"false\",\"feedback\":\"Check the tense.\",\"hint\":\"Use the imperfect of ir.\"}")
        assertFalse(wrong.correct)
        assertEquals("Use the imperfect of ir.", wrong.hint)
        assertNull(PracticeParser.check("{\"correct\":true,\"feedback\":\"Good\",\"hint\":\"x\"}").hint)
        assertFailsWith<MalformedGlossResponseException> { PracticeParser.exercise("{\"english\":\"no spanish\"}") }
    }

    @Test
    fun `same answer ignores case, punctuation and spacing but not accents`() {
        assertTrue(sameAnswer("íbamos a ir al aeropuerto", "¡Íbamos a ir al  aeropuerto!"))
        assertFalse(sameAnswer("ibamos a ir al aeropuerto", "Íbamos a ir al aeropuerto."))
        assertFalse(sameAnswer("  ", ""))
    }

    private class FakeCompleter(vararg replies: String) : TextCompleter {
        val queue = ArrayDeque(replies.toList())
        val users = mutableListOf<String>()
        override suspend fun complete(system: String, user: String): String {
            users += user
            return queue.removeFirst()
        }
    }

    private val spec = PracticeSpec(PracticeVerb("ir", "to go", listOf("vamos")), VerbTenses.byId("preterite")!!, Subject.YO, listOf("casa"))
    private val ex = VerbExercise("I went home yesterday.", "Ayer fui a casa.")

    @Test
    fun `ai retries an unparseable reply once, and skips the call for the model answer`() = runTest {
        val fake = FakeCompleter("no json here", "{\"english\":\"I went home yesterday.\",\"spanish\":\"Ayer fui a casa.\"}")
        assertEquals(ex, VerbPracticeAi(fake).exercise(spec))
        assertTrue(fake.users[1].endsWith("Reply with valid JSON only."))

        val none = FakeCompleter()
        assertTrue(VerbPracticeAi(none).check(spec, ex, emptyList(), "ayer fui a casa").correct)
        assertTrue(none.users.isEmpty())
    }

    @Test
    fun `the last try gets no hint`() = runTest {
        val reply = "{\"correct\":false,\"feedback\":\"Wrong tense.\",\"hint\":\"fui\"}"
        assertEquals("fui", VerbPracticeAi(FakeCompleter(reply)).check(spec, ex, listOf("a"), "Voy a casa").hint)
        assertNull(VerbPracticeAi(FakeCompleter(reply)).check(spec, ex, listOf("a", "b"), "Voy a casa").hint)
        val echo = "{\"correct\":false,\"feedback\":\"No.\",\"hint\":\"Say where the problem is and which rule applies.\"}"
        assertNull(VerbPracticeAi(FakeCompleter(echo)).check(spec, ex, emptyList(), "Voy a casa").hint)
    }
}
