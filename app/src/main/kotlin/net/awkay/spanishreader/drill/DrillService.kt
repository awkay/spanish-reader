package net.awkay.spanishreader.drill

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.awkay.spanishreader.core.gloss.GlosserFactory
import net.awkay.spanishreader.core.gloss.TextCompleter
import net.awkay.spanishreader.core.practice.CommonVerb
import net.awkay.spanishreader.core.practice.Drill
import net.awkay.spanishreader.core.practice.DrillCard
import net.awkay.spanishreader.core.practice.DrillProgress
import net.awkay.spanishreader.core.practice.DrillVerdict
import net.awkay.spanishreader.core.practice.DrillWriter
import net.awkay.spanishreader.core.vocab.WordStatus
import net.awkay.spanishreader.data.AppDatabase
import net.awkay.spanishreader.data.DrillCardsEntity
import net.awkay.spanishreader.data.DrillProgressEntity
import net.awkay.spanishreader.data.SettingsRepository
import net.awkay.spanishreader.data.VocabRepository
import net.awkay.spanishreader.gloss.GlossService
import net.awkay.spanishreader.share.WebShareService
import java.io.IOException

data class DrillAnswer(val verdict: DrillVerdict, val learned: Boolean)

/**
 * Lightning verb drill on Android (see core VerbDrill.kt). A verb's cards come from this device, else the web app's
 * shared set, else are written now by the AI (the primary provider writes, the "improve" one reviews) and shared.
 * Progress is this device's and travels with sync.
 */
class DrillService(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val vocab: VocabRepository,
    private val web: WebShareService,
    private val writerFor: suspend () -> Pair<TextCompleter, TextCompleter>? = {
        val s = settings.current()
        if (!s.primaryGlosser.isComplete) null
        else GlosserFactory.createCompleter(s.primaryGlosser, GlossService.sharedHttp) to
            GlosserFactory.createCompleter(s.improveGlosser.takeIf { it.isComplete } ?: s.primaryGlosser, GlossService.sharedHttp)
    },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val cardList = ListSerializer(DrillCard.serializer())
    private val writing = Mutex()

    fun observeProgress(): Flow<Map<String, DrillProgress>> =
        db.drill().observeProgress().map { rows -> rows.associate { it.cardId to it.toCore() } }

    suspend fun progress(): Map<String, DrillProgress> = db.drill().progress().associate { it.cardId to it.toCore() }

    /** Every card set on this device, by verb. */
    suspend fun cardSets(): Map<String, List<DrillCard>> =
        db.drill().allCards().mapNotNull { e -> decode(e.json)?.let { e.verb to it } }.toMap()

    /** [verb]'s cards: here, else the web app's shared set, else written by the AI now (and shared). */
    suspend fun cardsFor(verb: CommonVerb): List<DrillCard> = writing.withLock {
        db.drill().cards(verb.infinitive)?.let { decode(it.json) }?.let { return it }
        val shared = if (webConfigured()) fetchShared(verb.infinitive) else null
        val cards = shared ?: run {
            val (writer, reviewer) = writerFor() ?: throw IOException("Glossing is not configured. Set it up in Settings.")
            val written = DrillWriter(writer, reviewer).write(verb)
            if (webConfigured()) runCatching { share(verb.infinitive, written) }.getOrNull() ?: written else written
        }
        db.drill().upsertCards(DrillCardsEntity(verb.infinitive, json.encodeToString(cardList, cards), clock()))
        cards
    }

    /**
     * Records an answer. A card answered right for the TO_LEARN-th time is learned, and its verb words go into the
     * vocabulary as Known (Tony asked for drilled forms to count as known).
     */
    suspend fun answer(card: DrillCard, typed: String): DrillAnswer {
        val verdict = Drill.check(card, typed)
        val before = db.drill().progress().firstOrNull { it.cardId == card.id }?.toCore()
        val after = Drill.record(before, card.id, verdict == DrillVerdict.CORRECT, clock())
        db.drill().upsertProgress(listOf(after.toEntity()))
        val learned = !Drill.isLearned(before) && Drill.isLearned(after)
        if (learned) {
            val words = Drill.answerWords(card)
            for (w in words) {
                val cur = vocab.get(w)
                if (cur?.status == WordStatus.KNOWN || cur?.status == WordStatus.IGNORED) continue
                vocab.setStatus(w, WordStatus.KNOWN, card.filled)
                if (w == words.last() && vocab.get(w)?.lemma == null) vocab.annotate(w, card.verb, null)
            }
        }
        return DrillAnswer(verdict, learned)
    }

    /** Sets a bad card aside: it no longer comes up and counts as done. */
    suspend fun hide(card: DrillCard) {
        val cur = db.drill().progress().firstOrNull { it.cardId == card.id }?.toCore() ?: DrillProgress(card.id)
        db.drill().upsertProgress(listOf(cur.copy(hidden = true).toEntity()))
    }

    private suspend fun webConfigured(): Boolean = settings.current().webAccessCode.isNotBlank()

    private suspend fun fetchShared(verb: String): List<DrillCard>? {
        val (code, text) = runCatching { web.request("GET", "/api/drill/${verb.encodePath()}") }.getOrNull() ?: return null
        if (code != 200) return null
        return (Json.parseToJsonElement(text) as? JsonObject)?.get("cards")?.let { decode(it.toString()) }
    }

    /** The first set stored on the server wins, so this returns the server's cards. */
    private suspend fun share(verb: String, cards: List<DrillCard>): List<DrillCard>? {
        val body = buildJsonObject { put("cards", json.encodeToJsonElement(cardList, cards)) }
        val (code, text) = web.request("PUT", "/api/drill/${verb.encodePath()}", body.toString())
        if (code != 200) return null
        return (Json.parseToJsonElement(text) as? JsonObject)?.get("cards")?.let { decode(it.toString()) }
    }

    private fun decode(text: String): List<DrillCard>? = runCatching { json.decodeFromString(cardList, text) }.getOrNull()

    private fun String.encodePath() = java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")

    private fun DrillProgressEntity.toCore() = DrillProgress(cardId, correct, misses, lastMillis, hidden)

    private fun DrillProgress.toEntity() = DrillProgressEntity(cardId, correct, misses, lastMillis, hidden)
}
