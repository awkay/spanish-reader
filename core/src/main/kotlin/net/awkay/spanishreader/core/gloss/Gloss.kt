@file:OptIn(ExperimentalSerializationApi::class)

package net.awkay.spanishreader.core.gloss

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/** An LLM explanation of one word as used in one sentence. */
@Serializable
data class Gloss(
    val form: String,
    val lemma: String,
    @JsonNames("part_of_speech", "pos")
    val partOfSpeech: String,
    @JsonNames("meaning_in_context", "meaning")
    val meaningInContext: String,
    @JsonNames("grammar_note")
    val grammarNote: String? = null,
    @JsonNames("other_meanings")
    val otherMeanings: List<String> = emptyList(),
    @JsonNames("is_idiom_or_phrase")
    val isIdiomOrPhrase: Boolean = false,
    val phrase: String? = null,
    /** English meaning of [phrase] as a whole. */
    @JsonNames("phrase_meaning")
    val phraseMeaning: String? = null,
    /** Set when the word is a verb form in this sentence. */
    val verb: VerbForm? = null,
    /** Object/reflexive pronouns belonging to this word's verb, attached or placed before it. */
    val clitics: List<Clitic> = emptyList(),
    /** Origin or word structure worth knowing (Latin root, compound pattern such as en- + caja + -ar). */
    val roots: String? = null,
) {
    /** True for glosses cached before the richer fields existed (or from a model that ignored them). */
    val lacksDetail: Boolean
        get() = roots == null && verb == null && clitics.isEmpty() && partOfSpeech.contains("verb", ignoreCase = true)
}

/** How a verb form is built and why it is used in its sentence. */
@Serializable
data class VerbForm(
    val infinitive: String,
    val tense: String? = null,
    val mood: String? = null,
    val person: String? = null,
    val number: String? = null,
    /** Stem + ending, stem changes, irregularities, accents added for attached pronouns. */
    @JsonNames("how_formed", "howFormed")
    val formation: String? = null,
    /** Why this tense/mood fits the sentence (e.g. subjunctive after "para que"). */
    @JsonNames("why_this_form", "why", "reason")
    val whyThisForm: String? = null,
) {
    /** "preterite indicative · 3rd person singular", skipping unknown parts. */
    val summary: String
        get() = listOfNotNull(
            listOfNotNull(tense, mood).filter { it.isNotBlank() }.joinToString(" ").ifBlank { null },
            listOfNotNull(person?.let { p -> if (p.contains("person", true) || p.contains("usted", true) || p.contains("vos", true)) p else "$p person" }, number)
                .filter { it.isNotBlank() }.joinToString(" ").ifBlank { null },
        ).joinToString(" · ")
}

/** One clitic pronoun and what it does in the sentence. */
@Serializable
data class Clitic(
    val pronoun: String,
    val role: String,
    @JsonNames("refers_to")
    val refersTo: String? = null,
    val note: String? = null,
) {
    val kind: CliticRole get() = CliticRole.of(role)
}

/** Normalized clitic roles, so the UI can color them consistently whatever wording the model used. */
enum class CliticRole(val label: String) {
    DIRECT_OBJECT("direct object"),
    INDIRECT_OBJECT("indirect object"),
    REFLEXIVE("reflexive"),
    RECIPROCAL("reciprocal"),
    PRONOMINAL("pronominal verb"),
    ACCIDENTAL_SE("accidental se"),
    IMPERSONAL_SE("impersonal / passive se"),
    OTHER("other");

    companion object {
        fun of(role: String): CliticRole {
            val r = role.lowercase()
            return when {
                "indirect" in r || r == "io" || "dative" in r -> INDIRECT_OBJECT
                "direct" in r || r == "do" || "accusative" in r -> DIRECT_OBJECT
                "reciproc" in r -> RECIPROCAL
                "reflex" in r -> REFLEXIVE
                "accident" in r || "no-fault" in r || "involuntary" in r || "unplanned" in r -> ACCIDENTAL_SE
                "impersonal" in r || "passive" in r -> IMPERSONAL_SE
                "pronominal" in r || "inherent" in r || "lexical" in r -> PRONOMINAL
                else -> OTHER
            }
        }
    }
}

/** The pronouns glued onto the end of a verb form, e.g. "observándome" = "observándo" + [me]. */
data class CliticSplit(val base: String, val attached: List<String>) {
    companion object {
        private val PRONOUNS = setOf("me", "te", "se", "lo", "la", "le", "nos", "os", "los", "las", "les")

        /** Strips trailing pronouns of [form] that appear in [clitics]; the rest stand before the verb. */
        fun of(form: String, clitics: List<Clitic>): CliticSplit {
            val wanted = clitics.map { it.pronoun.lowercase().trim() }.filter { it in PRONOUNS }.toMutableList()
            var base = form
            val attached = ArrayList<String>()
            while (true) {
                val lower = base.lowercase()
                // Longest first so "los" wins over "lo"/"os".
                val p = wanted.sortedByDescending { it.length }.firstOrNull { lower.endsWith(it) && base.length - it.length >= 2 } ?: break
                attached.add(0, base.substring(base.length - p.length))
                base = base.substring(0, base.length - p.length)
                wanted.remove(p)
            }
            // Pronouns attach only to infinitives, gerunds and affirmative imperatives; otherwise "habla" ≠ "hab" + "la".
            return if (attached.isNotEmpty() && isAttachableHost(base, attached.size)) CliticSplit(base, attached)
            else CliticSplit(form, emptyList())
        }

        private val SHORT_IMPERATIVES = setOf("di", "haz", "pon", "ven", "ten", "sal", "ve", "sé", "da", "vamo")

        private fun isAttachableHost(base: String, count: Int): Boolean {
            val b = java.text.Normalizer.normalize(base.lowercase(), java.text.Normalizer.Form.NFD)
            val plain = b.replace(Regex("\\p{M}"), "")
            return plain.endsWith("r") || plain.endsWith("ndo") || b != plain /* written accent: imperative */ ||
                plain in SHORT_IMPERATIVES || count >= 2
        }
    }
}

/** An idiom or fixed expression found in a sentence, with its words as they appear there. */
@Serializable
data class FoundPhrase(val phrase: String, val meaning: String = "")

/** What one sentence-level call yields: an English translation and the expressions in the sentence. */
@Serializable
data class SentenceAnalysis(val translation: String?, val phrases: List<FoundPhrase>)

/**
 * Analyzes whole sentences (translation + idioms), independently of which words get glossed, so idioms made of
 * already-known words are found too.
 */
interface SentenceAnalyzer {
    /** One result per input sentence, in order; null for a sentence whose batch failed. */
    suspend fun analyze(sentences: List<String>): List<SentenceAnalysis?>
}

/**
 * Gloss [form] as it is used in [sentence]. [id] correlates the result and must be unique within a call.
 * [previous] is an earlier answer the learner found unhelpful; the model is asked to do better.
 */
data class GlossRequest(val form: String, val sentence: String, val id: String = form, val previous: Gloss? = null)

sealed interface GlossResult {
    val id: String

    data class Success(override val id: String, val gloss: Gloss) : GlossResult

    data class Failure(override val id: String, val error: String, val cause: Throwable? = null) : GlossResult
}

interface Glosser {
    /** Returns exactly one result per request, in request order. Never throws for per-item or HTTP failures. */
    suspend fun gloss(requests: List<GlossRequest>): List<GlossResult>
}

suspend fun Glosser.gloss(form: String, sentence: String): GlossResult = gloss(listOf(GlossRequest(form, sentence))).single()
