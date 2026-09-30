package net.awkay.spanishreader.core.gloss

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class MalformedGlossResponseException(message: String) : Exception(message)

/** Turns raw model text into validated glosses keyed by request id. */
object GlossParser {
    private val LIST_KEYS = listOf("glosses", "items", "results", "words")

    /**
     * Returns the valid glosses found for [requests], keyed by id. Items that are missing or invalid are simply absent.
     * Throws [MalformedGlossResponseException] when the text contains no usable gloss JSON at all.
     */
    fun parse(text: String, requests: List<GlossRequest>): Map<String, Gloss> {
        val items = JsonExtractor.candidates(text).map(::itemsOf).firstOrNull { it.isNotEmpty() }
            ?: throw MalformedGlossResponseException("No gloss JSON found in model output: ${text.take(200)}")

        val byId = requests.associateBy { it.id }
        val unmatched = requests.toMutableList()
        val out = LinkedHashMap<String, Gloss>()
        for (obj in items) {
            val id = (obj["id"] as? JsonPrimitive)?.content
            val request = byId[id]?.takeIf { it in unmatched }
                ?: matchByForm(obj, unmatched)
                ?: unmatched.singleOrNull()?.takeIf { id == null }
                ?: continue
            val gloss = decode(obj, request) ?: continue
            out[request.id] = gloss
            unmatched.remove(request)
        }
        return out
    }

    private fun itemsOf(element: JsonElement): List<JsonObject> = when (element) {
        is JsonArray -> element.filterIsInstance<JsonObject>()
        is JsonObject -> {
            val list = LIST_KEYS.firstNotNullOfOrNull { element[it] as? JsonArray }
            when {
                list != null -> list.filterIsInstance<JsonObject>()
                "meaningInContext" in element || "meaning_in_context" in element -> listOf(element)
                else -> emptyList()
            }
        }
        else -> emptyList()
    }

    private fun matchByForm(obj: JsonObject, candidates: List<GlossRequest>): GlossRequest? {
        val form = (obj["form"] as? JsonPrimitive)?.content ?: return null
        return candidates.filter { it.form.equals(form, ignoreCase = true) }.singleOrNull()
    }

    /** Optional structured fields; a malformed one is dropped rather than losing the whole gloss. */
    private val OPTIONAL_STRUCTURED = listOf("verb", "clitics")

    private fun tryDecode(obj: JsonObject): Gloss? = try {
        JsonExtractor.lenientJson.decodeFromJsonElement(Gloss.serializer(), obj)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun decode(obj: JsonObject, request: GlossRequest): Gloss? {
        val gloss = tryDecode(obj)
            ?: OPTIONAL_STRUCTURED.firstNotNullOfOrNull { key -> tryDecode(JsonObject(obj - key)) }
            ?: tryDecode(JsonObject(obj.filterKeys { it !in OPTIONAL_STRUCTURED }))
            ?: return null
        if (gloss.meaningInContext.isBlank()) return null
        return gloss.copy(
            form = gloss.form.ifBlank { request.form },
            lemma = gloss.lemma.ifBlank { request.form },
            otherMeanings = gloss.otherMeanings.filter { it.isNotBlank() },
            grammarNote = gloss.grammarNote?.takeIf { it.isNotBlank() },
            phrase = gloss.phrase?.takeIf { it.isNotBlank() },
            phraseMeaning = gloss.phraseMeaning?.takeIf { it.isNotBlank() },
            roots = gloss.roots?.takeIf { it.isNotBlank() },
            verb = gloss.verb?.takeIf { it.infinitive.isNotBlank() },
            clitics = gloss.clitics.filter { it.pronoun.isNotBlank() },
        )
    }

    /** Parses a phrase-finder reply: expressions per sentence id. Sentences missing from the reply are absent. */
    fun parsePhrases(text: String, ids: List<String>): Map<String, List<FoundPhrase>> {
        val entries = JsonExtractor.candidates(text).mapNotNull { root ->
            when (root) {
                is JsonObject -> (root["sentences"] as? JsonArray ?: root["items"] as? JsonArray ?: root["results"] as? JsonArray)
                is JsonArray -> root
                else -> null
            }?.filterIsInstance<JsonObject>()
        }.firstOrNull { it.isNotEmpty() }
            ?: throw MalformedGlossResponseException("No phrase JSON found in model output: ${text.take(200)}")
        val out = LinkedHashMap<String, List<FoundPhrase>>()
        val remaining = ids.toMutableList()
        for ((i, e) in entries.withIndex()) {
            val id = (e["id"] as? JsonPrimitive)?.content?.takeIf { it in remaining }
                ?: ids.getOrNull(i)?.takeIf { it in remaining && e["id"] == null }
                ?: continue
            val phrases = (e["phrases"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull { p ->
                val phrase = (p["phrase"] as? JsonPrimitive)?.content?.trim().orEmpty()
                if (phrase.isEmpty() || ' ' !in phrase) null
                else FoundPhrase(phrase, (p["meaning"] as? JsonPrimitive)?.content?.trim().orEmpty())
            }
            out[id] = phrases
            remaining.remove(id)
        }
        return out
    }
}
