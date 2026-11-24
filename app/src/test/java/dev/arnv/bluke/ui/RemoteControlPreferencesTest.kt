package dev.arnv.bluke.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteControlPreferencesTest {
    @Test
    fun unknownModifierPositionFallsBackToOff() {
        assertEquals(TouchpadModifierPosition.OFF, TouchpadModifierPosition.fromPreference("future"))
        assertEquals(TouchpadModifierPosition.LEFT, TouchpadModifierPosition.OFF.next())
        assertEquals(TouchpadModifierPosition.LEFT, TouchpadModifierPosition.fromPreference("both"))
    }
}
