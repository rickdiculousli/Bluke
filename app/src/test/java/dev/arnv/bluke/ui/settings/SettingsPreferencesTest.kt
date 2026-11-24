package dev.arnv.bluke.ui.settings

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import dev.arnv.bluke.bluetooth.GamepadDpadOutputMode
import dev.arnv.bluke.ui.GamepadTuneState
import dev.arnv.bluke.ui.InputMode
import dev.arnv.bluke.ui.MultimediaTuneState
import dev.arnv.bluke.ui.PointerTuneState
import dev.arnv.bluke.ui.TouchpadModifierPosition
import dev.arnv.bluke.ui.TouchpadTuneState
import dev.arnv.bluke.ui.theme.AppearanceSettings
import dev.arnv.bluke.ui.theme.PaletteStyle
import dev.arnv.bluke.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SettingsPreferencesTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("settings-preferences-test", Context.MODE_PRIVATE)
    private val actions = PreferenceSettingsActions(prefs, {}, {}, {})

    private fun read() = readSettingsState(prefs, dynamicColorAvailable = true, versionName = "9.9")

    @Before
    fun clear() = prefs.edit(commit = true) { clear() }

    @Test
    fun anEmptyStoreReadsTheAppsDefaults() {
        val state = read()
        assertEquals(1.5f, state.touchpad.speeds.pointerSpeed)
        assertEquals(1f, state.touchpad.speeds.scrollSpeed)
        assertEquals(true, state.appearance.dynamicColor)
        assertEquals(true, state.hapticsEnabled)
        assertEquals(true, state.autoConnect)
        assertEquals(true, state.hideUnsupported)
        assertEquals(false, state.hideUnknown)
        // Mirror needs a companion on the computer, so it starts outside the cycle.
        assertEquals(InputMode.entries.map { it.preferenceKey }.toSet() - InputMode.MIRROR.preferenceKey, state.cycleModes)
    }

    @Test
    fun eachChangeReadsBackFromItsKey() {
        actions.setThemeMode(ThemeMode.DARK)
        actions.setDynamicColor(false)
        actions.setAccentColor(9)
        actions.setPaletteStyle(PaletteStyle.MONOCHROME)
        actions.setBlackBackground(true)
        actions.setHaptics(false)
        val touchpad = TouchpadTuneState(
            speeds = PointerTuneState(pointerSpeed = 2f, scrollSpeed = 0.5f),
            invertScroll = true,
            momentum = false,
            glide = 0.8f,
            modifierPosition = TouchpadModifierPosition.LEFT,
        )
        actions.setTouchpad(touchpad)
        actions.setGamepad(GamepadTuneState(GamepadDpadOutputMode.WEB_BUTTONS))
        actions.setMultimedia(MultimediaTuneState(pointer = touchpad.speeds, hardwareVolumeButtons = true))
        actions.setAutoConnect(false)
        actions.setHideUnknown(true)
        actions.setHideUnsupported(false)
        actions.setShowMac(true)
        actions.setKeepAudio(true)
        assertEquals(
            SettingsState(
                appearance = AppearanceSettings(
                    themeMode = ThemeMode.DARK,
                    dynamicColor = false,
                    accentIndex = 9,
                    paletteStyle = PaletteStyle.MONOCHROME,
                    blackBackground = true,
                ),
                dynamicColorAvailable = true,
                hapticsEnabled = false,
                touchpad = touchpad,
                gamepad = GamepadTuneState(GamepadDpadOutputMode.WEB_BUTTONS),
                multimedia = MultimediaTuneState(pointer = touchpad.speeds, hardwareVolumeButtons = true),
                autoConnect = false,
                hideUnknown = true,
                hideUnsupported = false,
                showMac = true,
                keepAudio = true,
                versionName = "9.9",
            ),
            read(),
        )
    }

    /**
     * The keys and value sets are the contract with the rest of the app (the theme, the surfaces, the
     * Bluetooth manager, the HID rig and every existing install), so they are spelled out here as
     * literals: renaming a constant or a stored value fails this test even when the round trip holds.
     */
    @Test
    fun storedKeysAndValuesNeverChange() {
        actions.setThemeMode(ThemeMode.DARK)
        actions.setDynamicColor(false)
        actions.setAccentColor(9)
        actions.setPaletteStyle(PaletteStyle.MONOCHROME)
        actions.setBlackBackground(true)
        actions.setHaptics(false)
        actions.setTouchpad(
            TouchpadTuneState(
                speeds = PointerTuneState(pointerSpeed = 2f, scrollSpeed = 0.5f),
                invertScroll = true,
                momentum = false,
                glide = 0.8f,
                modifierPosition = TouchpadModifierPosition.LEFT,
            ),
        )
        actions.setGamepad(GamepadTuneState(GamepadDpadOutputMode.WEB_BUTTONS))
        actions.setMultimedia(
            MultimediaTuneState(pointer = PointerTuneState(pointerSpeed = 2f, scrollSpeed = 0.5f), hardwareVolumeButtons = true),
        )
        actions.toggleCycleMode(InputMode.MIRROR)
        actions.setAutoConnect(false)
        actions.setHideUnknown(true)
        actions.setHideUnsupported(false)
        actions.setShowMac(true)
        actions.setKeepAudio(true)
        assertEquals(
            mapOf(
                "theme_mode" to 2,
                "dynamic_color" to false,
                "accent_color_index" to 9,
                "palette_style" to "Monochrome",
                "high_contrast_mode" to true,
                "haptics_enabled" to false,
                "touchpad_sensitivity" to 2f,
                "touchpad_scroll_sensitivity" to 0.5f,
                "touchpad_invert_scroll" to true,
                "touchpad_scroll_momentum" to false,
                "touchpad_scroll_glide" to 0.8f,
                "touchpad_modifier_position" to "left",
                "touchpad_modifier_last_side" to "left",
                "gamepad_dpad_output_mode" to "web_buttons",
                "media_hardware_volume_remote" to true,
                "cycle_connection_modes" to setOf("keyboard", "touchpad", "gamepad", "media_presentation", "mirror"),
                "auto_connect" to false,
                "hide_unknown" to true,
                "hide_unsupported" to false,
                "show_mac" to true,
                "disconnect_audio_profiles" to true,
            ),
            prefs.all,
        )

        actions.setThemeMode(ThemeMode.SYSTEM)
        actions.setPaletteStyle(PaletteStyle.DEFAULT)
        actions.setTouchpad(read().touchpad.copy(modifierPosition = TouchpadModifierPosition.RIGHT))
        actions.setGamepad(GamepadTuneState(GamepadDpadOutputMode.ANDROID))
        assertEquals(0, prefs.getInt("theme_mode", -1))
        assertEquals("Default", prefs.getString("palette_style", null))
        assertEquals("right", prefs.getString("touchpad_modifier_position", null))
        assertEquals("android", prefs.getString("gamepad_dpad_output_mode", null))
        actions.setTouchpad(read().touchpad.copy(modifierPosition = TouchpadModifierPosition.OFF))
        actions.setGamepad(GamepadTuneState(GamepadDpadOutputMode.NATIVE_HAT))
        actions.setThemeMode(ThemeMode.LIGHT)
        assertEquals("off", prefs.getString("touchpad_modifier_position", null))
        assertEquals("native_hat", prefs.getString("gamepad_dpad_output_mode", null))
        assertEquals(1, prefs.getInt("theme_mode", -1))
    }

    @Test
    fun theInputModeCycleKeepsItsLastMode() {
        read().cycleModes.filter { it != InputMode.KEYBOARD.preferenceKey }
            .forEach { key -> actions.toggleCycleMode(InputMode.entries.first { it.preferenceKey == key }) }
        assertEquals(setOf(InputMode.KEYBOARD.preferenceKey), read().cycleModes)
        actions.toggleCycleMode(InputMode.KEYBOARD)
        assertEquals(setOf(InputMode.KEYBOARD.preferenceKey), read().cycleModes)
        actions.toggleCycleMode(InputMode.GAMEPAD)
        assertEquals(setOf(InputMode.KEYBOARD.preferenceKey, InputMode.GAMEPAD.preferenceKey), read().cycleModes)
    }

    @Test
    fun headerSummariesNameTheCurrentValues() {
        val state = SettingsState(appearance = AppearanceSettings(themeMode = ThemeMode.DARK, dynamicColor = true))
        assertEquals("Dark · dynamic colors", state.summary(SettingsSection.APPEARANCE))
        assertEquals("Pointer 1.5× · scroll 1.0× · momentum · medium glide", state.summary(SettingsSection.TOUCHPAD))
        assertEquals("Native profile", state.summary(SettingsSection.CONTROLLER))
        assertEquals("Volume buttons stay on this phone", state.summary(SettingsSection.MULTIMEDIA))
        assertEquals(
            "Toolbar visits Keyboard, Multimedia",
            state.copy(cycleModes = setOf("media_presentation", "keyboard")).summary(SettingsSection.INPUT_MODES),
        )
    }

    @Test
    fun connectionAdvancedSummarySaysWhatTheSwitchesDoNow() {
        assertEquals("Hiding unlikely devices", SettingsState().connectionAdvancedSummary())
        assertEquals(
            "Hiding unnamed and unlikely devices · MAC shown · audio kept on phone",
            SettingsState(hideUnknown = true, showMac = true, keepAudio = true).connectionAdvancedSummary(),
        )
        assertEquals("Listing every nearby device", SettingsState(hideUnsupported = false).connectionAdvancedSummary())
    }

    @Test
    fun connectionHeaderAddsOnlyTheAdvancedValuesThatAreNotDefault() {
        assertEquals("Reconnects on launch", SettingsState().summary(SettingsSection.CONNECTION))
        assertEquals(
            "Doesn't reconnect on launch · Listing every nearby device · MAC shown · audio kept on phone",
            SettingsState(autoConnect = false, hideUnsupported = false, showMac = true, keepAudio = true)
                .summary(SettingsSection.CONNECTION),
        )
    }

    @Test
    fun appearanceSummaryNamesOnlyWhatAppliesNow() {
        val light = SettingsState(
            appearance = AppearanceSettings(
                themeMode = ThemeMode.LIGHT, dynamicColor = false, accentIndex = 0,
                paletteStyle = PaletteStyle.MONOCHROME, blackBackground = true,
            ),
        )
        // Pure black applies only to a dark scheme, so a light theme leaves it out.
        assertEquals("Light · Bluke blue accent · Monochrome palette", light.summary(SettingsSection.APPEARANCE))
        val systemDark = light.copy(appearance = light.appearance.copy(themeMode = ThemeMode.SYSTEM))
        assertEquals(
            "System theme · Bluke blue accent · Monochrome palette · pure black",
            systemDark.summary(SettingsSection.APPEARANCE, systemDark = true),
        )
    }

    @Test
    fun everyStepOfTheKeyTouchAreaSliderHasItsOwnLabel() {
        val touchArea = (0..10).map { keyTouchAreaLabel(it.toFloat()) }
        assertEquals(11, touchArea.toSet().size)
        assertEquals("Balanced\u00A0(6)", keyTouchAreaLabel(6f))
    }

    @Test
    fun aSectionScrollsToItsGroupLabelWhenItOpensTheGroup() {
        val sections = SettingsListItems.filterIsInstance<SettingsListItem.Section>().map { it.section }
        assertEquals(SettingsSection.entries, sections)
        assertEquals(0, settingsListIndex(SettingsSection.APPEARANCE))
        assertEquals(SettingsListItem.Section(SettingsSection.FEEDBACK), SettingsListItems[settingsListIndex(SettingsSection.FEEDBACK)])
        assertEquals(SettingsListItem.Label("Inputs"), SettingsListItems[settingsListIndex(SettingsSection.KEYBOARD)])
        assertEquals(
            SettingsListItem.Section(SettingsSection.HELP_AND_ABOUT),
            SettingsListItems[settingsListIndex(SettingsSection.HELP_AND_ABOUT)],
        )
    }
}
