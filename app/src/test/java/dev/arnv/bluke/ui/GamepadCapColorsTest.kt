package dev.arnv.bluke.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import dev.arnv.bluke.ui.theme.AccentColors
import dev.arnv.bluke.ui.theme.PaletteStyle
import dev.arnv.bluke.ui.theme.staticColorScheme
import org.junit.Assert.assertTrue
import org.junit.Test

/** A pressed gamepad cap (shoulders, center buttons, sticks, D-pad arms) must read as pressed at a glance. */
class GamepadCapColorsTest {
    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance() + 0.05
        val lb = b.luminance() + 0.05
        return maxOf(la, lb).toDouble() / minOf(la, lb).toDouble()
    }

    @Test
    fun aPressedCapStandsAtLeast3To1OffTheRestingCapInEveryScheme() {
        val failures = PaletteStyle.entries.flatMap { style ->
            AccentColors.indices.flatMap { index ->
                listOf(false to false, true to false, true to true).mapNotNull { (dark, black) ->
                    val scheme = staticColorScheme(index, style, dark, black)
                    val (pressed, pressedLabel) = capColors(scheme, pressed = true)
                    val (rest, _) = capColors(scheme, pressed = false)
                    val fill = contrast(pressed, rest)
                    val label = contrast(pressedLabel, pressed)
                    if (fill < 3.0 || label < 4.5) {
                        "$style accent $index dark=$dark black=$black: pressed/rest ${"%.2f".format(fill)}, label ${"%.2f".format(label)}"
                    } else null
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
