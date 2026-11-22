package net.awkay.spanishreader.core.practice

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import net.awkay.spanishreader.core.gloss.JsonExtractor
import net.awkay.spanishreader.core.gloss.MalformedGlossResponseException
import net.awkay.spanishreader.core.gloss.TextCompleter
import java.text.Normalizer
import kotlin.random.Random

/*
 * The say-it / listen pool (ported to web/src/core/phrases.ts). After seeing the answer the learner says whether they
 * got it: right counts one up, wrong one down (not below 0). A sentence that reaches [PhrasePool.TO_RETIRE] is retired
 * in that mode, and the next sentence takes its place, so each deck and mode keeps [PhrasePool.SIZE] sentences in
 * play. Sentences come from the hand-written deck first (its first SIZE in a fixed mixed order), then from AI-written
 * ones shared by everyone through the server (ids `deck~n`, in order), then the rest of the hand-written deck (used
 * only while no AI sentences are available). The scores are each device's own and go with sync.
 */

/** The learner's score for one sentence in one mode ([PhraseMode.id]). */
@Serializable
data class PhraseProgress(
    val mode: String,
    val cardId: String,
    val score: Int = 0,
    val right: Int = 0,
    val wrong: Int = 0,
    val lastMillis: Long = 0,
) {
    val key: String get() = PhrasePool.key(mode, cardId)
}

object PhrasePool {
    const val SIZE = 50
    const val TO_RETIRE = 3
    /** Sentences the AI writes at a time. */
    const val BATCH = 10

    fun key(mode: String, cardId: String) = "$mode:$cardId"

    fun record(p: PhraseProgress?, mode: String, cardId: String, right: Boolean, now: Long): PhraseProgress {
        val cur = p ?: PhraseProgress(mode, cardId)
        return if (right) cur.copy(score = cur.score + 1, right = cur.right + 1, lastMillis = now)
        else cur.copy(score = maxOf(0, cur.score - 1), wrong = cur.wrong + 1, lastMillis = now)
    }

    fun retired(p: PhraseProgress?): Boolean = p != null && p.score >= TO_RETIRE

    /** The hand-written cards in a fixed order that mixes the deck (the same on every device and in the web app). */
    fun builtInOrder(deck: PhraseDeck): List<PhraseCard> = deck.cards.sortedWith(compareBy({ fnv(it.id) }, { it.id }))

    /** Where sentences come from, in order: the first [SIZE] hand-written, the AI-written, the other hand-written. */
    fun supply(deck: PhraseDeck, generated: List<PhraseCard>): List<PhraseCard> {
        val builtIn = builtInOrder(deck)
        return builtIn.take(SIZE) + generated + builtIn.drop(SIZE)
    }

    /** The sentences in play for [mode]: the first [SIZE] of the supply not retired there. */
    fun pool(deck: PhraseDeck, mode: PhraseMode, generated: List<PhraseCard>, progress: Map<String, PhraseProgress>): List<PhraseCard> =
        supply(deck, generated).filter { !retired(progress[key(mode.id, it.id)]) }.take(SIZE)

    /**
     * How many more AI sentences [deck] needs so every mode has [SIZE] in play without falling back on the reserve of
     * hand-written ones.
     */
    fun shortfall(deck: PhraseDeck, generated: List<PhraseCard>, progress: Map<String, PhraseProgress>): Int {
        val main = builtInOrder(deck).take(SIZE) + generated
        return PhraseMode.entries.maxOf { mode -> maxOf(0, SIZE - main.count { !retired(progress[key(mode.id, it.id)]) }) }
    }

    /** A practice session: the whole pool, least practiced first (ties shuffled). */
    fun session(pool: List<PhraseCard>, mode: PhraseMode, progress: Map<String, PhraseProgress>, random: Random = Random): List<PhraseCard> =
        pool.shuffled(random).sortedBy { progress[key(mode.id, it.id)]?.score ?: 0 }

    /** FNV-1a over the UTF-16 code units, unsigned. */
    internal fun fnv(s: String): Long {
        var h = 0x811c9dc5.toInt()
        for (c in s) {
            h = h xor c.code
            h *= 16777619
        }
        return h.toLong() and 0xffffffffL
    }
}

