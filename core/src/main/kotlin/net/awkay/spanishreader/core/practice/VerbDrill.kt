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
 * Lightning verb drill (ported to web/src/core/drill.ts). Verbs come from a fixed list of common verbs, in order.
 * Each verb gets one set of cards, written once by the AI and shared through the server: an English sentence, the
 * Spanish sentence with the verb left blank, and the verb form that fills it. The learner types only the verb form,
 * one try, checked on the device. A card is learned after [Drill.TO_LEARN] correct answers (a miss takes one back);
 * a verb is mastered when all its cards are learned, and the drill moves on to the next verb.
 */

data class CommonVerb(val infinitive: String, val meaning: String)

object CommonVerbs {
    /** Roughly by frequency, with a few everyday verbs moved up. */
    val ALL: List<CommonVerb> = listOf(
        "ser" to "to be", "estar" to "to be (state, location)", "tener" to "to have", "hacer" to "to do, to make",
        "ir" to "to go", "poder" to "to be able to, can", "decir" to "to say, to tell", "querer" to "to want",
        "ver" to "to see", "dar" to "to give", "saber" to "to know (facts)", "llegar" to "to arrive",
        "pasar" to "to pass, to happen", "deber" to "should, to owe", "poner" to "to put", "venir" to "to come",
        "hablar" to "to speak", "comer" to "to eat", "vivir" to "to live", "salir" to "to leave, to go out",
        "conocer" to "to know (people, places)", "pensar" to "to think", "creer" to "to believe", "llevar" to "to carry, to wear",
        "dejar" to "to leave, to let", "seguir" to "to follow, to continue", "encontrar" to "to find", "llamar" to "to call",
        "volver" to "to return", "tomar" to "to take, to drink", "trabajar" to "to work", "sentir" to "to feel",
        "esperar" to "to wait, to hope", "buscar" to "to look for", "entrar" to "to enter", "escribir" to "to write",
        "leer" to "to read", "entender" to "to understand", "pedir" to "to ask for, to order", "dormir" to "to sleep",
        "jugar" to "to play", "empezar" to "to begin", "recordar" to "to remember", "comprar" to "to buy",
        "beber" to "to drink", "abrir" to "to open", "necesitar" to "to need", "gustar" to "to please, to like",
        "mirar" to "to look at", "escuchar" to "to listen", "aprender" to "to learn", "ayudar" to "to help",
        "perder" to "to lose", "traer" to "to bring", "oír" to "to hear", "contar" to "to count, to tell",
        "terminar" to "to finish", "cambiar" to "to change", "pagar" to "to pay", "viajar" to "to travel",
        "caer" to "to fall", "morir" to "to die", "abrazar" to "to hug", "correr" to "to run",
        "estudiar" to "to study", "preguntar" to "to ask", "recibir" to "to receive", "usar" to "to use",
        "cocinar" to "to cook", "limpiar" to "to clean", "manejar" to "to drive", "caminar" to "to walk",
        "levantarse" to "to get up", "acostarse" to "to go to bed", "sentarse" to "to sit down", "vestirse" to "to get dressed",
        "lavar" to "to wash", "conducir" to "to drive", "servir" to "to serve", "repetir" to "to repeat",
        "cerrar" to "to close", "mostrar" to "to show", "olvidar" to "to forget",
        "ganar" to "to win, to earn", "nacer" to "to be born", "tocar" to "to touch, to play (music)", "enviar" to "to send",
        "explicar" to "to explain", "ofrecer" to "to offer", "conseguir" to "to get, to obtain", "elegir" to "to choose",
    ).distinctBy { it.first }.map { (inf, meaning) -> CommonVerb(inf, meaning) }

    fun byInfinitive(inf: String): CommonVerb? = ALL.firstOrNull { it.infinitive == inf }
}

