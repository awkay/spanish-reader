package net.awkay.spanishreader.core.gloss

import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Where a background pre-gloss run is, as shown on the lesson's card in the library: first the window's sentences are
 * translated (and scanned for idioms), then its words are glossed. [startedMillis] is when the current phase began and
 * [updatedMillis] when [done] was last counted, so the speed and time left follow from the numbers alone.
 * [fromPage] is zero-based; [toPage] is exclusive.
 */
data class PreGlossProgress(
    val phase: Phase,
    val done: Int,
    val total: Int,
    val startedMillis: Long = 0,
    val updatedMillis: Long = 0,
    val fromPage: Int = 0,
    val toPage: Int = 0,
) {
    enum class Phase(val id: String) {
        STARTING("starting"), SENTENCES("sentences"), WORDS("words");

        companion object {
            fun byId(id: String?): Phase = entries.firstOrNull { it.id == id } ?: STARTING
        }
    }

    val fraction: Float? get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null

    /** Items per minute so far; null until there is something to measure. */
    val perMinute: Double?
        get() {
            val elapsed = updatedMillis - startedMillis
            return if (done > 0 && elapsed >= MIN_ELAPSED_MS) done * 60_000.0 / elapsed else null
        }

    /** Minutes left at the current speed (rounded up); null when unknown. */
    val minutesLeft: Int?
        get() = perMinute?.let { rate -> ceil((total - done).coerceAtLeast(0) / rate).toInt() }

    /** One line: "Glossing words 120/300 · 35 a minute · about 6 min left · pages 4–6". */
    fun describe(): String {
        val what = when (phase) {
            Phase.STARTING -> "Pre-gloss starting…"
            Phase.SENTENCES -> "Translating sentences $done/$total"
            Phase.WORDS -> "Glossing words $done/$total"
        }
        val speed = perMinute?.let { r -> if (r >= 1) "${r.roundToInt()} a minute" else "under 1 a minute" }
        val left = minutesLeft?.let { m ->
            when {
                done >= total -> null
                m <= 1 -> "under a minute left"
                m < 90 -> "about $m min left"
                else -> "about ${(m + 30) / 60} h left"
            }
        }
        val pages = when {
            toPage <= fromPage -> null
            toPage - fromPage == 1 -> "page ${fromPage + 1}"
            else -> "pages ${fromPage + 1}–$toPage"
        }
        return listOfNotNull(what, speed.takeIf { phase != Phase.STARTING }, left, pages).joinToString(" · ")
    }

    companion object {
        /** Too short a time gives silly speeds. */
        const val MIN_ELAPSED_MS = 3_000L
    }
}