/** Writes new sentences for a deck: one card per target, then a review by the stronger model (as for drill cards). */
object PhraseWriting {
    /** The question words a batch picks from; the common ones appear more than once. */
    val QUESTION_WORDS: List<String> = listOf(
        "Qué", "Qué", "Qué", "Cómo", "Cómo", "Dónde", "Dónde", "Cuándo", "Cuándo", "Quién", "Quiénes", "Cuál", "Cuáles",
        "Por qué", "Por qué", "Para qué", "Cuánto", "Cuánta", "Cuántos", "Cuántas", "A qué hora", "De dónde", "Adónde",
        "De quién", "Con quién", "De qué", "Qué tal",
    )

    /** Everyday tenses for new verb sentences; the present comes up most. */
    val VERB_TENSES: List<String> = listOf(
        "present", "present", "present", "preterite", "preterite", "imperfect", "near-future", "present-perfect",
        "imperative", "negative-imperative", "present-progressive", "future",
    )

    /** Common verbs a verb target picks from, besides those of the hand-written deck. */
    const val VERB_COUNT = 40

    sealed interface Target {
        data class Question(val words: String) : Target
        data class Verb(val verb: String, val tense: String) : Target
    }

    /** [PhrasePool.BATCH] targets: question words, or verb + tense (half from the hand-written deck's pairs). */
    fun targets(deck: PhraseDeck, random: Random = Random): List<Target> = when (deck) {
        PhraseDeck.QUESTIONS -> List(PhrasePool.BATCH) { Target.Question(QUESTION_WORDS.random(random)) }
        PhraseDeck.VERBS -> {
            val known = PhraseDecks.VERBS.mapNotNull { c -> c.verb?.let { v -> c.tense?.let { Target.Verb(v, it) } } }.distinct()
            val verbs = (PhraseDecks.VERBS.mapNotNull { it.verb } + CommonVerbs.ALL.take(VERB_COUNT).map { it.infinitive }).distinct()
            val out = LinkedHashSet<Target>()
            out += known.shuffled(random).take(PhrasePool.BATCH / 2)
            var guard = 0
            while (out.size < PhrasePool.BATCH && guard++ < 1000) out += Target.Verb(verbs.random(random), VERB_TENSES.random(random))
            out.toList().shuffled(random)
        }
    }

    val SYSTEM: String = """
        You write fill-in-the-blank practice sentences for an English speaker learning everyday Latin American Spanish.
        The learner reads the English sentence, says the Spanish out loud, then checks it against the Spanish sentence,
        in which the target words are left blank. You get a numbered list of targets: question words, or a verb with a
        tense or construction. For each target write one card:
        - "english": a short, natural English sentence (3 to 12 words) from everyday life (shopping, food, travel,
          directions, work, family, home, health, plans, the weather) whose natural Spanish translation uses the target.
          When "you" could be formal or plural, add a hint in parentheses, like "(formal)" or "(to a group)".
        - "spanish": the natural Latin American Spanish translation with the target words replaced by ___ (three
          underscores), exactly once. For question words the blank holds the whole question phrase with its
          preposition ("De dónde", "A qué hora", "Con quién"), capitalized right after ¿. For a verb the blank holds the
          whole verb form, helper verbs included ("he comido", "voy a pagar", "estoy buscando"); object and reflexive
          pronouns stay outside the blank, before the verb ("Me ___ temprano."); avoid forms where pronouns attach to
          the verb, such as affirmative commands of reflexive verbs.
        - "answer": the exact words that fill the blank, with correct accents.
        - "note": a short tip in English when it helps the learner (e.g. "before a noun, \"which\" is usually qué",
          "negative tú commands use the subjunctive"); otherwise leave it out.
        Use ustedes, never vosotros, and common Mexican/US Spanish vocabulary. Make every sentence different from the
        others and from the sentences in "avoid", in situation as well as in wording.
        Respond with ONLY a JSON object, no markdown fences and no commentary, shaped exactly like:
        {"cards":[{"n":1,"english":"...","spanish":"...","answer":"...","note":"..."}]}
        Include exactly one card per target, in order.
    """.trimIndent()

    val REVIEW_SYSTEM: String = """
        You check fill-in-the-blank practice sentences written for an English speaker learning Latin American Spanish.
        Each card has a target (question words, or a verb with a tense or construction), an English sentence, a Spanish
        sentence with ___ where the target goes, the answer that fills the blank, and maybe a tip ("note").
        A card is right only if all of these hold:
        - the Spanish, with the answer in the blank, is a natural and grammatical Latin American Spanish translation of
          the English (check every word, agreement, ser versus estar, por versus para);
        - the answer is the target: those question words, or that verb in that tense or construction, in the person the
          English needs, and a native speaker would say it that way here;
        - the blank holds only the target words (helper verbs included), with pronouns outside it;
        - the note, if any, is true and fits this sentence.
        Fix a card that is wrong when you can while keeping its target (edit the English, the Spanish, the answer or the
        note, or drop the note); otherwise set "ok" to false. Keep correct cards exactly as they are.
        Respond with ONLY a JSON object, no markdown fences and no commentary, shaped exactly like:
        {"cards":[{"n":1,"ok":true,"english":"...","spanish":"...","answer":"...","note":"..."}]}
        Include every card, by its "n".
    """.trimIndent()

