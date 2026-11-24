package dev.arnv.bluke.ui.settings

import android.content.Context
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.test.core.app.ApplicationProvider
import dev.arnv.bluke.sound.SwitchType
import dev.arnv.bluke.testing.captureMatrix
import dev.arnv.bluke.ui.InputMode
import dev.arnv.bluke.ui.KeyboardSound
import dev.arnv.bluke.ui.KeyboardThemeCatalog
import dev.arnv.bluke.ui.KeyboardTuneOptions
import dev.arnv.bluke.ui.KeyboardTuneState
import dev.arnv.bluke.ui.theme.AppearanceSettings
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h1600dp-port-hdpi", sdk = [36])
class SettingsScreenshotTest {
    private val fontScales = listOf(1.0f, 1.3f, 2.0f)

    // Dynamic color off so Appearance shows every option; two modes left out of the cycle so the
    // Input modes section shows both states.
    private val state = SettingsState(
        appearance = AppearanceSettings(dynamicColor = false, accentIndex = 9),
        cycleModes = setOf(InputMode.KEYBOARD.preferenceKey, InputMode.TOUCHPAD.preferenceKey),
        developerMode = true,
        versionName = "1.1.0",
    )

    private val keyboard = KeyboardSettingsState(
        tune = KeyboardTuneState(sound = KeyboardSound.BuiltIn(SwitchType.HOLY_PANDA)),
        options = KeyboardTuneOptions(KeyboardThemeCatalog.builtIns, emptyList()),
        keySensitivity = 6f,
        keySoundsOn = true,
    )

    private object NoKeyboardActions : KeyboardSettingsActions {
        override fun setTune(next: KeyboardTuneState) = Unit
        override fun setKeySensitivity(value: Float) = Unit
        override fun openThemes() = Unit
        override fun selectSound(id: String) = Unit
        override fun turnSoundsOff() = Unit
        override fun playSound(id: String) = Unit
        override fun openSoundPacks() = Unit
    }

    /** Writes the fixture's accent and palette where the theme reads them, so the screen is drawn in them. */
    private fun capture(name: String, expanded: SettingsSection?, state: SettingsState = this.state) {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val saved = prefs.all.filterKeys { it == ACCENT || it == PALETTE }
        prefs.edit().putInt(ACCENT, state.appearance.accentIndex).putString(PALETTE, state.appearance.paletteStyle.preferenceValue).commit()
        try {
            captureFixture(name, expanded, state)
        } finally {
            prefs.edit().remove(ACCENT).remove(PALETTE).apply {
                (saved[ACCENT] as? Int)?.let { putInt(ACCENT, it) }
                (saved[PALETTE] as? String)?.let { putString(PALETTE, it) }
            }.commit()
        }
    }

    private fun captureFixture(name: String, expanded: SettingsSection?, state: SettingsState) {
        captureMatrix(name = name, fontScales = fontScales) {
            SettingsContent(
                state = state,
                actions = SettingsActions.None,
                keyboard = keyboard,
                keyboardActions = NoKeyboardActions,
                expanded = setOfNotNull(expanded),
                onToggleSection = {},
                connectionAdvancedExpanded = expanded == SettingsSection.CONNECTION,
                onToggleConnectionAdvanced = {},
                onBack = {},
                listState = rememberLazyListState(initialFirstVisibleItemIndex = expanded?.let(::settingsListIndex) ?: 0),
                // As the activity does for an intent that names a section.
                startCollapsed = expanded != null,
            )
        }
    }

    private companion object {
        const val ACCENT = "accent_color_index"
        const val PALETTE = "palette_style"
    }

    @Test
    fun collapsed() = capture("settings-collapsed", expanded = null)

    @Test
    fun appearance() = capture("settings-appearance", SettingsSection.APPEARANCE)

    @Test
    fun feedback() = capture("settings-feedback", SettingsSection.FEEDBACK)

    @Test
    fun touchpad() = capture("settings-touchpad", SettingsSection.TOUCHPAD)

    @Test
    fun controller() = capture("settings-controller", SettingsSection.CONTROLLER)

    @Test
    fun multimedia() = capture("settings-multimedia", SettingsSection.MULTIMEDIA)

    @Test
    fun inputModes() = capture("settings-input-modes", SettingsSection.INPUT_MODES)

    @Test
    fun inputModesLast() = capture(
        "settings-input-modes-last",
        SettingsSection.INPUT_MODES,
        state.copy(cycleModes = setOf(InputMode.KEYBOARD.preferenceKey)),
    )

    @Test
    fun connection() = capture("settings-connection", SettingsSection.CONNECTION)

    @Test
    fun helpAndAbout() = capture("settings-help-and-about", SettingsSection.HELP_AND_ABOUT)
}
