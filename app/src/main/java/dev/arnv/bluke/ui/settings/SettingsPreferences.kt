package dev.arnv.bluke.ui.settings

import android.content.SharedPreferences
import androidx.core.content.edit
import dev.arnv.bluke.ui.GamepadTuneState
import dev.arnv.bluke.ui.InputMode
import dev.arnv.bluke.ui.defaultInputModeKeys
import dev.arnv.bluke.ui.MultimediaTuneState
import dev.arnv.bluke.ui.TouchpadTuneState
import dev.arnv.bluke.ui.normalizeInputModeKeys
import dev.arnv.bluke.ui.theme.ACCENT_COLOR_PREFERENCE
import dev.arnv.bluke.ui.theme.AppearanceSettings
import dev.arnv.bluke.ui.theme.BLACK_BACKGROUND_PREFERENCE
import dev.arnv.bluke.ui.theme.DYNAMIC_COLOR_PREFERENCE
import dev.arnv.bluke.ui.theme.PALETTE_STYLE_PREFERENCE
import dev.arnv.bluke.ui.theme.PaletteStyle
import dev.arnv.bluke.ui.theme.THEME_MODE_PREFERENCE
import dev.arnv.bluke.ui.theme.ThemeMode
import dev.arnv.bluke.ui.toggledCycleSelection

/*
 * The preference keys and value sets below are the contract with the rest of the app (the theme, the
 * surfaces, the Bluetooth manager and the HID rig read them); never rename one. The theme's keys live
 * in `ui/theme/Appearance.kt` and each surface's in its `<Surface>TuneState`.
 */
internal const val HAPTICS_PREFERENCE = "haptics_enabled"
internal const val INPUT_MODE_CYCLE_PREFERENCE = "cycle_connection_modes"
internal const val AUTO_CONNECT_PREFERENCE = "auto_connect"
internal const val HIDE_UNKNOWN_PREFERENCE = "hide_unknown"
internal const val HIDE_UNSUPPORTED_PREFERENCE = "hide_unsupported"
internal const val SHOW_MAC_PREFERENCE = "show_mac"
internal const val KEEP_AUDIO_PREFERENCE = "disconnect_audio_profiles"
internal const val DEVELOPER_MODE_PREFERENCE = "is_developer_mode"

/**
 * Everything the Settings screen shows, besides the Keyboard section's own state. The touchpad,
 * gamepad and multimedia parts are the surfaces' own Tune states, so Settings and the Tune sheets
 * read and write the same keys the same way.
 */
internal data class SettingsState(
    val appearance: AppearanceSettings = AppearanceSettings(dynamicColor = true),
    val dynamicColorAvailable: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val touchpad: TouchpadTuneState = TouchpadTuneState(),
    val gamepad: GamepadTuneState = GamepadTuneState(),
    val multimedia: MultimediaTuneState = MultimediaTuneState(),
    val cycleModes: Set<String> = defaultInputModeKeys,
    val autoConnect: Boolean = true,
    val hideUnknown: Boolean = false,
    val hideUnsupported: Boolean = true,
    val showMac: Boolean = false,
    val keepAudio: Boolean = false,
    val developerMode: Boolean = false,
    val versionName: String = "",
)

/** Reads [SettingsState] from `app_prefs`, with the same defaults the rest of the app reads. */
internal fun readSettingsState(
    prefs: SharedPreferences,
    dynamicColorAvailable: Boolean,
    versionName: String,
): SettingsState = SettingsState(
    appearance = AppearanceSettings.read(prefs),
    dynamicColorAvailable = dynamicColorAvailable,
    hapticsEnabled = prefs.getBoolean(HAPTICS_PREFERENCE, true),
    touchpad = TouchpadTuneState.read(prefs),
    gamepad = GamepadTuneState.read(prefs),
    multimedia = MultimediaTuneState.read(prefs),
    // The surfaces read the cycle through the same normalisation, so this shows what the toolbar visits.
    cycleModes = normalizeInputModeKeys(prefs.getStringSet(INPUT_MODE_CYCLE_PREFERENCE, null)?.toSet()),
    autoConnect = prefs.getBoolean(AUTO_CONNECT_PREFERENCE, true),
    hideUnknown = prefs.getBoolean(HIDE_UNKNOWN_PREFERENCE, false),
    hideUnsupported = prefs.getBoolean(HIDE_UNSUPPORTED_PREFERENCE, true),
    showMac = prefs.getBoolean(SHOW_MAC_PREFERENCE, false),
    keepAudio = prefs.getBoolean(KEEP_AUDIO_PREFERENCE, false),
    developerMode = prefs.getBoolean(DEVELOPER_MODE_PREFERENCE, false),
    versionName = versionName,
)

