package dev.arnv.bluke.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Nothing may name one of the eight settings screens the one Settings screen replaced. The
 * shortcuts that used to open them are long-pressed in [InputModeShortcutsTest].
 */
class OldSettingsShortcutsTest {
    private val sources = File("src/main/java/dev/arnv/bluke").walkTopDown().filter { it.extension == "kt" }.toList()

    @Test
    fun noSourceNamesADeletedSettingsScreen() {
        val deleted = listOf(
            "LookAndFeelActivity", "DarkThemeActivity", "KeyboardSettingsActivity", "KeyboardLayoutsActivity",
            "TouchpadSettingsActivity", "ControllerSettingsActivity", "BehaviorActivity", "QuickCycleActivity",
        )
        val offenders = sources.flatMap { file -> deleted.filter { it in file.readText() }.map { "${file.name}: $it" } }
        assertEquals(emptyList<String>(), offenders)
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertEquals(emptyList<String>(), deleted.filter { it in manifest })
    }
}
