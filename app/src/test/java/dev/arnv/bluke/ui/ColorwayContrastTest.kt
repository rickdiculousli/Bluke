package dev.arnv.bluke.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every built-in colourway's legends read on the keycap they're drawn on: WCAG 2 AA's 4.5:1 for
 * normal text, since a legend at its largest (13sp bold) is under WCAG's large-text size (14pt bold).
 * KeyCap draws both the main and the shifted legend in the key's legend colour at full opacity.
 */
class ColorwayContrastTest {
    @Test
    fun everyPaletteLegendReachesNormalTextContrastOnItsKeycap() {
        val failures = Colorways.PALETTES.flatMap { (type, palette) ->
            listOf(
                "alpha" to (palette.alphaLegend to palette.alphaBg),
                "modifier" to (palette.modLegend to palette.modBg),
                "accent" to (palette.accentLegend to palette.accentBg),
            ).mapNotNull { (role, colors) ->
                val ratio = contrast(colors.first, colors.second)
                if (ratio < 4.5) "$type $role legend: ${"%.2f".format(ratio)}:1" else null
            }
        }
        assertTrue("Legends under 4.5:1:\n${failures.joinToString("\n")}", failures.isEmpty())
    }

    @Test
    fun everyBuiltInThemeUsesItsPalettesLegends() {
        KeyboardThemeCatalog.builtIns.forEach { theme ->
            listOf(theme.alphaStyle, theme.modifierStyle, theme.accentStyle).forEach { style ->
                val ratio = contrast(Color(style.legendArgb), Color(style.backgroundArgb))
                assertTrue("${theme.name}: ${"%.2f".format(ratio)}:1", ratio >= 4.5)
            }
        }
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
}
