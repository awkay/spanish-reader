package net.awkay.spanishreader.core.practice

import kotlinx.coroutines.test.runTest
import net.awkay.spanishreader.core.backup.BackupCodec
import net.awkay.spanishreader.core.backup.BackupFile
import net.awkay.spanishreader.core.backup.Sync
import net.awkay.spanishreader.core.gloss.MalformedGlossResponseException
import net.awkay.spanishreader.core.gloss.TextCompleter
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhrasePoolTest {
    private val say = PhraseMode.SAY
    private fun gen(deck: PhraseDeck, n: Int) = (1..n).map { PhraseCard("${deck.id}~$it", "New $it", "¿___ $it?", "Qué") }
    private fun retire(vararg ids: String, mode: PhraseMode = say) =
        ids.associate { PhrasePool.key(mode.id, it) to PhraseProgress(mode.id, it, score = PhrasePool.TO_RETIRE) }

    @Test
    fun `right counts up, wrong counts down but not below zero, and three retires`() {
        var p = PhrasePool.record(null, "say", "q#1", right = true, now = 5)
        assertEquals(PhraseProgress("say", "q#1", score = 1, right = 1, wrong = 0, lastMillis = 5), p)
        p = PhrasePool.record(p, "say", "q#1", right = false, now = 6)
        p = PhrasePool.record(p, "say", "q#1", right = false, now = 7)
        assertEquals(0, p.score)
        assertEquals(2, p.wrong)
        repeat(2) { p = PhrasePool.record(p, "say", "q#1", right = true, now = 8) }
        assertFalse(PhrasePool.retired(p))
        p = PhrasePool.record(p, "say", "q#1", right = true, now = 9)
        assertTrue(PhrasePool.retired(p))
        assertEquals(4, p.right)
        assertFalse(PhrasePool.retired(null))
    }

    @Test
    fun `the hand-written order is fixed and mixes the deck`() {
        val order = PhrasePool.builtInOrder(PhraseDeck.QUESTIONS)
        assertEquals(PhraseDeck.QUESTIONS.cards.toSet(), order.toSet())
        assertEquals(order, PhrasePool.builtInOrder(PhraseDeck.QUESTIONS))
        // Not the deck order (which groups the cards by question word): the first 50 cover most words.
        assertNotEquals(PhraseDeck.QUESTIONS.cards.take(10), order.take(10))
        assertTrue(order.take(PhrasePool.SIZE).map { it.answer }.distinct().size >= 15)
        // Pinned values, mirrored by the web port's test.
        assertEquals(2166136261L, PhrasePool.fnv(""))
        assertEquals(3826002220L, PhrasePool.fnv("a"))
    }

    @Test
    fun `a retired sentence is replaced by the next AI-written one, then by the hand-written reserve`() {
        val deck = PhraseDeck.QUESTIONS
        val order = PhrasePool.builtInOrder(deck)
        assertEquals(order.take(50), PhrasePool.pool(deck, say, emptyList(), emptyMap()))

        val retired = retire(order[0].id, order[1].id)
        val generated = gen(deck, 1)
        val pool = PhrasePool.pool(deck, say, generated, retired)
        assertEquals(50, pool.size)
        assertEquals(order.subList(2, 50) + generated + order[50], pool)
        // Retired in "say" only: listening still has it.
        assertTrue(order[0] in PhrasePool.pool(deck, PhraseMode.LISTEN, generated, retired))
        assertEquals(1, PhrasePool.shortfall(deck, generated, retired))
        assertEquals(0, PhrasePool.shortfall(deck, gen(deck, 2), retired))
        assertEquals(0, PhrasePool.shortfall(deck, emptyList(), emptyMap()))
    }

    @Test
    fun `a session is the whole pool, least practiced first`() {
        val pool = PhrasePool.builtInOrder(PhraseDeck.VERBS).take(50)
        val progress = mapOf(PhrasePool.key("say", pool[0].id) to PhraseProgress("say", pool[0].id, score = 2))
        val s = PhrasePool.session(pool, say, progress, Random(1))
        assertEquals(pool.toSet(), s.toSet())
        assertEquals(pool[0], s.last())
    }

    @Test
    fun `verb targets mix the hand-written pairs with everyday verbs and tenses`() {
        val t = PhraseWriting.targets(PhraseDeck.VERBS, Random(3))
        assertEquals(PhrasePool.BATCH, t.size)
        assertEquals(t.size, t.distinct().size)
        assertTrue(t.all { it is PhraseWriting.Target.Verb && VerbTenses.byId(it.tense) != null })
        val q = PhraseWriting.targets(PhraseDeck.QUESTIONS, Random(3))
        assertTrue(q.all { it is PhraseWriting.Target.Question && it.words in PhraseWriting.QUESTION_WORDS })
    }

    @Test
    fun `the prompt lists the targets and the sentences to avoid`() {
        val user = PhraseWriting.user(PhraseDeck.VERBS, listOf(PhraseWriting.Target.Verb("tener", "present")), listOf("I'm hungry."))
        assertTrue("\"verb\":\"tener\"" in user)
        assertTrue("\"tense\":\"present\"" in user)
        assertTrue("\"avoid\":[\"I'm hungry.\"]" in user)
        assertFalse("could not be parsed" in user)
        assertTrue("could not be parsed" in PhraseWriting.user(PhraseDeck.QUESTIONS, listOf(PhraseWriting.Target.Question("Qué")), emptyList(), isRetry = true))
    }

    @Test
    fun `parsing keeps well-formed cards whose answer is the target`() {
        val targets = listOf(
            PhraseWriting.Target.Question("Cuánto"), PhraseWriting.Target.Question("Dónde"),
            PhraseWriting.Target.Question("Qué"), PhraseWriting.Target.Question("Cómo"), PhraseWriting.Target.Question("Quién"),
        )
        val text = """{"cards":[
            {"n":1,"english":"How much is the bread?","spanish":"¿__ cuesta el pan?","answer":"cuánto","note":" "},
            {"n":2,"english":"Where is the bank?","spanish":"¿___ está el banco?","answer":"Adónde"},
            {"n":3,"english":"What's this?","spanish":"¿Qué es esto?","answer":"Qué"},
            {"n":4,"english":"What is this?","spanish":"¿___ es esto?","answer":"Qué"},
            {"n":5,"english":"Who is it?","spanish":"¿___ es?","answer":"Quién.","note":"quién = who"}
        ]}"""
        val drafts = PhraseWriting.parse(text, targets, avoid = listOf("WHO is it"))
        // 2: not the target; 3: no blank; 4: not the target (Cómo); 5: already in the deck.
        assertEquals(listOf(PhraseWriting.Draft(1, "How much is the bread?", "¿___ cuesta el pan?", "cuánto", null)), drafts)
        assertFailsWith<MalformedGlossResponseException> { PhraseWriting.parse("no", targets, emptyList()) }
    }

    @Test
    fun `verb cards keep helper verbs inside the blank`() {
        val targets = listOf(PhraseWriting.Target.Verb("comer", "present-perfect"))
        val d = PhraseWriting.parse("""{"cards":[{"n":1,"english":"I've already eaten.","spanish":"Ya he ___.","answer":"comido"}]}""", targets, emptyList())
        assertEquals("Ya ___.", d.single().spanish)
        assertEquals("he comido", d.single().answer)
    }

    @Test
    fun `the review fixes, rejects or keeps cards`() {
        val targets = listOf(PhraseWriting.Target.Question("Qué"), PhraseWriting.Target.Question("Cuál"), PhraseWriting.Target.Question("Dónde"))
        val drafts = listOf(
            PhraseWriting.Draft(1, "What's up?", "¿___ pasa?", "Qué", "a tip"),
            PhraseWriting.Draft(2, "Which one?", "¿___?", "Cuál", null),
            PhraseWriting.Draft(3, "Where?", "¿___?", "Dónde", null),
        )
        val out = PhraseWriting.applyReview(
            """{"cards":[{"n":1,"ok":true,"english":"What's going on?"},{"n":2,"ok":false},{"n":3,"ok":true,"note":null}]}""",
            targets, drafts, emptyList(),
        )
        assertEquals(listOf(drafts[0].copy(english = "What's going on?"), drafts[2]), out)
        assertFailsWith<MalformedGlossResponseException> { PhraseWriting.applyReview("?", targets, drafts, emptyList()) }
    }

    @Test
    fun `a review that names no draft, or writes the answer into the blank, keeps the drafts`() {
        val targets = listOf(PhraseWriting.Target.Question("Qué"), PhraseWriting.Target.Question("Cuál"))
        val drafts = listOf(PhraseWriting.Draft(1, "What's up?", "¿___ pasa?", "Qué", null), PhraseWriting.Draft(2, "Which one?", "¿___ es?", "Cuál", null))
        assertFailsWith<MalformedGlossResponseException> {
            PhraseWriting.applyReview("""{"cards":[{"ok":true},{"ok":true}]}""", targets, drafts, emptyList())
        }
        val out = PhraseWriting.applyReview(
            """{"cards":[{"n":1,"ok":true,"spanish":"¿Qué pasa?"},{"n":2,"ok":true,"english":"Which is it?"}]}""", targets, drafts, emptyList(),
        )
        assertEquals(listOf(drafts[0], drafts[1].copy(english = "Which is it?")), out)
    }

    @Test
    fun `the writer numbers new cards after the shared ones and uses the reviewer`() = runTest {
        val writes = mutableListOf<String>()
        val writer = PhraseWriter(
            object : TextCompleter {
                override suspend fun complete(system: String, user: String): String {
                    writes += user
                    // A card for every target, whatever words were asked for.
                    val words = Regex("\"questionWords\":\"([^\"]+)\"").findAll(user).map { it.groupValues[1] }.toList()
                    return words.mapIndexed { i, w -> """{"n":${i + 1},"english":"Sentence ${i + 1}","spanish":"¿___ ${i + 1}?","answer":"$w"}""" }
                        .joinToString(",", "{\"cards\":[", "]}")
                }
            },
            object : TextCompleter {
                override suspend fun complete(system: String, user: String): String {
                    assertEquals(PhraseWriting.REVIEW_SYSTEM, system)
                    return """{"cards":[{"n":1,"ok":false},{"n":2,"ok":true}]}"""
                }
            },
            Random(7),
        )
        val cards = writer.write(PhraseDeck.QUESTIONS, avoid = listOf("Old one"), already = 20)
        assertEquals(listOf("questions~21"), cards.map { it.id })
        assertEquals("Sentence 2", cards.single().english)
        assertNull(cards.single().verb)
        assertTrue("Old one" in writes.single())
    }

    @Test
    fun `scores go with backups and sync, and states without them keep their fingerprint`() {
        val plain = BackupFile(exportedAtMillis = 1)
        assertFalse("phrases" in BackupCodec.encode(plain))
        val p = PhraseProgress("listen", "verbs~3", score = 2, right = 4, wrong = 2, lastMillis = 9)
        val with = plain.copy(phrases = listOf(p))
        assertEquals(with, BackupCodec.decode(BackupCodec.encode(with)))
        assertNotEquals(Sync.fingerprint(plain), Sync.fingerprint(with))
        assertTrue(Sync.hasLocalChanges(null, with))
        // The fingerprint of a state with no scores is what it was before scores existed.
        assertEquals(Sync.fingerprint(plain), Sync.fingerprint(BackupCodec.decode("""{"version":1,"exportedAtMillis":3,"lessons":[],"vocab":[],"drill":[]}""")))
    }
}
