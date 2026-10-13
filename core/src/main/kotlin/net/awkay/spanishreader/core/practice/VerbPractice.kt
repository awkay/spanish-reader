package net.awkay.spanishreader.core.practice

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import net.awkay.spanishreader.core.gloss.Gloss
import net.awkay.spanishreader.core.gloss.Infinitives
import net.awkay.spanishreader.core.gloss.JsonExtractor
import net.awkay.spanishreader.core.gloss.MalformedGlossResponseException
import net.awkay.spanishreader.core.gloss.TextCompleter
import net.awkay.spanishreader.core.vocab.VocabEntry
import java.text.Normalizer
import kotlin.random.Random

/*
 * Verb practice: a verb the learner has met, a tense and a subject are picked locally (no AI), then the AI writes an
 * English sentence that needs that verb form, and checks the learner's Spanish translation with up to [MAX_TRIES]
 * tries and growing hints. Ported to the web app in web/src/core/practice.ts.
 */

/** A verb the learner has met (some form of it is in the vocabulary at LEVEL_1..KNOWN). */
data class PracticeVerb(val infinitive: String, val meaning: String?, val forms: List<String>)

/** The person the sentence is about: Spanish pronoun plus an English hint for the prompt and the screen. */
enum class Subject(val spanish: String, val english: String) {
    YO("yo", "I"),
    TU("tú", "you, informal"),
    EL("él", "he"),
    ELLA("ella", "she"),
    USTED("usted", "you, formal"),
    NOSOTROS("nosotros", "we"),
    ELLOS("ellos", "they"),
    USTEDES("ustedes", "you all"),
    ;

    val label: String get() = "$spanish ($english)"
}

/** A tense/construction to practice. [instruction] tells the AI how the English must signal it. */
data class VerbTense(
    val id: String,
    val label: String,
    val example: String,
    val instruction: String,
    val subjects: List<Subject> = Subject.entries,
)

object VerbTenses {
    private val imperative = listOf(Subject.TU, Subject.USTED, Subject.USTEDES, Subject.NOSOTROS)

    val ALL: List<VerbTense> = listOf(
        VerbTense("present", "present", "hablo", "simple present indicative: a habit, a fact, or what is happening now"),
        VerbTense("preterite", "preterite", "hablé", "preterite: a completed past action at a definite time (yesterday, last year, once)"),
        VerbTense("imperfect", "imperfect", "hablaba", "imperfect: a past habit (used to, would every day) or a background/ongoing past state"),
        VerbTense("future", "future", "hablaré", "simple future (will ...)"),
        VerbTense("conditional", "conditional", "hablaría", "conditional (would ...), e.g. a hypothetical or a polite request"),
        VerbTense("present-perfect", "present perfect", "he hablado", "present perfect with haber (have/has done)"),
        VerbTense("pluperfect", "pluperfect", "había hablado", "pluperfect with haber in the imperfect (had done)"),
        VerbTense("conditional-perfect", "conditional perfect", "habría hablado", "conditional perfect (would have done)"),
        VerbTense("present-subjunctive", "present subjunctive", "hable", "present subjunctive, triggered by a wish, request, doubt, emotion or a conjunction like para que / cuando (future)"),
        VerbTense("imperfect-subjunctive", "imperfect subjunctive", "hablara", "imperfect subjunctive (-ra form), e.g. after si in a hypothetical (if I had...) or a past wish/request"),
        VerbTense("imperative", "affirmative command", "habla / hable", "affirmative command to the subject", imperative),
        VerbTense("negative-imperative", "negative command", "no hables", "negative command (don't ...) to the subject", imperative),
        VerbTense("near-future", "ir a + infinitive", "voy a hablar", "near future with ir a + infinitive (going to ...)"),
        VerbTense("past-near-future", "ir (imperfect) a + infinitive", "iba a hablar", "ir in the imperfect + a + infinitive (was/were going to ...)"),
        VerbTense("present-progressive", "present progressive", "estoy hablando", "estar in the present + gerund (am/is/are ...ing right now)"),
        VerbTense("past-progressive", "past progressive", "estaba hablando", "estar in the imperfect + gerund (was/were ...ing)"),
    )

    fun byId(id: String): VerbTense? = ALL.firstOrNull { it.id == id }
}

/** Everything the AI gets to write one exercise. */
data class PracticeSpec(
    val verb: PracticeVerb,
    val tense: VerbTense,
    val subject: Subject,
    val knownWords: List<String>,
)

@Serializable
data class VerbExercise(
    val english: String,
    /** A model answer, shown after the last try (or on success, as another way to say it). */
    val spanish: String,
    /** The conjugated verb (with any helper verb) as used in [spanish]. */
    val verbForm: String? = null,
    val verbMeaning: String? = null,
)

@Serializable
data class AnswerCheck(
    val correct: Boolean,
    /** Short English feedback on this answer. */
    val feedback: String,
    /** The next hint (stronger each try); null when correct or out of tries. */
    val hint: String? = null,
)

