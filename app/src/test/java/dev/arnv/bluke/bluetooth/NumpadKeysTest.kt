package dev.arnv.bluke.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NumpadKeysTest {
    private val gridLegends = listOf(
        "7", "4", "1", "0", "8", "5", "2", ".", "9", "6", "3", "%",
        "/", "*", "-", "+", "⌫", "Enter", "=",
    )

    @Test
    fun everyLegendOnTheGridSendsAKey() {
        gridLegends.forEach { assertNotNull(it, numpadStroke(it)) }
    }

    @Test
    fun digitsAndOperatorsUseKeypadUsages() {
        val expected = mapOf(
            "1" to 0x59, "5" to 0x5D, "9" to 0x61, "0" to 0x62, "." to 0x63,
            "/" to 0x54, "*" to 0x55, "-" to 0x56, "+" to 0x57, "Enter" to 0x58,
        )
        expected.forEach { (legend, usage) -> assertEquals(legend, NumpadStroke(usage), numpadStroke(legend)) }
    }

    @Test
    fun onlyDigitsAndThePointDependOnNumLock() {
        val needing = gridLegends.filter { numpadStroke(it)!!.needsNumLock }.toSet()
        assertEquals(setOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "."), needing)
    }

    @Test
    fun percentIsShiftedFive() {
        val stroke = numpadStroke("%")!!
        assertTrue(stroke.shift)
        assertEquals(0x22, stroke.keyCode)
    }
}
