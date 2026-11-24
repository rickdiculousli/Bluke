package dev.arnv.bluke.ui

import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import dev.arnv.bluke.ui.theme.migratePaletteStyle

// Surface state: how the user left a surface, restored when it reopens. These are not settings, and
// Settings doesn't show them; only the surface and its Tune sheet write them.
const val GAMEPAD_CONSOLE_PREFERENCE = "gamepad_console"
const val TOUCHPAD_BUTTON_MODE_PREFERENCE = "touchpad_button_mode"
const val TOUCHPAD_NUMPAD_PREFERENCE = "touchpad_numpad"

// Settings the touchpad and multimedia pointer share. Their keys are the contract with Settings.
const val TOUCHPAD_POINTER_SPEED_PREFERENCE = "touchpad_sensitivity"
const val DEFAULT_TOUCHPAD_POINTER_SPEED = 1.5f

/** Runs every surface-preference migration. Safe to run twice; the app runs it on each start. */
fun migrateSurfacePreferences(prefs: SharedPreferences) {
    migrateHapticsPreference(prefs)
    migratePaletteStyle(prefs)
}

/**
 * A value read from [prefs] by [read], re-read whenever any of [keys] changes, from anywhere in the
 * process (a Tune sheet, a settings screen). A surface reads its settings through this, so a change
 * applies at once rather than when the surface reopens.
 */
@Composable
fun <T> rememberPreference(
    prefs: SharedPreferences,
    vararg keys: String,
    read: (SharedPreferences) -> T,
): State<T> {
    val keySet = remember(*keys) { keys.toSet() }
    val currentRead by rememberUpdatedState(read)
    val state = remember(prefs, keySet) { mutableStateOf(read(prefs)) }
    DisposableEffect(prefs, keySet) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
            // A null key means the file was cleared.
            if (key == null || key in keySet) state.value = currentRead(changed)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        // Catch a write made between the first read and registering.
        state.value = currentRead(prefs)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return state
}
