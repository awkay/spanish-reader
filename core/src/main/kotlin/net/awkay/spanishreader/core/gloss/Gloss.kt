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
)

/** Gloss [form] as it is used in [sentence]. [id] correlates the result and must be unique within a call. */
data class GlossRequest(val form: String, val sentence: String, val id: String = form)

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
