package dev.arnv.bluke.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class AppearanceTest {
    @Test
    fun everyRetiredPaletteStyleReadsAsDefault() {
        for (old in listOf("Tonal Spot", "Vibrant", "Expressive", "Rainbow", "Fruit Salad", "Fidelity", "Content", "Neutral", null, "")) {
            assertEquals(old, PaletteStyle.DEFAULT, PaletteStyle.fromPreference(old))
        }
        assertEquals(PaletteStyle.MONOCHROME, PaletteStyle.fromPreference("Monochrome"))
    }

    @Test
    fun wallpaperColoursHideAccentAndPaletteStyle() {
        assertEquals(
            listOf(AppearanceOption.THEME_MODE, AppearanceOption.DYNAMIC_COLOR),
            appearanceOptions(dynamicColor = true, dark = false, dynamicAvailable = true),
        )
        assertEquals(
            listOf(
                AppearanceOption.THEME_MODE, AppearanceOption.DYNAMIC_COLOR,
                AppearanceOption.ACCENT, AppearanceOption.PALETTE_STYLE,
            ),
            appearanceOptions(dynamicColor = false, dark = false, dynamicAvailable = true),
        )
    }

    @Test
    fun pureBlackOnlyAppliesToADarkScheme() {
        assertEquals(
            listOf(AppearanceOption.THEME_MODE, AppearanceOption.DYNAMIC_COLOR, AppearanceOption.BLACK_BACKGROUND),
            appearanceOptions(dynamicColor = true, dark = true, dynamicAvailable = true),
        )
    }

    @Test
    fun withoutWallpaperColoursTheirSwitchIsHiddenAndTheAccentShows() {
        assertEquals(
            listOf(AppearanceOption.THEME_MODE, AppearanceOption.ACCENT, AppearanceOption.PALETTE_STYLE),
            appearanceOptions(dynamicColor = true, dark = false, dynamicAvailable = false),
        )
    }
}