/** What the Settings screen's controls do (all but the Keyboard section's). */
internal interface SettingsActions {
    fun setThemeMode(mode: ThemeMode)
    fun setDynamicColor(on: Boolean)
    fun setAccentColor(index: Int)
    fun setPaletteStyle(style: PaletteStyle)
    fun setBlackBackground(on: Boolean)
    fun setHaptics(on: Boolean)
    fun setTouchpad(next: TouchpadTuneState)
    fun setGamepad(next: GamepadTuneState)
    fun setMultimedia(next: MultimediaTuneState)
    fun toggleCycleMode(mode: InputMode)
    fun setAutoConnect(on: Boolean)
    fun setHideUnknown(on: Boolean)
    fun setHideUnsupported(on: Boolean)
    fun setShowMac(on: Boolean)
    fun setKeepAudio(on: Boolean)
    fun openHelp()
    fun openAbout()
    fun openDeveloperOptions()

    /** Does nothing; for previews and screenshots. */
    object None : SettingsActions {
        override fun setThemeMode(mode: ThemeMode) = Unit
        override fun setDynamicColor(on: Boolean) = Unit
        override fun setAccentColor(index: Int) = Unit
        override fun setPaletteStyle(style: PaletteStyle) = Unit
        override fun setBlackBackground(on: Boolean) = Unit
        override fun setHaptics(on: Boolean) = Unit
        override fun setTouchpad(next: TouchpadTuneState) = Unit
        override fun setGamepad(next: GamepadTuneState) = Unit
        override fun setMultimedia(next: MultimediaTuneState) = Unit
        override fun toggleCycleMode(mode: InputMode) = Unit
        override fun setAutoConnect(on: Boolean) = Unit
        override fun setHideUnknown(on: Boolean) = Unit
        override fun setHideUnsupported(on: Boolean) = Unit
        override fun setShowMac(on: Boolean) = Unit
        override fun setKeepAudio(on: Boolean) = Unit
        override fun openHelp() = Unit
        override fun openAbout() = Unit
        override fun openDeveloperOptions() = Unit
    }
}

/**
 * Writes each change to `app_prefs`. It never updates the screen itself: the screen re-reads the
 * preferences on every change, so what it shows is what was stored.
 */
internal class PreferenceSettingsActions(
    private val prefs: SharedPreferences,
    private val onOpenHelp: () -> Unit,
    private val onOpenAbout: () -> Unit,
    private val onOpenDeveloperOptions: () -> Unit,
) : SettingsActions {
    override fun setThemeMode(mode: ThemeMode) = prefs.edit { putInt(THEME_MODE_PREFERENCE, mode.preferenceValue) }
    override fun setDynamicColor(on: Boolean) = prefs.edit { putBoolean(DYNAMIC_COLOR_PREFERENCE, on) }
    override fun setAccentColor(index: Int) = prefs.edit { putInt(ACCENT_COLOR_PREFERENCE, index) }
    override fun setPaletteStyle(style: PaletteStyle) = prefs.edit { putString(PALETTE_STYLE_PREFERENCE, style.preferenceValue) }
    override fun setBlackBackground(on: Boolean) = prefs.edit { putBoolean(BLACK_BACKGROUND_PREFERENCE, on) }
    override fun setHaptics(on: Boolean) = prefs.edit { putBoolean(HAPTICS_PREFERENCE, on) }
    override fun setTouchpad(next: TouchpadTuneState) = next.write(prefs)
    // The Bluetooth manager listens for the profile key and sends the host a neutral report in the new profile.
    override fun setGamepad(next: GamepadTuneState) = next.write(prefs)
    override fun setMultimedia(next: MultimediaTuneState) = next.write(prefs)
    override fun toggleCycleMode(mode: InputMode) {
        val current = normalizeInputModeKeys(prefs.getStringSet(INPUT_MODE_CYCLE_PREFERENCE, null)?.toSet())
        prefs.edit { putStringSet(INPUT_MODE_CYCLE_PREFERENCE, toggledCycleSelection(current, mode.preferenceKey)) }
    }
    override fun setAutoConnect(on: Boolean) = prefs.edit { putBoolean(AUTO_CONNECT_PREFERENCE, on) }
    override fun setHideUnknown(on: Boolean) = prefs.edit { putBoolean(HIDE_UNKNOWN_PREFERENCE, on) }
    override fun setHideUnsupported(on: Boolean) = prefs.edit { putBoolean(HIDE_UNSUPPORTED_PREFERENCE, on) }
    override fun setShowMac(on: Boolean) = prefs.edit { putBoolean(SHOW_MAC_PREFERENCE, on) }
    override fun setKeepAudio(on: Boolean) = prefs.edit { putBoolean(KEEP_AUDIO_PREFERENCE, on) }
    override fun openHelp() = onOpenHelp()
    override fun openAbout() = onOpenAbout()
    override fun openDeveloperOptions() = onOpenDeveloperOptions()
}