    private const val RETRY = "\nYour previous reply could not be parsed. Reply with valid JSON only."

    private fun targetJson(o: kotlinx.serialization.json.JsonObjectBuilder, t: Target) {
        when (t) {
            is Target.Question -> o.put("questionWords", t.words)
            is Target.Verb -> {
                o.put("verb", t.verb)
                val tense = VerbTenses.byId(t.tense)
                o.put("tense", tense?.label ?: t.tense)
                if (tense != null) o.put("tenseNote", tense.instruction)
            }
        }
    }

    fun user(deck: PhraseDeck, targets: List<Target>, avoid: List<String>, isRetry: Boolean = false): String {
        val o = buildJsonObject {
            put("deck", deck.title)
            putJsonArray("targets") {
                targets.forEachIndexed { i, t -> addJsonObject { put("n", i + 1); targetJson(this, t) } }
            }
            putJsonArray("avoid") { avoid.forEach { add(JsonPrimitive(it)) } }
        }
        return "Write the cards:\n$o" + if (isRetry) RETRY else ""
    }

    /** A written card before it gets its id: [n] is its target's number. */
    data class Draft(val n: Int, val english: String, val spanish: String, val answer: String, val note: String?)

    fun reviewUser(deck: PhraseDeck, targets: List<Target>, drafts: List<Draft>, isRetry: Boolean = false): String {
        val o = buildJsonObject {
            put("deck", deck.title)
            putJsonArray("cards") {
                for (d in drafts) addJsonObject {
                    put("n", d.n)
                    targets.getOrNull(d.n - 1)?.let { targetJson(this, it) }
                    put("english", d.english)
                    put("spanish", d.spanish)
                    put("answer", d.answer)
                    if (d.note != null) put("note", d.note)
                }
            }
        }
        return "Check these cards:\n$o" + if (isRetry) RETRY else ""
    }

    /** The well-formed cards in [text]: one blank, an answer that is the target, an English sentence not in [avoid]. */
    fun parse(text: String, targets: List<Target>, avoid: Collection<String>): List<Draft> = cards(text, targets, avoid) { o, i ->
        Draft(o.int("n") ?: (i + 1), o.str("english") ?: return@cards null, o.str("spanish") ?: return@cards null,
            o.str("answer") ?: return@cards null, o.str("note"))
    }.ifEmpty { throw MalformedGlossResponseException("No phrase cards in reply: ${text.take(200)}") }

    /** [drafts] after the review in [text]: corrected where the reviewer fixed them, without those it rejected or left out. */
    fun applyReview(text: String, targets: List<Target>, drafts: List<Draft>, avoid: Collection<String>): List<Draft> {
        val byN = drafts.associateBy { it.n }
        for (el in JsonExtractor.candidates(text)) {
            val arr = (el as? JsonObject)?.get("cards") as? JsonArray ?: (el as? JsonArray) ?: continue
            if (arr.isEmpty()) continue
            var matched = 0
            val reviewed = arr.mapNotNull { item ->
                val o = item as? JsonObject ?: return@mapNotNull null
                val d = o.int("n")?.let(byN::get) ?: return@mapNotNull null
                matched++
                if ((o["ok"] as? JsonPrimitive)?.content == "false") return@mapNotNull null
                val note = if ("note" in o) o.str("note") else d.note
                val edit = d.copy(english = o.str("english") ?: d.english, spanish = o.str("spanish") ?: d.spanish, answer = o.str("answer") ?: d.answer, note = note)
                // An edit that lost the blank (the answer written in) is unusable; only "ok": false rejects a card.
                if (edit.spanish.replace(Regex("_{2,}"), Drill.BLANK).split(Drill.BLANK).size != 2) d else edit
            }
            // A review naming none of the drafts says nothing about them: unparseable, so the drafts are kept.
            if (matched == 0) continue
            return valid(reviewed, targets, avoid)
        }
        throw MalformedGlossResponseException("No review in reply: ${text.take(200)}")
    }

