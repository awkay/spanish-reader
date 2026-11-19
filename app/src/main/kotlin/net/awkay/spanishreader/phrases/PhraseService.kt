package net.awkay.spanishreader.phrases

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.awkay.spanishreader.core.gloss.GlosserFactory
import net.awkay.spanishreader.core.gloss.TextCompleter
import net.awkay.spanishreader.core.practice.PhraseCard
import net.awkay.spanishreader.core.practice.PhraseDeck
import net.awkay.spanishreader.core.practice.PhraseMode
import net.awkay.spanishreader.core.practice.PhrasePool
import net.awkay.spanishreader.core.practice.PhraseProgress
import net.awkay.spanishreader.core.practice.PhraseWriter
import net.awkay.spanishreader.data.AppDatabase
import net.awkay.spanishreader.data.PracticePhrasesEntity
import net.awkay.spanishreader.data.PracticeScoreEntity
import net.awkay.spanishreader.data.SettingsRepository
import net.awkay.spanishreader.gloss.GlossService
import net.awkay.spanishreader.share.WebShareService

/** After an answer: the sentence's new score, and whether that retired it. */
data class PhraseAnswer(val progress: PhraseProgress, val retired: Boolean)

/**
 * Say-it / listen on Android (see core PhrasePool.kt): scores per mode and sentence (this device's, synced), and the
 * deck's AI-written sentences, shared through the web server. When retirements leave a deck short of
 * [PhrasePool.SIZE] sentences in play, the AI writes more (the primary provider writes, the "improve" one reviews).
 */
class PhraseService(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val web: WebShareService,
    private val writerFor: suspend () -> Pair<TextCompleter, TextCompleter>? = {
        val s = settings.current()
        if (!s.primaryGlosser.isComplete) null
        else GlosserFactory.createCompleter(s.primaryGlosser, GlossService.sharedHttp) to
            GlosserFactory.createCompleter(s.improveGlosser.takeIf { it.isComplete } ?: s.primaryGlosser, GlossService.sharedHttp)
    },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }
    private val cardList = ListSerializer(PhraseCard.serializer())
    private val writing = Mutex()

    suspend fun progress(): Map<String, PhraseProgress> = db.practice().scores().associate { it.toCore().let { p -> p.key to p } }

    /** The deck's AI-written sentences on this device. */
    suspend fun generated(deck: PhraseDeck): List<PhraseCard> =
        db.practice().phrases(deck.id)?.let { decode(it.json) }.orEmpty()

    suspend fun pool(deck: PhraseDeck, mode: PhraseMode): List<PhraseCard> = PhrasePool.pool(deck, mode, generated(deck), progress())

    suspend fun answer(mode: PhraseMode, card: PhraseCard, right: Boolean): PhraseAnswer {
        val before = progress()[PhrasePool.key(mode.id, card.id)]
        val after = PhrasePool.record(before, mode.id, card.id, right, clock())
        db.practice().upsertScores(listOf(after.toEntity()))
        return PhraseAnswer(after, !PhrasePool.retired(before) && PhrasePool.retired(after))
    }

    /**
     * Brings the deck's AI-written sentences up to what the pool needs: first whatever the server has, then new ones
     * written now and shared. Without the web app or an AI provider, the hand-written reserve fills in. Returns
     * how many sentences the deck has from the AI afterwards.
     */
    suspend fun topUp(deck: PhraseDeck): Int = writing.withLock {
        var cards = generated(deck)
        if (PhrasePool.shortfall(deck, cards, progress()) == 0) return cards.size
        if (webConfigured()) fetchShared(deck)?.takeIf { it.size > cards.size }?.let { cards = it; keep(deck, it) }
        var batches = 0
        while (PhrasePool.shortfall(deck, cards, progress()) > 0 && batches++ < MAX_BATCHES) {
            val (writer, reviewer) = writerFor() ?: break
            val avoid = (deck.cards + cards).map { it.english }
            val written = PhraseWriter(writer, reviewer).write(deck, avoid, cards.size)
            cards = if (webConfigured()) share(deck, cards.size, written) ?: (cards + written) else cards + written
            keep(deck, cards)
        }
        cards.size
    }

    private suspend fun keep(deck: PhraseDeck, cards: List<PhraseCard>) =
        db.practice().upsertPhrases(PracticePhrasesEntity(deck.id, json.encodeToString(cardList, cards), clock()))

    private suspend fun webConfigured(): Boolean = settings.current().webAccessCode.isNotBlank()

    private suspend fun fetchShared(deck: PhraseDeck): List<PhraseCard>? {
        val (code, text) = runCatching { web.request("GET", "/api/phrases/${deck.id}") }.getOrNull() ?: return null
        if (code != 200) return null
        return (Json.parseToJsonElement(text) as? JsonObject)?.get("cards")?.let { decode(it.toString()) }
    }

    /** Appends [cards] after the [after] the server had; if someone else added first, the server's list comes back. */
    private suspend fun share(deck: PhraseDeck, after: Int, cards: List<PhraseCard>): List<PhraseCard>? {
        val body = buildJsonObject { put("after", after); put("cards", json.encodeToJsonElement(cardList, cards)) }
        val (code, text) = runCatching { web.request("PUT", "/api/phrases/${deck.id}", body.toString()) }.getOrNull() ?: return null
        if (code != 200) return null
        return (Json.parseToJsonElement(text) as? JsonObject)?.get("cards")?.let { decode(it.toString()) }
    }

    private fun decode(text: String): List<PhraseCard>? = runCatching { json.decodeFromString(cardList, text) }.getOrNull()

    private fun PracticeScoreEntity.toCore() = PhraseProgress(mode, cardId, score, right, wrong, lastMillis)

    private fun PhraseProgress.toEntity() = PracticeScoreEntity(mode, cardId, score, right, wrong, lastMillis)

    companion object {
        /** Batches written in one go at most (a fresh install with many retirements synced down). */
        const val MAX_BATCHES = 5
    }
}