/** One drill card. [spanish] has [Drill.BLANK] where [answer] goes; [also] are other accepted forms. */
@Serializable
data class DrillCard(
    val id: String,
    val verb: String,
    val tense: String,
    val subject: String,
    val english: String,
    val spanish: String,
    val answer: String,
    val also: List<String> = emptyList(),
) {
    val tenseLabel: String get() = VerbTenses.byId(tense)?.label ?: tense

    /** The situation number in the id (`ir#3` → 3). */
    val number: Int get() = id.substringAfterLast('#').toIntOrNull() ?: 0
    val subjectLabel: String get() = runCatching { Subject.valueOf(subject).label }.getOrDefault(subject)

    /** The Spanish sentence with the answer filled in. */
    val filled: String get() = spanish.replace(Drill.BLANK, answer)
}

/** The learner's record for one card. [hidden]: set aside as a bad card. */
@Serializable
data class DrillProgress(
    val cardId: String,
    val correct: Int = 0,
    val misses: Int = 0,
    val lastMillis: Long = 0,
    val hidden: Boolean = false,
)

enum class DrillVerdict { CORRECT, ACCENT, WRONG }

object Drill {
    const val TO_LEARN = 6
    const val CARDS_PER_VERB = 10
    const val BLANK = "___"

    /** What a set of cards covers: mostly the present, then the common past and future forms. */
    val SET: List<String> = listOf(
        "present", "present", "present", "preterite", "preterite", "imperfect", "near-future", "present-perfect", "future",
    )
    val EXTRA = listOf("conditional", "present-subjunctive", "imperative", "past-progressive", "present-progressive")

    /** [CARDS_PER_VERB] distinct (tense, subject) pairs: the core [SET] plus one less common form. */
    fun situations(random: Random = Random): List<Pair<VerbTense, Subject>> {
        val tenses = (SET + EXTRA.random(random)).mapNotNull(VerbTenses::byId)
        val used = HashSet<Pair<String, Subject>>()
        return tenses.map { t ->
            val subject = t.subjects.shuffled(random).firstOrNull { (t.id to it) !in used } ?: t.subjects.random(random)
            used += t.id to subject
            t to subject
        }
    }

    val SYSTEM: String = """
        You write fast verb-conjugation drill cards for an English-speaking learner of Latin American Spanish.
        You get one Spanish verb and a numbered list of situations, each a tense or construction and a subject.
        Only write sentences where a native speaker would use exactly this verb: for ser, never a sentence that needs
        estar (location, temporary states) or ir ("fue a la fiesta" is ir); for estar, never one that needs ser.
        For each situation write one card:
        - "english": a short, natural, everyday English sentence (4 to 12 words) whose Spanish translation needs the verb
          in exactly that tense or construction with that subject. Make the subject and the time clear in the English
          ("Yesterday...", "Every day...", "Tomorrow...").
        - "spanish": the natural Latin American Spanish translation with the verb form replaced by $BLANK (three
          underscores). The blank holds only the verb words, including helper verbs ("he $BLANK" is wrong, the blank is
          "he comido"). Object and reflexive pronouns stay outside the blank, before the verb ("Me $BLANK temprano." for
          "levanto"); avoid forms where pronouns attach to the verb, such as affirmative commands of reflexive verbs.
        - "answer": the exact verb words that fill the blank, with correct accents ("fuimos", "he comido", "voy a comer").
        - "also": other forms that are equally correct in the blank (e.g. "hubiese" for "hubiera"), usually [].
        Use ustedes, never vosotros. Keep the rest of each sentence simple and different from the other cards.
        Respond with ONLY a JSON object, no markdown fences and no commentary, shaped exactly like:
        {"cards":[{"n":1,"english":"...","spanish":"...","answer":"...","also":[]}]}
        Include exactly one card per situation, in order.
    """.trimIndent()

    fun user(verb: CommonVerb, situations: List<Pair<VerbTense, Subject>>, isRetry: Boolean = false): String {
        val o = buildJsonObject {
            put("verb", verb.infinitive)
            put("verbMeaning", verb.meaning)
            putJsonArray("situations") {
                situations.forEachIndexed { i, (t, s) ->
                    addJsonObject {
                        put("n", i + 1)
                        put("tense", t.label)
                        put("tenseNote", t.instruction)
                        put("subject", s.label)
                    }
                }
            }
        }
        return "Write the cards:\n$o" + if (isRetry) "\nYour previous reply could not be parsed. Reply with valid JSON only." else ""
    }

