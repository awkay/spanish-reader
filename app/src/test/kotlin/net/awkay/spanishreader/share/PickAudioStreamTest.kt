package net.awkay.spanishreader.share

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PickAudioStreamTest {
    private fun opt(kbps: Int, opus: Boolean = false, original: Boolean = true, progressive: Boolean = true, url: String = "u$kbps$opus$original") =
        AudioOption(url, if (opus) "audio/webm" else "audio/mp4", if (opus) "webm" else "m4a", kbps, opus, original, progressive)

    @Test
    fun picksTheSmallestStreamThatStillSoundsFineForSpeech() {
        // YouTube's usual offer: m4a 48 / 128, opus 50 / 70 / 160, and a 33 kbps opus that is too thin.
        val offer = listOf(opt(128), opt(160, opus = true), opt(70, opus = true), opt(50, opus = true), opt(48), opt(33, opus = true))
        assertEquals(opt(48), pickAudioStream(offer))
        assertEquals(opt(50, opus = true), pickAudioStream(offer - opt(48)))
        // Opus wins a tie.
        assertEquals(opt(64, opus = true), pickAudioStream(listOf(opt(64), opt(64, opus = true))))
    }

    @Test
    fun skipsDubbedTracksAndNonProgressiveStreams() {
        assertEquals(opt(128), pickAudioStream(listOf(opt(50, opus = true, original = false), opt(128))))
        assertEquals(opt(128), pickAudioStream(listOf(opt(50, progressive = false), opt(128))))
        assertEquals(opt(70, original = false), pickAudioStream(listOf(opt(70, original = false)))) // only dubbed: still better than nothing
        assertNull(pickAudioStream(listOf(opt(50, progressive = false), opt(60, url = ""))))
    }

    @Test
    fun fallsBackToTheBestThinStreamOrAnUnknownBitrate() {
        assertEquals(opt(40), pickAudioStream(listOf(opt(33), opt(40))))
        assertEquals(opt(-1), pickAudioStream(listOf(opt(-1))))
        assertEquals(opt(50), pickAudioStream(listOf(opt(-1), opt(50))))
    }
}
