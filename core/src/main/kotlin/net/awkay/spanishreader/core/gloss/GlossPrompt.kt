package net.awkay.spanishreader.core.gloss

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** The single prompt shared by all LLM providers. */
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
        - "grammarNote": for verbs give tense, mood, person and number; break down attached clitic pronouns (for example "dámelo" = da, imperative tú of dar + me + lo). Mention Latin American usage when it differs from Spain (ustedes instead of vosotros, voseo, regional meanings). Use null when there is nothing useful to add.
        - "otherMeanings": up to 3 other common English meanings of the lemma; may be empty.
        - "isIdiomOrPhrase": true if the word is part of an idiom or fixed expression in this sentence.
        - "phrase": that idiom or expression as written in the sentence, or null.
        Write all explanations in English.
        Respond with ONLY a JSON object, no markdown fences and no commentary, shaped exactly like:
        {"glosses":[{"id":"1","form":"...","lemma":"...","partOfSpeech":"...","meaningInContext":"...","grammarNote":null,"otherMeanings":[],"isIdiomOrPhrase":false,"phrase":null}]}
        Include exactly one entry per item.
    """.trimIndent()

    fun user(requests: List<GlossRequest>, isRetry: Boolean = false): String {
        val items = buildJsonObject {
            putJsonArray("items") {
                for (r in requests) addJsonObject {
                    put("id", r.id)
                    put("form", r.form)
                    put("sentence", r.sentence)
                }
            }
        }
        val reminder = if (isRetry) "\nYour previous reply could not be parsed. Reply with valid JSON only." else ""
        return "Gloss these items:\n$items$reminder"
    }
}
