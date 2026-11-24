package dev.arnv.bluke.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import dev.arnv.bluke.bluetooth.BluetoothKeyboardManager
import dev.arnv.bluke.bluetooth.BluetoothState
import dev.arnv.bluke.bluetooth.HidLifecycleState
import dev.arnv.bluke.sound.KeyboardSoundSynthesizer
import dev.arnv.bluke.ui.theme.MyApplicationTheme

/**
 * The full-screen input surface for [launchMode]: keyboard, touchpad, gamepad or multimedia remote.
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
    val stableHost = remember(host.status, host.name, host.reason, host.onReconnect != null, host.onFix != null) { host }
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

/**
 * How the host chip reads the connection. Checking and starting read as Connecting (the surface stays
 * usable and says so); only a state no tap here can fix reads as Blocked.
 */
internal fun surfaceHostStatus(
    state: BluetoothState,
    lifecycle: HidLifecycleState,
    hasTarget: Boolean = false,
): SurfaceHostStatus = when {
    state is BluetoothState.Connected -> SurfaceHostStatus.Connected
    blockedReason(state) != null -> SurfaceHostStatus.Blocked
    state is BluetoothState.InitializingCapabilities || state is BluetoothState.CheckingCapabilities ||
        lifecycle is HidLifecycleState.Connecting -> SurfaceHostStatus.Connecting
    // Re-registering the HID app with no host in view isn't connecting to anything.
    (lifecycle is HidLifecycleState.Registering || lifecycle is HidLifecycleState.BindingProxy) && hasTarget ->
        SurfaceHostStatus.Connecting
    else -> SurfaceHostStatus.Offline
}

/** The cause, in a few words, when no tap on a surface can reach a host; null otherwise. */
internal fun blockedReason(state: BluetoothState): String? = when (state) {
    is BluetoothState.BluetoothOff -> "Bluetooth off"
    is BluetoothState.PermissionRequired -> "Nearby devices permission needed"
    is BluetoothState.Unsupported -> "No Bluetooth on this phone"
    is BluetoothState.ProfileNotSupported -> "This phone can't be a Bluetooth input"
    else -> null
}

/** A tap on a Blocked chip that clears its cause: the chip's action word and what TalkBack says it does. */
internal enum class BlockedFix(val label: String, val clickLabel: String) {
    TurnOnBluetooth("Turn on", "Turn on Bluetooth"),
    // The home screen owns the runtime request; from a surface, Bluke's settings page is where it's granted.
    OpenAppSettings("Allow", "Allow nearby devices"),
}

/** The fix for [state]'s block, when a tap can clear it; no tap gives a phone Bluetooth or an HID role. */
internal fun blockedFix(state: BluetoothState): BlockedFix? = when (state) {
    is BluetoothState.BluetoothOff -> BlockedFix.TurnOnBluetooth
    is BluetoothState.PermissionRequired -> BlockedFix.OpenAppSettings
    else -> null
}

private fun BlockedFix.intent(context: Context): Intent = when (this) {
    BlockedFix.TurnOnBluetooth -> Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
    BlockedFix.OpenAppSettings -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
}

private fun BlockedFix.run(context: Context) {
    val intent = intent(context)
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: SecurityException) {
    } catch (_: ActivityNotFoundException) {
    }
}

/**
 * The [SurfaceHost] for the surface call site, from the home screen's state plus the reconnect target.
 * Offline with a bonded reconnect target, the chip reconnects to it; Blocked by something a tap can
 * clear, the chip opens that fix from [context].
 */
@SuppressLint("MissingPermission")
internal fun surfaceHost(btManager: BluetoothKeyboardManager, state: HomeUiState, context: Context): SurfaceHost {
    fun BluetoothDevice.label(): String? = try { name ?: address } catch (_: SecurityException) { null }
    val status = surfaceHostStatus(state.bluetoothState, state.hidLifecycleState, hasTarget = state.connectionTargetAddress != null)
    val target = btManager.getReconnectTarget()?.takeIf { it in state.bondedDevices }
    return when (status) {
        SurfaceHostStatus.Connected -> SurfaceHost(
            status,
            state.connectedDevice?.label() ?: (state.bluetoothState as? BluetoothState.Connected)?.deviceName,
        )
        SurfaceHostStatus.Connecting -> {
            val address = (state.hidLifecycleState as? HidLifecycleState.Connecting)?.deviceAddress
                ?: state.connectionTargetAddress
            val device = state.bondedDevices.firstOrNull { it.address == address }
            SurfaceHost(status, device?.label())
        }
        SurfaceHostStatus.Offline -> SurfaceHost(
            status,
            target?.label(),
            onReconnect = target?.let { device -> { btManager.connectDevice(device) } },
        )
        SurfaceHostStatus.Blocked -> {
            val fix = blockedFix(state.bluetoothState)
            SurfaceHost(
                status,
                null,
                reason = blockedReason(state.bluetoothState),
                onFix = fix?.let { { it.run(context) } },
                fixLabel = fix?.label,
                fixClickLabel = fix?.clickLabel,
                fixIcon = when (fix) {
                    BlockedFix.TurnOnBluetooth -> Icons.Default.Bluetooth
                    BlockedFix.OpenAppSettings -> Icons.Default.Settings
                    null -> null
                },
            )
        }
    }
}
