package net.awkay.spanishreader.core.chat

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import net.awkay.spanishreader.core.gloss.JsonExtractor
import net.awkay.spanishreader.core.gloss.MalformedGlossResponseException
import net.awkay.spanishreader.core.gloss.TextCompleter
import net.awkay.spanishreader.core.text.Tokenizer

/*
 * Conversation mode (Android): the learner talks about a topic of their choice with an AI partner, typing or speaking
 * (Spanish, often mixed with English). Each learner turn is checked in the background: what it means in English and
 * the smallest correction a learner could have managed. The partner answers in Spanish, one sentence per item with
 * its English, so the reply can be spoken sentence by sentence and the translations kept for the saved lesson.
 */

enum class ChatRole { LEARNER, PARTNER }

/** One turn of the conversation as sent to the AI; learner turns use their corrected text once it is known. */
data class ChatLine(val role: ChatRole, val text: String)

/** One sentence of the partner's reply and its English. */
data class PartnerSentence(val es: String, val en: String)

/** One fix in a learner's turn. */
data class ChatChange(val from: String, val to: String, val why: String)

/**
 * The check of one learner turn: [english] is what they meant; [corrected] the smallest change that makes it correct
 * (their own text when [ok]).
 */
data class Correction(val english: String, val corrected: String, val changes: List<ChatChange>, val ok: Boolean)

object ChatPrompt {
    /** Earlier turns sent with each request; older ones are dropped. */
    const val HISTORY = 24

    val PARTNER_SYSTEM = """
        You are a friendly conversation partner for an English speaker who is learning Latin American Spanish. They chose the topic; chat with them about it like a friendly native speaker in a relaxed, everyday conversation.

        Rules:
        - Reply in Latin American Spanish only (ustedes, never vosotros; neutral Mexican/US Spanish vocabulary), even if the learner writes some or all of it in English.
        - Speak at a natural conversational level: everyday vocabulary and any tense a native would use, but no rare words, slang-heavy phrasing or long, complicated sentences.
        - Keep each reply short, like a turn in a spoken conversation: 1 to 3 sentences, about 30 words at most.
        - Respond to what the learner meant. They make mistakes and mix in English words: understand them, never point out or correct their mistakes, and don't comment on their Spanish.
        - Keep the conversation going: usually end with one natural question about the topic or about what they said.
        - Never use emojis, lists or stage directions; write only what you would say out loud.

        Respond ONLY with JSON: {"reply": [{"es": "a sentence", "en": "its natural English translation"}]}, one item per sentence, in order.
        Example: {"reply": [{"es": "¡Qué rico!", "en": "How delicious!"}, {"es": "¿Qué pediste de postre?", "en": "What did you order for dessert?"}]}
    """.trimIndent()

    val CORRECT_SYSTEM = """
        You check one thing a learner of Latin American Spanish said during a conversation, so they can see a better way to say it later. They may mix in English words.

        Give:
        - "english": what they meant, in natural English.
        - "corrected": the smallest change that makes it correct, natural-enough Latin American Spanish that a learner at their level could have said. Keep their words, word choice, sentence structure and simplicity wherever they are already correct. Fix only: verb conjugation and tense, gender and number agreement, articles and prepositions, word order, wrong words, missing accents and ¿ ¡, and English words or phrases (replace each with the simplest common Spanish). Never rewrite for style, never make it sound more native or more elaborate, and never add ideas.
        - "changes": one item per fix: {"from": "their words", "to": "the corrected words", "why": "one short sentence in English"}.
        - "ok": true when nothing needed fixing ("corrected" is then their text, "changes" empty).
        If "source" is "voice", the text came from speech recognition: a word that makes no sense there may be a mis-hearing; fix it to the most likely word they said (why: "Probably misheard").
        "partnerSaid" is what the partner said just before, to understand what they meant.

        Respond ONLY with JSON, for example:
        {"english": "I went to the beach with my family and we ate sandwiches.", "corrected": "Fui a la playa con mi familia y comimos sándwiches.", "changes": [{"from": "al playa", "to": "a la playa", "why": "Playa is feminine; only a + el contracts to al."}, {"from": "we ate", "to": "comimos", "why": "'We ate' is comimos (comer, preterite, nosotros)."}], "ok": false}
        {"english": "I like to cook on weekends.", "corrected": "Me gusta cocinar los fines de semana.", "changes": [], "ok": true}
    """.trimIndent()

    fun partnerUser(topic: String, history: List<ChatLine>, learnerSaid: String, retry: Boolean = false): String {
        val o = buildJsonObject {
            put("topic", topic)
            putJsonArray("conversation") {
                for (line in history.takeLast(HISTORY)) addJsonObject {
                    put("who", if (line.role == ChatRole.LEARNER) "learner" else "you")
                    put("text", line.text)
                }
            }
            put("learnerJustSaid", learnerSaid)
        }
        return "$o\n\nWrite your next reply." + if (retry) RETRY else ""
    }

