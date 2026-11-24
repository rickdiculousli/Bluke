package dev.arnv.bluke.ui

import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import dev.arnv.bluke.bluetooth.BluetoothKeyboardManager
import dev.arnv.bluke.sound.KeyboardSoundSynthesizer
import dev.arnv.bluke.ui.theme.MyApplicationTheme

/**
 * The full-screen input surface for [launchMode]: keyboard, touchpad, gamepad, multimedia remote or Mirror.
 * Every surface draws in the theme's dark scheme, in the user's accent: it is hardware you hold.
 */
@Composable
internal fun InputSurface(
    keyboardState: KeyboardSurfaceState,
    btManager: BluetoothKeyboardManager,
    soundSynth: KeyboardSoundSynthesizer,
    sharedPrefs: SharedPreferences,
    launchMode: Int,
    isConnected: Boolean,
    host: SurfaceHost,
    onClose: () -> Unit,
    onModeChange: (Int) -> Unit,
) {
    val mode = InputMode.fromId(launchMode)
    val currentOnClose by rememberUpdatedState(onClose)
    val currentOnModeChange by rememberUpdatedState(onModeChange)
    // The host is rebuilt on every home-state emission; keep it (and the chrome) the same object while
    // nothing it shows changed, so a lock-light or scan update doesn't recompose the whole surface.
    val stableHost = remember(host.status, host.name, host.reason, host.onReconnect != null) { host }
    val chrome = remember(mode, stableHost) {
        SurfaceChrome(
            mode = mode,
            host = stableHost,
            onClose = { currentOnClose() },
            onNextMode = { currentOnModeChange(nextInputMode(sharedPrefs.enabledInputModes(), mode).id) },
        )
    }
    val caseBrush = caseBrush(keyboardState.selectedKeyboardTheme)

    SurfaceTheme {
        when (mode) {
            InputMode.TOUCHPAD -> TouchpadView(
                btManager = btManager,
                chrome = chrome,
                sharedPrefs = sharedPrefs,
                caseBrush = caseBrush,
                modifierStyle = keyboardState.modifierStyle,
            )
            InputMode.GAMEPAD -> GamepadView(
                btManager = btManager,
                chrome = chrome,
                sharedPrefs = sharedPrefs,
                caseBrush = caseBrush,
            )
            InputMode.MEDIA_PRESENTATION -> MediaPresentationView(
                btManager = btManager,
                chrome = chrome,
                sharedPrefs = sharedPrefs,
            )
            // Mirror keeps the rail spec 002 built for it rather than the shared toolbar.
            InputMode.MIRROR -> MirrorView(
                btManager = btManager,
                onClose = onClose,
                launchMode = launchMode,
                onModeChange = onModeChange,
                sharedPrefs = sharedPrefs,
            )
            else -> KeyboardSurface(
                state = keyboardState,
                btManager = btManager,
                soundSynth = soundSynth,
                sharedPrefs = sharedPrefs,
                caseBrush = caseBrush,
                chrome = chrome,
                isConnected = isConnected,
            )
        }
    }
}

/** The theme every input surface draws in: the app's dark scheme in the user's accent, whatever the theme mode. */
@Composable
fun SurfaceTheme(content: @Composable () -> Unit) {
    MyApplicationTheme(forceDark = true, content = content)
}

/** The mode after [current] in the user's input-mode cycle; [current] itself when it is the only one. */
internal fun nextInputMode(enabled: List<InputMode>, current: InputMode): InputMode {
    if (enabled.isEmpty()) return current
    val index = enabled.indexOf(current)
    return enabled[(index + 1).mod(enabled.size)]
}

/** The keyboard theme's case, which the touchpad and gamepad stand on too. */
internal fun caseBrush(theme: KeyboardThemeDefinition): Brush {
    val case = Color(theme.caseArgb)
    if (!theme.caseMetallic) return SolidColor(case)
    return Brush.linearGradient(
        colors = listOf(case, case.copy(alpha = 0.85f), case.copy(alpha = 0.7f), case, case.copy(alpha = 0.9f)),
        start = Offset(0f, 0f),
        end = Offset(500f, 500f),
    )
}
