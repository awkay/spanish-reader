package net.awkay.spanishreader.ui

import androidx.compose.ui.graphics.Color
import net.awkay.spanishreader.core.vocab.WordStatus

object StatusColors {
    private val NEW_BLUE = Color(0xFF64B5F6)
    private val LEARNING_YELLOW = Color(0xFFFFC107)

    /** Outline of the reader's current word; a hue no status uses. */
    val CURSOR = Color(0xFFD81B60)

    /** Word background: blue for NEW, fading yellows for LEVEL_1..LEARNED, nothing for KNOWN/IGNORED. */
    fun background(status: WordStatus): Color = when {
        status == WordStatus.NEW -> NEW_BLUE.copy(alpha = 0.40f)
        status.isLearning -> LEARNING_YELLOW.copy(alpha = 0.75f * status.highlightIntensity)
        else -> Color.Transparent
    }

    /** Solid swatch for chips and badges. */
    fun swatch(status: WordStatus): Color = when {
        status == WordStatus.NEW -> NEW_BLUE
        status.isLearning -> LEARNING_YELLOW.copy(alpha = 0.3f + 0.7f * status.highlightIntensity)
        status == WordStatus.KNOWN -> Color(0xFF81C784)
        else -> Color(0xFFBDBDBD)
    }
}
