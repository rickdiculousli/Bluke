package dev.arnv.bluke.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardModifierStylesTest {
    /** The legends and HID usages of the space bar's row, left to right, minus arrows. */
    private fun spaceRow(type: KeyboardLayoutType, style: KeyboardModifierStyle): List<Pair<String, Int>> =
        KeyboardLayouts.getLayout(type, modifierStyle = style)
            .single { row -> row.any { it.keyCode == KeyboardLayouts.KEY_SPACE } }
            .filter { it.keyCode !in setOf(KeyboardLayouts.KEY_LEFT, KeyboardLayouts.KEY_DOWN, KeyboardLayouts.KEY_RIGHT) }
            .map { (if (it.keyCode == KeyboardLayouts.KEY_SPACE) "space" else it.legend) to it.keyCode }

    private val ctrl = KeyboardLayouts.MOD_LCTRL
    private val alt = KeyboardLayouts.MOD_LALT
    private val gui = KeyboardLayouts.MOD_LWIN
    private val space = KeyboardLayouts.KEY_SPACE
    private val fn = KeyboardLayouts.KEY_FN

    @Test fun pcStyleKeepsEveryLayoutAsDrawn() {
        assertEquals(
            listOf("Ctrl" to ctrl, "Win" to gui, "Alt" to alt, "space" to space, "Alt" to alt, "Fn" to fn, "Ctrl" to ctrl),
            spaceRow(KeyboardLayoutType.OBLIVION_75, KeyboardModifierStyle.PC),
        )
    }

    @Test fun macStylePutsCommandBesideTheSpaceBar() {
        assertEquals(
            listOf("Ctrl" to ctrl, "Opt" to alt, "Cmd" to gui, "space" to space, "Opt" to alt, "Fn" to fn, "Ctrl" to ctrl),
            spaceRow(KeyboardLayoutType.OBLIVION_75, KeyboardModifierStyle.MAC),
        )
    }

    @Test fun macStyleGivesEachLayoutCommandBesideTheSpaceBar() {
        // HHKB already places its GUI keys beside the space bar.
        assertEquals(
            listOf("Opt" to alt, "Cmd" to gui, "space" to space, "Cmd" to gui, "Opt" to alt),
            spaceRow(KeyboardLayoutType.HHKB_60, KeyboardModifierStyle.MAC),
        )
        // Ctrl Alt | Win: Command goes to the left thumb, Option to the right.
        assertEquals(
            listOf("Ctrl" to ctrl, "Cmd" to gui, "space" to space, "Opt" to alt),
            spaceRow(KeyboardLayoutType.OLIVIA_75, KeyboardModifierStyle.MAC),
        )
    }

    @Test fun macStyleKeepsEachSlotsPlaceAndSize() {
        val pc = KeyboardLayouts.getLayout(KeyboardLayoutType.OBLIVION_75).flatten()
        val mac = KeyboardLayouts.getLayout(KeyboardLayoutType.OBLIVION_75, modifierStyle = KeyboardModifierStyle.MAC).flatten()
        assertEquals(pc.map { Triple(it.x, it.y, it.widthRatio) }, mac.map { Triple(it.x, it.y, it.widthRatio) })
        assertEquals(pc.map { it.styleId }, mac.map { it.styleId })
    }

    @Test fun unknownPreferenceFallsBackToPc() {
        assertEquals(KeyboardModifierStyle.PC, KeyboardModifierStyle.fromPreference("amiga"))
        assertEquals(KeyboardModifierStyle.MAC, KeyboardModifierStyle.fromPreference("mac"))
    }
}