    private fun cards(text: String, targets: List<Target>, avoid: Collection<String>, read: (JsonObject, Int) -> Draft?): List<Draft> {
        for (el in JsonExtractor.candidates(text)) {
            val arr = (el as? JsonObject)?.get("cards") as? JsonArray ?: (el as? JsonArray) ?: continue
            val drafts = valid(arr.mapIndexedNotNull { i, item -> (item as? JsonObject)?.let { read(it, i) } }, targets, avoid)
            if (drafts.isNotEmpty()) return drafts
        }
        return emptyList()
    }

    private fun valid(drafts: List<Draft>, targets: List<Target>, avoid: Collection<String>): List<Draft> {
        val seen = avoid.map(::sameKey).toHashSet()
        return drafts.mapNotNull { raw ->
            val target = targets.getOrNull(raw.n - 1) ?: return@mapNotNull null
            var d = raw.copy(spanish = raw.spanish.replace(Regex("_{2,}"), Drill.BLANK), answer = clean(raw.answer),
                note = raw.note?.trim()?.takeIf { it.isNotEmpty() })
            if (d.spanish.split(Drill.BLANK).size != 2 || d.answer.isEmpty()) return@mapNotNull null
            when (target) {
                is Target.Question -> if (!d.answer.equals(target.words, ignoreCase = true)) return@mapNotNull null
                is Target.Verb -> {
                    val fixed = Drill.withHelpers(DrillCard("x#${d.n}", target.verb, target.tense, "", d.english, d.spanish, d.answer))
                    d = d.copy(spanish = fixed.spanish, answer = fixed.answer)
                }
            }
            if (!seen.add(sameKey(d.english))) return@mapNotNull null
            d
        }.distinctBy { it.n }.sortedBy { it.n }
    }

    /** New cards get ids `deck~n`, numbered on from the [already] AI-written ones. */
    fun toCards(deck: PhraseDeck, targets: List<Target>, drafts: List<Draft>, already: Int): List<PhraseCard> =
        drafts.mapIndexed { i, d ->
            val t = targets.getOrNull(d.n - 1) as? Target.Verb
            PhraseCard("${deck.id}~${already + i + 1}", d.english, d.spanish, d.answer, t?.verb, t?.tense, d.note)
        }

    /** Same English sentence, ignoring case, spacing and punctuation. */
    fun sameKey(english: String): String = Normalizer.normalize(english, Normalizer.Form.NFC).lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private val ENDS = Regex("^[\\s.,!?¡¿;:\"]+|[\\s.,!?¡¿;:\"]+$")

    private fun clean(s: String) = Normalizer.normalize(s, Normalizer.Form.NFC).replace(ENDS, "").replace(Regex("\\s+"), " ")

    private fun JsonObject.str(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.int(key: String): Int? = (get(key) as? JsonPrimitive)?.content?.toIntOrNull()
}

/**
 * Writes [PhrasePool.BATCH] new sentences for a deck with the AI, then has [reviewer] (the stronger model where there
 * is one) check and fix them, since they are shared. Each step retries once on an unparseable reply; a review that
 * never parses keeps the cards as written. [avoid]: the English of the deck's sentences so far.
 */
class PhraseWriter(private val completer: TextCompleter, private val reviewer: TextCompleter = completer, private val random: Random = Random) {
    suspend fun write(deck: PhraseDeck, avoid: List<String>, already: Int): List<PhraseCard> {
        val targets = PhraseWriting.targets(deck, random)
        val written = twice { retry -> PhraseWriting.parse(completer.complete(PhraseWriting.SYSTEM, PhraseWriting.user(deck, targets, avoid, retry)), targets, avoid) }
        val reviewed = try {
            twice { retry ->
                PhraseWriting.applyReview(reviewer.complete(PhraseWriting.REVIEW_SYSTEM, PhraseWriting.reviewUser(deck, targets, written, retry)), targets, written, avoid)
            }
        } catch (_: MalformedGlossResponseException) {
            written
        }
        if (reviewed.isEmpty()) throw MalformedGlossResponseException("No usable sentences were written")
        return PhraseWriting.toCards(deck, targets, reviewed, already)
    }

    private suspend fun <T> twice(block: suspend (Boolean) -> T): T = try {
        block(false)
    } catch (e: CancellationException) {
        throw e
    } catch (_: MalformedGlossResponseException) {
        block(true)
    }
}
