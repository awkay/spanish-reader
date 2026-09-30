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

    private fun decode(obj: JsonObject, request: GlossRequest): Gloss? {
        val gloss = try {
            JsonExtractor.lenientJson.decodeFromJsonElement(Gloss.serializer(), obj)
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (gloss.meaningInContext.isBlank()) return null
        return gloss.copy(
            form = gloss.form.ifBlank { request.form },
            lemma = gloss.lemma.ifBlank { request.form },
            otherMeanings = gloss.otherMeanings.filter { it.isNotBlank() },
            grammarNote = gloss.grammarNote?.takeIf { it.isNotBlank() },
            phrase = gloss.phrase?.takeIf { it.isNotBlank() },
        )
    }
}
