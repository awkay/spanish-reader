@file:OptIn(ExperimentalSerializationApi::class)

package net.awkay.spanishreader.core.gloss

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Pulls JSON out of chatty LLM output: code fences, leading prose, trailing commentary, trailing commas. */
object JsonExtractor {
    internal val lenientJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
        allowTrailingComma = true
    }

    private val FENCE = Regex("```(?:json|JSON)?\\s*\\n?(.*?)```", RegexOption.DOT_MATCHES_ALL)

    /** The first parseable JSON object or array in [text], or null. */
    fun extract(text: String): JsonElement? = candidates(text).firstOrNull()

    /** Every parseable top-level JSON object/array, fenced blocks first, in order of appearance. */
    fun candidates(text: String): Sequence<JsonElement> = sequence {
        for (m in FENCE.findAll(text)) yieldAll(scan(m.groupValues[1]))
        yieldAll(scan(text))
    }

    private fun scan(text: String): Sequence<JsonElement> = sequence {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '{' || c == '[') {
                val end = matchingEnd(text, i)
                if (end > 0) {
                    val parsed = tryParse(text.substring(i, end + 1))
                    if (parsed != null) {
                        yield(parsed)
                        i = end + 1
                        continue
                    }
                }
            }
            i++
        }
    }

    private fun tryParse(s: String): JsonElement? =
        try {
            lenientJson.parseToJsonElement(s)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    /** Index of the bracket closing the one at [start], respecting strings; -1 if unbalanced. */
    private fun matchingEnd(text: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> depth++
                '}', ']' -> {
                    depth--
                    if (depth == 0) return i
                    if (depth < 0) return -1
                }
            }
        }
        return -1
    }
}
