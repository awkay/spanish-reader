package net.awkay.spanishreader.core.practice

import kotlinx.coroutines.test.runTest
import net.awkay.spanishreader.core.gloss.MalformedGlossResponseException
import net.awkay.spanishreader.core.gloss.TextCompleter
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VerbDrillTest {
    private val card = DrillCard("ir#1", "ir", "preterite", "NOSOTROS", "We went to the beach yesterday.", "Ayer ___ a la playa.", "fuimos")

    @Test
    fun `common verbs are distinct infinitives`() {
        val all = CommonVerbs.ALL.map { it.infinitive }
        assertEquals(all.distinct(), all)
        assertTrue(all.size >= 90)
        assertEquals("ser", all.first())
        assertTrue(all.all { Regex("^[a-záéíóúñü]+$").matches(it) })
    }

    @Test
    fun `situations are ten distinct tense and subject pairs, mostly the basics`() {
        repeat(30) { seed ->
            val s = Drill.situations(Random(seed))
            assertEquals(Drill.CARDS_PER_VERB, s.size)
            assertEquals(s.size, s.map { it.first.id to it.second }.distinct().size)
            assertEquals(3, s.count { it.first.id == "present" })
            assertTrue(s.all { it.second in it.first.subjects })
        }
    }

    @Test
    fun `cards are parsed from the reply, keeping only well-formed ones`() {
        val situations = listOf(VerbTenses.byId("preterite")!! to Subject.NOSOTROS, VerbTenses.byId("present")!! to Subject.YO,
            VerbTenses.byId("present-perfect")!! to Subject.TU)
        val reply = """```json
            {"cards":[{"n":1,"english":"We went to the beach yesterday.","spanish":"Ayer ____ a la playa.","answer":"fuimos.","also":["fuimos"]},
              {"n":2,"english":"I go.","spanish":"Voy.","answer":"voy"},
              {"n":3,"english":"You have eaten.","spanish":"Tú ___ mucho.","answer":" has  comido ","also":["habías comido"]}]}```"""
        val cards = Drill.parse(reply, "ir", situations)
        assertEquals(listOf("ir#1", "ir#3"), cards.map { it.id })
        assertEquals(card.copy(spanish = "Ayer ___ a la playa."), cards[0])
        assertEquals("has comido", cards[1].answer)
        assertEquals(listOf("habías comido"), cards[1].also)
        assertEquals("present perfect", cards[1].tenseLabel)
        assertEquals("tú (you, informal)", cards[1].subjectLabel)
        assertEquals("Ayer fuimos a la playa.", cards[0].filled)
        assertFailsWith<MalformedGlossResponseException> { Drill.parse("no cards", "ir", situations) }
    }

    @Test
    fun `helper verbs in front of the blank move into the answer`() {
        val perfect = card.copy(tense = "present-perfect", spanish = "Esta semana he ___ muy ocupado.", answer = "sido")
        assertEquals(perfect.copy(spanish = "Esta semana ___ muy ocupado.", answer = "he sido"), Drill.withHelpers(perfect))
        val start = card.copy(tense = "near-future", spanish = "Voy a  ___ maestro.", answer = "ser", also = listOf("ser"))
        assertEquals(start.copy(spanish = "___ maestro.", answer = "Voy a ser", also = listOf("voy a ser")), Drill.withHelpers(start))
        val after = card.copy(tense = "near-future", spanish = "Mañana ___ a ir al restaurante.", answer = "vas")
        assertEquals(after.copy(spanish = "Mañana ___ al restaurante.", answer = "vas a ir"), Drill.withHelpers(after))
        val notInf = card.copy(spanish = "Ayer ___ a la playa.") // "a la" isn't a + infinitive
        assertEquals(notInf, Drill.withHelpers(notInf))
        assertEquals(card, Drill.withHelpers(card))
        val chave = card.copy(spanish = "La chave ___ aquí.") // "he" inside a word isn't a helper
        assertEquals(chave, Drill.withHelpers(chave))
    }

    @Test
    fun `answers must be the exact form, but case, spacing and end punctuation don't matter`() {
        assertEquals(DrillVerdict.CORRECT, Drill.check(card, " Fuimos. "))
        assertEquals(DrillVerdict.CORRECT, Drill.check(card, "¡. fuimos !"))
        assertEquals(DrillVerdict.ACCENT, Drill.check(card.copy(answer = "comió"), "comio"))
        assertEquals(DrillVerdict.WRONG, Drill.check(card, "vamos"))
        assertEquals(DrillVerdict.WRONG, Drill.check(card, ""))
        assertEquals(DrillVerdict.CORRECT, Drill.check(card.copy(answer = "hubiera ido", also = listOf("hubiese ido")), "hubiese  ido"))
    }

    @Test
    fun `progress counts up, a miss takes one back, six learns a card`() {
        var p: DrillProgress? = null
        repeat(5) { p = Drill.record(p, "ir#1", true, 1) }
        assertFalse(Drill.isLearned(p))
        p = Drill.record(p, "ir#1", false, 2)
        assertEquals(4, p!!.correct)
        assertEquals(1, p!!.misses)
        repeat(2) { p = Drill.record(p, "ir#1", true, 3) }
        assertTrue(Drill.isLearned(p))
        assertEquals(0, Drill.record(null, "x", false, 1).correct)
        assertTrue(Drill.isLearned(DrillProgress("x", hidden = true)))
    }

    @Test
    fun `rounds take the least practiced open cards, and mastered verbs move the drill on`() {
        val cards = (1..4).map { card.copy(id = "ir#$it") }
        val progress = mapOf("ir#1" to DrillProgress("ir#1", correct = 6), "ir#2" to DrillProgress("ir#2", correct = 3),
            "ir#4" to DrillProgress("ir#4", hidden = true))
        assertEquals(listOf("ir#3", "ir#2"), Drill.round(cards, progress, Random(1)).map { it.id })
        val done = progress + ("ir#2" to DrillProgress("ir#2", correct = 6)) + ("ir#3" to DrillProgress("ir#3", correct = 7))
        assertEquals(setOf("ir#1", "ir#2", "ir#3"), Drill.round(cards, done, Random(1)).map { it.id }.toSet())
        assertTrue(Drill.mastered(cards, done))
        assertFalse(Drill.mastered(emptyList(), done))
        val sets = mapOf("ser" to listOf(card.copy(id = "ser#1", verb = "ser")))
        assertEquals("ser", Drill.currentVerb({ sets[it] }, emptyMap()).infinitive)
        assertEquals("estar", Drill.currentVerb({ sets[it] }, mapOf("ser#1" to DrillProgress("ser#1", correct = 6))).infinitive)
        assertEquals(listOf("he", "comido"), Drill.answerWords(card.copy(answer = "He comido")))
    }

    private val tenCards = """{"cards":[${(1..10).joinToString(",") { """{"n":$it,"english":"E$it","spanish":"S ___ $it","answer":"a$it"}""" }}]}"""

    private class Fake(vararg replies: String) : TextCompleter {
        val queue = ArrayDeque(replies.toList())
        val users = mutableListOf<String>()
        override suspend fun complete(system: String, user: String): String = queue.removeFirst().also { users += user }
    }

    @Test
    fun `the review fixes, drops and keeps cards`() {
        val cards = (1..3).map { card.copy(id = "ser#$it", verb = "ser", answer = "fue", spanish = "Él ___ en la reunión.") }
        val review = """{"cards":[{"n":1,"ok":true,"english":"He was a teacher.","spanish":"Él ___ maestro.","answer":"fue","also":[]},
            {"n":2,"ok":false},{"n":3,"ok":true}]}"""
        val out = Drill.applyReview(review, cards)
        assertEquals(listOf("ser#1", "ser#3"), out.map { it.id })
        assertEquals("Él ___ maestro.", out[0].spanish)
        assertEquals(cards[2], out[1])
        val user = Drill.reviewUser(CommonVerb("ser", "to be"), cards)
        assertTrue(user.startsWith("Check these cards:\n{\"verb\":\"ser\",\"verbMeaning\":\"to be\",\"cards\":[{\"n\":1,\"tense\":\"preterite\""))
        assertFailsWith<MalformedGlossResponseException> { Drill.applyReview("nope", cards) }
    }

    @Test
    fun `the writer retries an unparseable reply once, then has the cards reviewed`() = runTest {
        val writer = Fake("nothing", tenCards)
        val reviewer = Fake("""{"cards":[${(1..10).joinToString(",") { if (it == 4) """{"n":4,"ok":false}""" else """{"n":$it,"ok":true}""" }}]}""")
        val cards = DrillWriter(writer, reviewer, Random(2)).write(CommonVerb("ir", "to go"))
        assertEquals(9, cards.size)
        assertFalse("ir#4" in cards.map { it.id })
        assertTrue(writer.users[1].endsWith("Reply with valid JSON only."))
        assertTrue("\"situations\":[{\"n\":1," in writer.users[0])
        assertTrue(reviewer.users[0].startsWith("Check these cards:"))
        // A review that never parses keeps the cards as written; too few cards fail.
        assertEquals(10, DrillWriter(Fake(tenCards), Fake("x", "y"), Random(2)).write(CommonVerb("ir", "to go")).size)
        val rejectAll = """{"cards":[${(1..10).joinToString(",") { """{"n":$it,"ok":false}""" }}]}"""
        assertFailsWith<MalformedGlossResponseException> { DrillWriter(Fake(tenCards), Fake(rejectAll), Random(2)).write(CommonVerb("ir", "to go")) }
    }
}