object VerbPool {
    /** LEVEL_1..KNOWN: tapped, seen on a finished page, or marked. */
    fun isExposed(e: VocabEntry) = e.status.code in 1..5

    /**
     * The verbs among the exposed vocabulary, by plain infinitive. [glossesOf] gives a form's cached glosses (any
     * sentence); a form counts as a verb when one of them says so.
     */
    fun verbs(entries: List<VocabEntry>, glossesOf: (String) -> List<Gloss>): List<PracticeVerb> {
        val forms = LinkedHashMap<String, MutableList<String>>()
        val meanings = HashMap<String, String>()
        for (e in entries) {
            if (!isExposed(e)) continue
            for (g in glossesOf(e.form)) {
                val v = g.verb ?: continue
                val inf = Infinitives.plain(v.infinitive) ?: Infinitives.plain(g.lemma) ?: continue
                val list = forms.getOrPut(inf) { mutableListOf() }
                if (e.form !in list) list += e.form
                val m = g.lemmaMeaning?.trim()
                if (!m.isNullOrEmpty() && inf !in meanings) meanings[inf] = m
            }
        }
        return forms.map { (inf, fs) -> PracticeVerb(inf, meanings[inf], fs) }.sortedBy { it.infinitive }
    }

    /** Up to [count] distinct exposed words (lemma where known), in random order, without [exclude]. */
    fun knownWords(entries: List<VocabEntry>, exclude: Set<String>, count: Int = 100, random: Random = Random): List<String> =
        entries.asSequence()
            .filter { isExposed(it) }
            .map { it.lemma?.trim()?.lowercase()?.takeIf { l -> l.isNotEmpty() } ?: it.form }
            .filter { it !in exclude && it.any(Char::isLetter) }
            .distinct()
            .toList()
            .shuffled(random)
            .take(count)

    /**
     * A random verb (avoiding [recent] while others remain), tense and subject. [tenses] limits the choice; null when
     * there are no verbs yet.
     */
    fun pick(
        verbs: List<PracticeVerb>,
        entries: List<VocabEntry>,
        random: Random = Random,
        recent: Collection<String> = emptyList(),
        tenses: List<VerbTense> = VerbTenses.ALL,
    ): PracticeSpec? {
        if (verbs.isEmpty() || tenses.isEmpty()) return null
        val fresh = verbs.filter { it.infinitive !in recent }.ifEmpty { verbs }
        val verb = fresh.random(random)
        val tense = tenses.random(random)
        val subject = tense.subjects.random(random)
        val exclude = setOf(verb.infinitive) + verb.forms
        return PracticeSpec(verb, tense, subject, knownWords(entries, exclude, random = random))
    }
}

/** The prompts; fixtures/prompts.json carries them to the web app. */
object PracticePrompt {
    const val MAX_TRIES = 3

    val EXERCISE_SYSTEM: String = """
        You write translation exercises for an English-speaking learner of Latin American Spanish.
        You get a Spanish verb, a tense or construction, a subject, and Spanish words the learner already knows.
        Write ONE natural, everyday English sentence (5 to 14 words) whose most natural Latin American Spanish translation
        uses that verb, conjugated in exactly that tense or construction, with that subject.
        - The English must make the tense unambiguous: add the time words or context it needs ("every summer" for the
          imperfect, "yesterday" for the preterite, a trigger like "I want you to..." for the subjunctive).
        - Build the rest of the sentence mostly from the known words; use other words only when needed, and keep them common.
        - Use Latin American Spanish: ustedes, never vosotros.
        - If the verb is normally used with an indirect object (gustar, encantar, doler) or is impersonal (llover), use its
          natural construction and make the subject the person it is about.
        Respond with ONLY a JSON object, no markdown fences and no commentary, shaped exactly like:
        {"english":"...","spanish":"...","verbForm":"...","verbMeaning":"..."}
        "spanish": your translation, with every written accent and ¿ ¡ where needed; "verbForm": the conjugated verb in it,
        with any helper verb ("íbamos a ir");
        "verbMeaning": the verb's basic English meaning ("to go").
    """.trimIndent()

    val CHECK_SYSTEM: String = """
        You check a translation exercise for an English-speaking learner of Latin American Spanish. You get the English
        sentence, the verb, tense and subject being practiced, a model answer and its verb form, the learner's earlier wrong
        tries, this try number (of $MAX_TRIES), and the learner's answer.
        - "correct": true if the answer is a natural, grammatical Latin American Spanish translation with the same meaning
          that uses the practiced verb in the practiced tense and person. Other wording than the model answer is fine.
          Ignore capitalization and punctuation; accept missing written accents and missing subject pronouns.
        - "feedback": one or two short English sentences. If correct, mention a missing written accent only if a word in
          the answer really lacks one; never comment on punctuation or capitalization. If not correct, say what kind of
          problem there is without giving away the correction.
        - "hint": null when correct. Otherwise write your own hint for the learner, following "howToHint" (null when it
          says none). Never copy "howToHint" itself.
        Respond with ONLY a JSON object, no markdown fences and no commentary, shaped exactly like:
        {"correct":false,"feedback":"...","hint":"..."}
    """.trimIndent()

