package dev.arnv.bluke.ui

import android.bluetooth.BluetoothDevice
import android.content.SharedPreferences
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import dev.arnv.bluke.bluetooth.BluetoothKeyboardManager
import dev.arnv.bluke.sound.KeyboardSoundSynthesizer

/** The full-screen input surface for [launchMode]: keyboard, touchpad, gamepad or multimedia remote. */
@Composable
internal fun InputSurface(
    keyboardState: KeyboardSurfaceState,
    btManager: BluetoothKeyboardManager,
    soundSynth: KeyboardSoundSynthesizer,
    sharedPrefs: SharedPreferences,
    launchMode: Int,
    isConnected: Boolean,
    isHapticsEnabled: Boolean,
    connectedDevice: BluetoothDevice?,
    onClose: () -> Unit,
    onModeChange: (Int) -> Unit,
) {
    val caseColorVal = Color(keyboardState.selectedKeyboardTheme.caseArgb)
    val caseMetallic = keyboardState.selectedKeyboardTheme.caseMetallic
    val caseBrush = if (caseMetallic) {
        Brush.linearGradient(
            colors = listOf(
                caseColorVal,
                caseColorVal.copy(alpha = 0.85f),
                caseColorVal.copy(alpha = 0.7f),
                caseColorVal,
                caseColorVal.copy(alpha = 0.9f)
            ),
            start = Offset(0f, 0f),
            end = Offset(500f, 500f)
        )
    } else {
        SolidColor(caseColorVal)
    }

    when (launchMode) {
        1, 2, 4, 5 -> {
            val darkScheme = darkColorScheme(
                primary = MaterialTheme.colorScheme.primary,
                background = Color(0xFF141218),
                surface = Color(0xFF141218),
                surfaceVariant = Color(0xFF2B2930),
                onBackground = Color(0xFFE6E0E9),
                onSurface = Color(0xFFE6E0E9),
                onSurfaceVariant = Color(0xFFCBC4D0)
            )
            MaterialTheme(colorScheme = darkScheme) {
                when (launchMode) {
                    1 -> {
                        TouchpadView(
                            btManager = btManager,
                            onClose = onClose,
                            launchMode = launchMode,
                            onModeChange = onModeChange,
                            sharedPrefs = sharedPrefs,
                            caseBrush = caseBrush,
                            modifierStyle = keyboardState.modifierStyle,
                        )
                    }
                    2 -> {
                        GamepadView(
                            btManager = btManager,
                            onClose = onClose,
                            launchMode = launchMode,
                            onModeChange = onModeChange,
                            sharedPrefs = sharedPrefs,
                            caseBrush = caseBrush
                        )
                    }
                    4 -> {
                        MediaPresentationView(
                            btManager = btManager,
                            onClose = onClose,
                            launchMode = launchMode,
                            onModeChange = onModeChange,
                            sharedPrefs = sharedPrefs,
                            isConnected = isConnected,
                        )
                    }
                    5 -> {
                        MirrorView(
                            btManager = btManager,
                            onClose = onClose,
                            launchMode = launchMode,
                            onModeChange = onModeChange,
                            sharedPrefs = sharedPrefs,
                        )
                    }
                }
            }
        }
        else -> KeyboardSurface(
            state = keyboardState,
            btManager = btManager,
            soundSynth = soundSynth,
            sharedPrefs = sharedPrefs,
            caseBrush = caseBrush,
            launchMode = launchMode,
            isConnected = isConnected,
            isHapticsEnabled = isHapticsEnabled,
            connectedDevice = connectedDevice,
            onClose = onClose,
            onModeChange = onModeChange,
        )
    }
}
