package dev.arnv.bluke.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class QuickCyclePreferencesTest {
    private val available = listOf("first", "second", "third")

    @Test
    fun toggleCanAddAndRemoveWhileKeepingOneChoice() {
        assertEquals(setOf("first", "second"), toggledCycleSelection(setOf("first"), "second"))
        assertEquals(setOf("first"), toggledCycleSelection(setOf("first", "second"), "second"))
        assertEquals(setOf("first"), toggledCycleSelection(setOf("first"), "first"))
    }

    @Test
    fun selectedChoiceMovesToFirstEnabledChoiceWhenExcluded() {
        assertEquals(
            "second",
            selectedOrFirstEnabled(
                selected = "removed",
                enabled = setOf("second", "third"),
                available = available,
            ),
        )
    }
}
