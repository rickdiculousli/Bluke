package dev.arnv.bluke.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.arnv.bluke.ui.KeyboardTuneOptions
import dev.arnv.bluke.ui.KeyboardTuneState
import dev.arnv.bluke.ui.theme.AppearanceSettings
import dev.arnv.bluke.ui.theme.PaletteStyle
import dev.arnv.bluke.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings › Appearance shows the controls the theme's `appearanceOptions` lists, and no other. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h1600dp-port-hdpi", sdk = [36])
class SettingsAppearanceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val chosenStyles = mutableListOf<PaletteStyle>()
    private val actions = object : SettingsActions by SettingsActions.None {
        override fun setPaletteStyle(style: PaletteStyle) {
            chosenStyles += style
        }
    }

    private fun show(appearance: AppearanceSettings) {
        compose.setContent {
            SettingsContent(
                state = SettingsState(appearance = appearance),
                actions = actions,
                keyboard = KeyboardSettingsState(KeyboardTuneState(), KeyboardTuneOptions(emptyList(), emptyList()), 6f, true),
                keyboardActions = NoKeyboardActions,
                expanded = setOf(SettingsSection.APPEARANCE),
                onToggleSection = {},
                connectionAdvancedExpanded = false,
                onToggleConnectionAdvanced = {},
                onBack = {},
            )
        }
        compose.waitForIdle()
    }

    @Test
    fun wallpaperColoursHideTheAccentAndPaletteStyle() {
        show(AppearanceSettings(themeMode = ThemeMode.LIGHT, dynamicColor = true))
        compose.onNodeWithText("Dynamic colors").assertExists()
        compose.onNodeWithText("Palette style").assertDoesNotExist()
        compose.onNodeWithText("Accent color").assertDoesNotExist()
        // A light scheme has no dark background to turn black.
        compose.onNodeWithText("Pure black").assertDoesNotExist()
    }

    @Test
    fun aDarkStaticSchemeShowsAccentPaletteAndPureBlack() {
        show(AppearanceSettings(themeMode = ThemeMode.DARK, dynamicColor = false))
        compose.onNodeWithText("Accent color").assertExists()
        compose.onNodeWithText("Palette style").assertExists()
        compose.onNodeWithText("Pure black").assertExists()
    }

    @Test
    fun thePaletteStylesOfferedAreTheOnesThatExist() {
        show(AppearanceSettings(themeMode = ThemeMode.LIGHT, dynamicColor = false, paletteStyle = PaletteStyle.MONOCHROME))
        PaletteStyle.entries.forEach { compose.onNodeWithText(it.label).assertExists() }
        compose.onNodeWithText("Vibrant").assertDoesNotExist()

        compose.onNode(hasText(PaletteStyle.DEFAULT.label) and hasClickAction()).performScrollTo().performClick()
        assertEquals(listOf(PaletteStyle.DEFAULT), chosenStyles)
    }

    private object NoKeyboardActions : KeyboardSettingsActions {
        override fun setTune(next: KeyboardTuneState) = Unit
        override fun setKeySensitivity(value: Float) = Unit
        override fun openThemes() = Unit
        override fun selectSound(id: String) = Unit
        override fun turnSoundsOff() = Unit
        override fun playSound(id: String) = Unit
        override fun openSoundPacks() = Unit
    }
}
