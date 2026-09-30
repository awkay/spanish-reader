package net.awkay.spanishreader.core.gloss

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** The prompts shared by all LLM providers. */
object GlossPrompt {
    val SYSTEM: String = """
        You are a Spanish tutor for an English-speaking learner of Latin American Spanish.
        Each item gives a Spanish word form exactly as it appears in a text, plus the sentence it appears in.
        For every item, explain the word as it is used in THAT sentence, with these fields:
        - "id": echo the item's id.
        - "form": the word exactly as given.
        - "lemma": the dictionary form (infinitive for verbs, masculine singular for nouns and adjectives).
        - "partOfSpeech": verb, noun, adjective, adverb, pronoun, preposition, conjunction, determiner, interjection, etc.
        - "meaningInContext": a short English meaning that fits this sentence.
        - "verb": ONLY if the word is a verb form in this sentence (including infinitives, gerunds, participles and forms with pronouns attached), otherwise null. An object with:
            "infinitive";
            "tense": present, preterite, imperfect, future, conditional, present perfect, pluperfect, etc. (null for infinitive/gerund/participle);
            "mood": indicative, subjunctive, imperative, infinitive, gerund or past participle;
            "person": 1st, 2nd or 3rd, naming usted/ustedes/vos when that is the subject; null for non-finite forms;
            "number": singular or plural, or null;
            "formation": how THIS form is built from the infinitive: stem + ending, stem change, irregular stem, spelling change, and any written accent added because pronouns are attached;
            "whyThisForm": why this tense/mood is used in THIS sentence (e.g. "subjunctive because 'para que' introduces a purpose", "preterite for a completed past action").
        - "clitics": every object or reflexive pronoun (me, te, se, lo, la, le, nos, los, las, les) that belongs to this word's verb in the sentence, whether attached to it ("observándome", "dáselo") or placed before it ("se me olvidó", "lo vi"). If the item itself is such a pronoun, describe it and the other pronouns of its verb. Each entry: {"pronoun": "me", "role": ..., "refersTo": ..., "note": ...} where "role" is exactly one of: "direct object", "indirect object", "reflexive", "reciprocal", "pronominal verb", "accidental se", "impersonal se", "passive se"; "refersTo" is who or what it stands for, in English ("me", "the letter", "to her"); "note" is a short tip or null (e.g. "le becomes se before lo"). Use [] when there are none.
        - "roots": one or two sentences about origin or structure that help remember the word: a Latin/Greek/Arabic root with English cognates, or how it is built from parts (e.g. "en- + caja + -ar: to put into a box, hence 'to fit'"). null if nothing useful.
        - "grammarNote": anything else worth knowing (Latin American vs Spain usage, voseo, regional meaning, register); null if nothing.
        - "otherMeanings": up to 3 other common English meanings of the lemma; may be empty.
        - "isIdiomOrPhrase": true if the word is part of an idiom, fixed expression or verb + preposition combination with its own meaning in this sentence.
        - "phrase": that expression's words exactly as they appear in the sentence (e.g. "echar de menos"), or null.
        - "phraseMeaning": English meaning of the whole expression, or null.
        Write all explanations in English and keep each field brief.
        Respond with ONLY a JSON object, no markdown fences and no commentary, shaped exactly like:
        {"glosses":[{"id":"1","form":"...","lemma":"...","partOfSpeech":"...","meaningInContext":"...","verb":null,"clitics":[],"roots":null,"grammarNote":null,"otherMeanings":[],"isIdiomOrPhrase":false,"phrase":null,"phraseMeaning":null}]}
        Include exactly one entry per item.
    """.trimIndent()

    fun user(requests: List<GlossRequest>, isRetry: Boolean = false): String {
        val items = buildJsonObject {
            putJsonArray("items") {
                for (r in requests) addJsonObject {
                    put("id", r.id)
                    put("form", r.form)
                    put("sentence", r.sentence)
                    r.previous?.let { put("previousAnswer", previousJson.encodeToJsonElement(Gloss.serializer(), it)) }
                }
            }
        }
        val improve = if (requests.any { it.previous != null }) IMPROVE_NOTE else ""
        return "Gloss these items:\n$items$improve${reminder(isRetry)}"
    }

    val PHRASE_SYSTEM: String = """
        You find idioms and fixed multi-word expressions in Spanish sentences for a learner of Latin American Spanish.
        Include: idioms ("echar de menos", "dar a luz"), fixed expressions ("sin embargo", "a lo mejor", "de repente"),
        verb + preposition combinations with their own meaning ("darse cuenta de", "acabar de" + infinitive, "tratarse de"),
        and pronoun constructions with a special meaning ("se me olvidó", "me cae bien").
        Do not list ordinary literal word combinations.
        For each expression give "phrase": its words exactly as they appear in the sentence, in order, only the expression's
        own words; and "meaning": a short English meaning in this sentence.
        Respond with ONLY a JSON object, no markdown fences and no commentary, shaped exactly like:
        {"sentences":[{"id":"1","phrases":[{"phrase":"...","meaning":"..."}]}]}
        Include exactly one entry per sentence; use "phrases":[] when there are none.
    """.trimIndent()

    fun phraseUser(sentences: List<Pair<String, String>>, isRetry: Boolean = false): String {
        val items = buildJsonObject {
            putJsonArray("sentences") {
                for ((id, text) in sentences) addJsonObject {
                    put("id", id)
                    put("sentence", text)
                }
            }
        }
        return "Find the expressions in these sentences:\n$items${reminder(isRetry)}"
    }

    private val previousJson = kotlinx.serialization.json.Json { explicitNulls = false }

    private val IMPROVE_NOTE = "\n" + """
        The learner found the "previousAnswer" given for an item unhelpful or wrong. Re-read the sentence carefully and
        give a corrected, more careful and more complete answer: check the lemma, the exact conjugation and why it is used,
        every clitic and its role, and whether the word belongs to an expression. Do not simply repeat the previous answer.
    """.trimIndent()

    private fun reminder(isRetry: Boolean) =
        if (isRetry) "\nYour previous reply could not be parsed. Reply with valid JSON only." else ""
}