    fun exerciseUser(spec: PracticeSpec, isRetry: Boolean = false): String {
        val o = buildJsonObject {
            put("verb", spec.verb.infinitive)
            spec.verb.meaning?.let { put("verbMeaning", it) }
            put("tense", spec.tense.label)
            put("tenseNote", spec.tense.instruction)
            put("example", spec.tense.example)
            put("subject", spec.subject.label)
            putJsonArray("knownWords") { spec.knownWords.forEach { add(it) } }
        }
        return "Write one exercise:\n$o${reminder(isRetry)}"
    }

    fun checkUser(spec: PracticeSpec, exercise: VerbExercise, earlier: List<String>, answer: String, isRetry: Boolean = false): String {
        val o = buildJsonObject {
            put("english", exercise.english)
            put("verb", spec.verb.infinitive)
            put("tense", spec.tense.label)
            put("subject", spec.subject.label)
            put("modelAnswer", exercise.spanish)
            exercise.verbForm?.let { put("modelVerbForm", it) }
            putJsonArray("earlierTries") { earlier.forEach { add(it) } }
            put("try", earlier.size + 1)
            put("answer", answer)
            put("howToHint", howToHint(earlier.size + 1, exercise))
        }
        return "Check this answer:\n$o${reminder(isRetry)}"
    }

    /** Hints get stronger with each try; the last try gets none because the model answer is shown. */
    fun howToHint(tryNumber: Int, exercise: VerbExercise): String = when {
        tryNumber >= MAX_TRIES -> "none"
        tryNumber == 1 -> "say where the problem is and which rule applies, without any Spanish words from the model answer"
        else -> "give the correct verb form exactly" + (exercise.verbForm?.let { " (\"$it\")" } ?: "") +
            " and explain how the sentence is built, without giving the whole sentence"
    }

    private fun reminder(isRetry: Boolean) =
        if (isRetry) "\nYour previous reply could not be parsed. Reply with valid JSON only." else ""
}

object PracticeParser {
    fun exercise(text: String): VerbExercise {
        for (el in JsonExtractor.candidates(text)) {
            val o = el as? JsonObject ?: continue
            val english = o.str("english") ?: continue
            val spanish = o.str("spanish") ?: continue
            return VerbExercise(english, spanish, o.str("verbForm", "verb_form"), o.str("verbMeaning", "verb_meaning"))
        }
        throw MalformedGlossResponseException("No exercise in reply: ${text.take(200)}")
    }

    fun check(text: String): AnswerCheck {
        for (el in JsonExtractor.candidates(text)) {
            val o = el as? JsonObject ?: continue
            val correct = (o["correct"] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.content.equals("true", true) } ?: continue
            return AnswerCheck(correct, o.str("feedback") ?: if (correct) "Correct!" else "Not quite.", if (correct) null else o.str("hint"))
        }
        throw MalformedGlossResponseException("No check result in reply: ${text.take(200)}")
    }

    private fun JsonObject.str(vararg keys: String): String? = keys.firstNotNullOfOrNull { k ->
        (get(k) as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/** Same words as [reference], ignoring case, punctuation and spacing (accents still count). Saves an AI call. */
fun sameAnswer(answer: String, reference: String): Boolean {
    fun norm(s: String) = Normalizer.normalize(s, Normalizer.Form.NFC).lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    return norm(answer).isNotEmpty() && norm(answer) == norm(reference)
}

/** The two AI steps, each retried once on an unparseable reply. */
class VerbPracticeAi(private val completer: TextCompleter) {
    suspend fun exercise(spec: PracticeSpec): VerbExercise =
        twice { retry -> PracticeParser.exercise(completer.complete(PracticePrompt.EXERCISE_SYSTEM, PracticePrompt.exerciseUser(spec, retry))) }

    suspend fun check(spec: PracticeSpec, exercise: VerbExercise, earlier: List<String>, answer: String): AnswerCheck {
        if (sameAnswer(answer, exercise.spanish)) return AnswerCheck(true, "Correct!")
        val last = earlier.size + 1 >= PracticePrompt.MAX_TRIES
        val c = twice { retry ->
            PracticeParser.check(completer.complete(PracticePrompt.CHECK_SYSTEM, PracticePrompt.checkUser(spec, exercise, earlier, answer, retry)))
        }
        // Small models sometimes echo the instruction instead of writing a hint.
        val echoed = c.hint?.let { PracticePrompt.howToHint(earlier.size + 1, exercise).startsWith(it.take(30), ignoreCase = true) } == true
        return if (last || echoed) c.copy(hint = null) else c
    }

    private suspend fun <T> twice(block: suspend (Boolean) -> T): T {
        try {
            return block(false)
        } catch (e: CancellationException) {
            throw e
        } catch (_: MalformedGlossResponseException) {
            return block(true)
        }
    }
}
