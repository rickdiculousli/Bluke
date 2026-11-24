package dev.arnv.bluke.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorRailTest {
    // labelMedium 16, labelLarge 20, labelSmall 16 line heights at font scale 1.
    private val scale1 = RailText(labelLine = 16.dp, largeLine = 20.dp, nameLine = 16.dp, widestLatency = 40.dp, mirrorWord = 38.dp)
    private val scale2 = RailText(labelLine = 32.dp, largeLine = 40.dp, nameLine = 32.dp, widestLatency = 80.dp, mirrorWord = 76.dp)

    // Fixed: 3 × 48 + 5 × 8 = 184. A labelled item is 32 + 8 + label.
    @Test
    fun theRichestArrangementThatFitsWins() {
        // Both labels, the name and a large rate: 184 + 56 × 2 + 20 + 16 + 16 = 348.
        assertEquals(RailLayout(ModeLabels.BOTH, showName = true, largeFps = true, wordOnNextMode = true), fitRail(348.dp, scale1))
        // One label and the name: 184 + 56 + 48 + 32 + 16 = 336.
        assertEquals(RailLayout(ModeLabels.SELECTED, showName = true, largeFps = false, wordOnNextMode = true), fitRail(347.dp, scale1))
        // One label, no name: 320.
        assertEquals(RailLayout(ModeLabels.SELECTED, showName = false, largeFps = false, wordOnNextMode = true), fitRail(320.dp, scale1))
        assertEquals(RailLayout(ModeLabels.NONE, showName = false, largeFps = false, wordOnNextMode = true), fitRail(319.dp, scale1))
    }

    @Test
    fun largeTextWidensTheRailAndSwapsTheWordForItsGlyph() {
        assertEquals(RAIL_WIDTH, railWidth(scale1))
        assertEquals(96.dp, railWidth(scale2))
        assertTrue(fitRail(1000.dp, scale1).wordOnNextMode)
        // 12 + 76 + 18 + 4 = 110 > 96 − 16.
        assertFalse(fitRail(1000.dp, scale2).wordOnNextMode)
    }
}
