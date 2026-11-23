package dev.arnv.bluke.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorSchemeContrastTest {
    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance() + 0.05
        val lb = b.luminance() + 0.05
        return maxOf(la, lb).toDouble() / minOf(la, lb).toDouble()
    }

    private fun everyStaticScheme(): List<Pair<String, ColorScheme>> = buildList {
        for (style in PaletteStyle.entries) {
            for (index in AccentColors.indices) {
                for (dark in listOf(false, true)) {
                    for (black in if (dark) listOf(false, true) else listOf(false)) {
                        val name = "${style.name} accent $index ${if (dark) "dark" else "light"}${if (black) " black" else ""}"
                        add(name to staticColorScheme(index, style, dark, black))
                    }
                }
            }
        }
    }

    @Test
    fun everyAccentHasDesignedTones() {
        assertEquals(AccentColors.size, ACCENT_TONES.size)
    }

    @Test
    fun onColorsReadOnTheirContainers() {
        val failures = everyStaticScheme().flatMap { (name, s) ->
            listOf(
                "onPrimary/primary" to (s.onPrimary to s.primary),
                "onPrimaryContainer/primaryContainer" to (s.onPrimaryContainer to s.primaryContainer),
                "onSecondary/secondary" to (s.onSecondary to s.secondary),
                "onSecondaryContainer/secondaryContainer" to (s.onSecondaryContainer to s.secondaryContainer),
                "onTertiary/tertiary" to (s.onTertiary to s.tertiary),
                "onTertiaryContainer/tertiaryContainer" to (s.onTertiaryContainer to s.tertiaryContainer),
                "onError/error" to (s.onError to s.error),
                "onErrorContainer/errorContainer" to (s.onErrorContainer to s.errorContainer),
                "onSurface/surface" to (s.onSurface to s.surface),
                "onBackground/background" to (s.onBackground to s.background),
                "onSurfaceVariant/surfaceVariant" to (s.onSurfaceVariant to s.surfaceVariant),
                "onSurfaceVariant/surfaceContainerHighest" to (s.onSurfaceVariant to s.surfaceContainerHighest),
                "inverseOnSurface/inverseSurface" to (s.inverseOnSurface to s.inverseSurface),
            ).mapNotNull { (pair, colors) ->
                val ratio = contrast(colors.first, colors.second)
                if (ratio < 4.5) "$name $pair = ${"%.2f".format(ratio)}" else null
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun primaryAndOutlineStandOutOnEverySurface() {
        val failures = everyStaticScheme().flatMap { (name, s) ->
            listOf(s.surface, s.surfaceContainerLow, s.surfaceContainer, s.surfaceContainerHigh, s.surfaceContainerHighest)
                .flatMap { surface ->
                    listOf("primary" to s.primary, "outline" to s.outline).mapNotNull { (role, color) ->
                        val ratio = contrast(color, surface)
                        if (ratio < 3.0) "$name $role on $surface = ${"%.2f".format(ratio)}" else null
                    }
                }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun aLitLedReadsOnTheSurfaceChrome() {
        for (index in AccentColors.indices) {
            val s = staticColorScheme(index, PaletteStyle.DEFAULT, dark = true)
            assertTrue(contrast(SurfaceTokens.LedLit, s.surfaceContainerHigh) >= 3.0)
        }
    }
}