    val REVIEW_SYSTEM: String = """
        You check verb drill cards written for an English-speaking learner of Latin American Spanish. Each card has an
        English sentence, the verb, the intended tense or construction and subject, a Spanish sentence with $BLANK where
        the verb goes, and the answer that fills the blank.
        A card is right only if all of these hold:
        - the Spanish, with the answer in the blank, is a natural and grammatical Latin American Spanish translation of
          the English (check every word, agreement and the choice between ser and estar);
        - the answer is the given verb in the intended tense or construction and person, and a native speaker would use
          this verb here (a sentence that needs estar instead of ser, ir instead of ser, etc. is wrong);
        - the blank holds only the verb words (helper verbs included), with pronouns outside it;
        - the English makes that form the natural choice.
        Fix a card that is wrong when you can do it while keeping its verb, tense and subject (edit the English, the
        Spanish, the answer or "also"); otherwise set "ok" to false. Keep correct cards exactly as they are.
        Respond with ONLY a JSON object, no markdown fences and no commentary, shaped exactly like:
        {"cards":[{"n":1,"ok":true,"english":"...","spanish":"...","answer":"...","also":[]}]}
        Include every card, by its "n".
    """.trimIndent()

    fun reviewUser(verb: CommonVerb, cards: List<DrillCard>, isRetry: Boolean = false): String {
        val o = buildJsonObject {
            put("verb", verb.infinitive)
            put("verbMeaning", verb.meaning)
            putJsonArray("cards") {
                for (c in cards) addJsonObject {
                    put("n", c.number)
                    put("tense", c.tenseLabel)
                    put("subject", c.subjectLabel)
                    put("english", c.english)
                    put("spanish", c.spanish)
                    put("answer", c.answer)
                    putJsonArray("also") { c.also.forEach { add(JsonPrimitive(it)) } }
                }
            }
        }
        return "Check these cards:\n$o" + if (isRetry) "\nYour previous reply could not be parsed. Reply with valid JSON only." else ""
    }

    /**
     * A helper verb left in front of the blank ("He ___ ..." with "sido") goes into the answer ("he sido"), so the blank
     * always holds the whole verb form.
     */
    fun withHelpers(card: DrillCard): DrillCard = helperAfter(helperBefore(card))

    /** "Mañana ___ a ir al cine." with "vas": the "a ir" that follows belongs to the verb form too. */
    private fun helperAfter(card: DrillCard): DrillCard {
        if (' ' in card.answer) return card
        val after = card.spanish.substringAfter(BLANK)
        val m = A_INFINITIVE.find(after) ?: return card
        val tail = " " + m.groupValues[1].lowercase() + " " + m.groupValues[2]
        return card.copy(
            spanish = card.spanish.substringBefore(BLANK) + BLANK + after.substring(m.value.length),
            answer = card.answer + tail,
            also = card.also.map { if (' ' in it) it else it + tail },
        )
    }

    private val A_INFINITIVE = Regex("^\\s+(a)\\s+(\\p{L}*(?:ar|er|ir|ír))(?![\\p{L}])", RegexOption.IGNORE_CASE)

    private fun helperBefore(card: DrillCard): DrillCard {
        val before = card.spanish.substringBefore(BLANK)
        val m = HELPERS.find(before) ?: return card
        val helper = m.value.trim().replace(Regex("\\s+"), " ")
        val start = before.length - m.value.length
        val spanish = card.spanish.substring(0, start) + BLANK + card.spanish.substringAfter(BLANK)
        val fix = { a: String -> if (a.lowercase().startsWith(helper.lowercase() + " ")) a else "${helper.lowercase()} $a" }
        // The helper takes the answer's place at the start of a sentence: keep its capital there.
        val answer = fix(card.answer)
        return card.copy(spanish = spanish, answer = if (start == 0 && helper[0].isUpperCase()) answer.replaceFirstChar { it.uppercaseChar() } else answer, also = card.also.map(fix))
    }