    fun correctUser(said: String, partnerSaid: String?, voice: Boolean, retry: Boolean = false): String {
        val o = buildJsonObject {
            put("learnerSaid", said)
            put("source", if (voice) "voice" else "typed")
            if (!partnerSaid.isNullOrBlank()) put("partnerSaid", partnerSaid)
        }
        return "$o" + if (retry) RETRY else ""
    }

    private const val RETRY = "\n\nYour previous reply could not be read. Respond with the JSON object only."
}

object ChatParser {
    fun reply(text: String): List<PartnerSentence> {
        for (el in JsonExtractor.candidates(text)) {
            val items = when (el) {
                is JsonObject -> (el["reply"] ?: el["sentences"]) as? JsonArray ?: continue
                is JsonArray -> el
                else -> continue
            }
            val sentences = items.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val es = o.str("es", "spanish", "text") ?: return@mapNotNull null
                PartnerSentence(es, o.str("en", "english", "translation").orEmpty())
            }
            if (sentences.isNotEmpty()) return sentences
        }
        throw MalformedGlossResponseException("No reply in: ${text.take(200)}")
    }

    fun correction(text: String, said: String): Correction {
        for (el in JsonExtractor.candidates(text)) {
            val o = el as? JsonObject ?: continue
            val english = o.str("english", "meaning") ?: continue
            val changes = (o["changes"] as? JsonArray).orEmpty().mapNotNull { e ->
                val c = e as? JsonObject ?: return@mapNotNull null
                val to = c.str("to") ?: return@mapNotNull null
                ChatChange(c.str("from").orEmpty(), to, c.str("why", "reason").orEmpty())
            }
            val corrected = o.str("corrected", "spanish") ?: said.trim()
            val okFlag = (o["ok"] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.content.equals("true", true) }
            val ok = if (sameText(corrected, said)) true else okFlag == true && changes.isEmpty()
            return Correction(english, if (ok) said.trim() else corrected, if (ok) emptyList() else changes, ok)
        }
        throw MalformedGlossResponseException("No correction in: ${text.take(200)}")
    }

    /** Equal but for spacing (the model may echo the text with different whitespace). */
    private fun sameText(a: String, b: String) = a.trim().replace(Regex("\\s+"), " ") == b.trim().replace(Regex("\\s+"), " ")

    private fun JsonObject.str(vararg keys: String): String? = keys.firstNotNullOfOrNull { k ->
        (get(k) as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
    }
}

class ChatAi(private val completer: TextCompleter, private val corrector: TextCompleter = completer) {
    /** The partner's answer to [learnerSaid] (the learner's own words), given the earlier [history]. */
    suspend fun reply(topic: String, history: List<ChatLine>, learnerSaid: String): List<PartnerSentence> =
        twice { retry -> ChatParser.reply(completer.complete(ChatPrompt.PARTNER_SYSTEM, ChatPrompt.partnerUser(topic, history, learnerSaid, retry))) }

    suspend fun correct(said: String, partnerSaid: String?, voice: Boolean): Correction =
        twice { retry -> ChatParser.correction(corrector.complete(ChatPrompt.CORRECT_SYSTEM, ChatPrompt.correctUser(said, partnerSaid, voice, retry)), said) }

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

/** A finished (or interrupted) conversation as a reading lesson. */
object ChatTranscript {
    private val SPANISH_MONTHS = listOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sept", "oct", "nov", "dic")

    fun title(topic: String, day: Int, month: Int): String =
        "Conversación: ${topic.trim().replace(Regex("\\s+"), " ").take(60)} · $day ${SPANISH_MONTHS[month - 1]}"

    /** One paragraph per turn (no speaker names: they would read as unknown words). */
    fun text(turns: List<String>): String = turns.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")

    /**
     * The sentences of a turn as the lesson will split them, so audio made for them is the lesson's listen-mode audio
     * and translations stored for them are found by the reader.
     */
    fun sentences(turn: String): List<String> = Tokenizer.tokenize(turn.trim()).let { t ->
        t.sentenceRanges.indices.filter { s -> t.sentenceTokens(s).any { it.isWord } }.map(t::sentenceText)
    }

    /**
     * Translations known for a turn's lesson sentences: [pieces] are the AI's sentence/English pairs; a lesson sentence
     * that matches one gets its English. A turn with one sentence and one English ([whole]) pairs them directly.
     */
    fun translations(turn: String, pieces: List<PartnerSentence>, whole: String? = null): List<Pair<String, String>> {
        val sentences = sentences(turn)
        if (whole != null && sentences.size == 1 && whole.isNotBlank()) return listOf(sentences[0] to whole.trim())
        val byText = pieces.filter { it.en.isNotBlank() }.associateBy({ norm(it.es) }, { it.en.trim() })
        return sentences.mapNotNull { s -> byText[norm(s)]?.let { s to it } }
    }

    private fun norm(s: String) = s.trim().replace(Regex("\\s+"), " ")
}
