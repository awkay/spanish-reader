package net.awkay.spanishreader.core.gloss

import net.awkay.spanishreader.core.gloss.PreGlossProgress.Phase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PreGlossProgressTest {
    @Test
    fun `words show count, speed, time left and pages`() {
        // 120 words in 2 minutes: 60 a minute, 180 left → 3 minutes.
        val p = PreGlossProgress(Phase.WORDS, 120, 300, startedMillis = 1_000, updatedMillis = 121_000, fromPage = 3, toPage = 6)
        assertEquals(60.0, p.perMinute)
        assertEquals(3, p.minutesLeft)
        assertEquals(0.4f, p.fraction)
        assertEquals("Glossing words 120/300 · 60 a minute · about 3 min left · pages 4–6", p.describe())
    }

    @Test
    fun `no speed until there is something to measure`() {
        val first = PreGlossProgress(Phase.WORDS, 0, 300, 1_000, 1_000, 0, 1)
        assertNull(first.perMinute)
        assertEquals("Glossing words 0/300 · page 1", first.describe())
        // Too soon after starting for a sensible speed.
        assertNull(PreGlossProgress(Phase.WORDS, 20, 300, 1_000, 2_000).perMinute)
    }

    @Test
    fun `slow runs, nearly done runs and the other phases`() {
        assertEquals(
            "Glossing words 20/300 · 10 a minute · about 28 min left",
            PreGlossProgress(Phase.WORDS, 20, 300, 0, 120_000).describe(),
        )
        assertEquals(
            "Glossing words 290/300 · 145 a minute · under a minute left",
            PreGlossProgress(Phase.WORDS, 290, 300, 0, 120_000).describe(),
        )
        assertEquals("about 3 h left", PreGlossProgress(Phase.WORDS, 10, 1_000, 0, 120_000).describe().split(" · ")[2])
        assertEquals("under 1 a minute", PreGlossProgress(Phase.WORDS, 1, 300, 0, 120_000).describe().split(" · ")[1])
        assertEquals("Glossing words 300/300 · 150 a minute", PreGlossProgress(Phase.WORDS, 300, 300, 0, 120_000).describe())
        assertEquals(
            "Translating sentences 15/45 · 15 a minute · about 2 min left · pages 1–3",
            PreGlossProgress(Phase.SENTENCES, 15, 45, 0, 60_000, 0, 3).describe(),
        )
        assertEquals("Pre-gloss starting… · pages 2–4", PreGlossProgress(Phase.STARTING, 0, 0, 0, 0, 1, 4).describe())
        assertNull(PreGlossProgress(Phase.STARTING, 0, 0).fraction)
        assertEquals(Phase.WORDS, Phase.byId("words"))
        assertEquals(Phase.STARTING, Phase.byId(null))
    }
}