    private val HELPERS = Regex(
        "(?<![\\p{L}])(?:(?:he|has|ha|hemos|han|había|habías|habíamos|habían|habré|habrás|habrá|habremos|habrán|habría|habrías|habríamos|habrían|" +
            "estoy|estás|está|estamos|están|estaba|estabas|estábamos|estaban)|(?:voy|vas|va|vamos|van|iba|ibas|íbamos|iban)\\s+a)\\s+$",
        RegexOption.IGNORE_CASE,
    )

    /** [cards] after the review in [text]: corrected where the reviewer fixed them, without those it rejected or left out. */
    fun applyReview(text: String, cards: List<DrillCard>): List<DrillCard> {
        for (el in JsonExtractor.candidates(text)) {
            val arr = (el as? JsonObject)?.get("cards") as? JsonArray ?: (el as? JsonArray) ?: continue
            val byNumber = cards.associateBy { it.number }
            val out = arr.mapNotNull { item ->
                val o = item as? JsonObject ?: return@mapNotNull null
                val card = (o["n"] as? JsonPrimitive)?.content?.toIntOrNull()?.let(byNumber::get) ?: return@mapNotNull null
                if ((o["ok"] as? JsonPrimitive)?.content == "false") return@mapNotNull null
                val spanish = o.str("spanish")?.replace(Regex("_{2,}"), BLANK) ?: card.spanish
                val answer = o.str("answer")?.let(::clean)?.takeIf { it.isNotEmpty() } ?: card.answer
                if (spanish.split(BLANK).size != 2) return@mapNotNull null
                val also = (o["also"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.let(::clean) }
                    ?.filter { it.isNotEmpty() && it != answer } ?: card.also
                withHelpers(card.copy(english = o.str("english") ?: card.english, spanish = spanish, answer = answer, also = also))
            }.distinctBy { it.id }.sortedBy { it.number }
            if (arr.isNotEmpty()) return out
        }
        throw MalformedGlossResponseException("No review in reply: ${text.take(200)}")
    }

    /** The well-formed cards of [text] (one blank, an answer), with ids `verb#n`. */
    fun parse(text: String, verb: String, situations: List<Pair<VerbTense, Subject>>): List<DrillCard> {
        for (el in JsonExtractor.candidates(text)) {
            val arr = (el as? JsonObject)?.get("cards") as? JsonArray ?: (el as? JsonArray) ?: continue
            val cards = arr.mapIndexedNotNull { i, item ->
                val o = item as? JsonObject ?: return@mapIndexedNotNull null
                val n = (o["n"] as? JsonPrimitive)?.content?.toIntOrNull() ?: (i + 1)
                val (tense, subject) = situations.getOrNull(n - 1) ?: return@mapIndexedNotNull null
                val english = o.str("english") ?: return@mapIndexedNotNull null
                val spanish = o.str("spanish")?.replace(Regex("_{2,}"), BLANK) ?: return@mapIndexedNotNull null
                val answer = o.str("answer")?.let(::clean) ?: return@mapIndexedNotNull null
                if (spanish.split(BLANK).size != 2 || answer.isEmpty()) return@mapIndexedNotNull null
                val also = (o["also"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content?.let(::clean) }
                    .filter { it.isNotEmpty() && it != answer }
                withHelpers(DrillCard("$verb#$n", verb, tense.id, subject.name, english, spanish, answer, also))
            }.distinctBy { it.id }
            if (cards.isNotEmpty()) return cards
        }
        throw MalformedGlossResponseException("No drill cards in reply: ${text.take(200)}")
    }

    private fun JsonObject.str(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }

    private val ENDS = Regex("^[\\s.,!?¡¿;:\"]+|[\\s.,!?¡¿;:\"]+$")

    private fun clean(s: String) = Normalizer.normalize(s, Normalizer.Form.NFC).replace(ENDS, "").replace(Regex("\\s+"), " ")

    private fun norm(s: String) = clean(s).lowercase()

    private fun plain(s: String) = Normalizer.normalize(norm(s), Normalizer.Form.NFD).replace(Regex("[\\u0300-\\u036f]"), "")

    /** Exact verb form (case, spacing and end punctuation aside); [DrillVerdict.ACCENT] when only accents differ. */
    fun check(card: DrillCard, typed: String): DrillVerdict {
        val t = norm(typed)
        val accepted = listOf(card.answer) + card.also
        return when {
            t.isEmpty() -> DrillVerdict.WRONG
            accepted.any { norm(it) == t } -> DrillVerdict.CORRECT
            accepted.any { plain(it) == plain(typed) } -> DrillVerdict.ACCENT
            else -> DrillVerdict.WRONG
        }
    }

    /** A correct answer counts one up, a miss (accents included) one down. */
    fun record(p: DrillProgress?, cardId: String, correct: Boolean, now: Long): DrillProgress {
        val cur = p ?: DrillProgress(cardId)
        return if (correct) cur.copy(correct = cur.correct + 1, lastMillis = now)
        else cur.copy(correct = maxOf(0, cur.correct - 1), misses = cur.misses + 1, lastMillis = now)
    }

    fun isLearned(p: DrillProgress?): Boolean = p != null && (p.hidden || p.correct >= TO_LEARN)

    /** Cards still to learn, least practiced first (ties shuffled); when all are learned, all of them for review. */
    fun round(cards: List<DrillCard>, progress: Map<String, DrillProgress>, random: Random = Random): List<DrillCard> {
        val active = cards.filter { progress[it.id]?.hidden != true }
        val open = active.filter { !isLearned(progress[it.id]) }
        return if (open.isEmpty()) active.shuffled(random)
        else open.shuffled(random).sortedBy { progress[it.id]?.correct ?: 0 }
    }

    /** All of a verb's cards learned. A verb without cards isn't. */
    fun mastered(cards: List<DrillCard>, progress: Map<String, DrillProgress>): Boolean =
        cards.isNotEmpty() && cards.all { isLearned(progress[it.id]) }

    /** The first common verb not yet mastered ([cardsOf] null: no cards yet). */
    fun currentVerb(cardsOf: (String) -> List<DrillCard>?, progress: Map<String, DrillProgress>): CommonVerb =
        CommonVerbs.ALL.firstOrNull { !mastered(cardsOf(it.infinitive).orEmpty(), progress) } ?: CommonVerbs.ALL.last()

    /** The words of a learned card's answer, for the vocabulary ("he comido" → he, comido). */
    fun answerWords(card: DrillCard): List<String> =
        card.answer.lowercase().split(' ').map { it.trim() }.filter { w -> w.isNotEmpty() && w.any(Char::isLetter) }.distinct()
}

/**
 * Writes a verb's cards with the AI, then has [reviewer] (the stronger model where there is one) check and fix them,
 * since a set is written once and shared. Each step retries once on an unparseable reply; a review that never parses
 * keeps the cards as written. Fails when fewer than half the cards survive.
 */
class DrillWriter(private val completer: TextCompleter, private val reviewer: TextCompleter = completer, private val random: Random = Random) {
    suspend fun write(verb: CommonVerb): List<DrillCard> {
        val situations = Drill.situations(random)
        val written = twice { retry -> Drill.parse(completer.complete(Drill.SYSTEM, Drill.user(verb, situations, retry)), verb.infinitive, situations) }
        val reviewed = try {
            twice { retry -> Drill.applyReview(reviewer.complete(Drill.REVIEW_SYSTEM, Drill.reviewUser(verb, written, retry)), written) }
        } catch (_: MalformedGlossResponseException) {
            written
        }
        if (reviewed.size < Drill.CARDS_PER_VERB / 2) throw MalformedGlossResponseException("Only ${reviewed.size} usable cards for ${verb.infinitive}")
        return reviewed
    }

    private suspend fun <T> twice(block: suspend (Boolean) -> T): T = try {
        block(false)
    } catch (e: CancellationException) {
        throw e
    } catch (_: MalformedGlossResponseException) {
        block(true)
    }
}
