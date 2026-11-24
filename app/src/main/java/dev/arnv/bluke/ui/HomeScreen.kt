package dev.arnv.bluke.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.os.Build
import android.util.Log
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.arnv.bluke.BuildConfig
import dev.arnv.bluke.HelpActivity
import dev.arnv.bluke.QuickCycleActivity
import dev.arnv.bluke.SettingsActivity
import dev.arnv.bluke.bluetooth.BluetoothKeyboardManager
import dev.arnv.bluke.bluetooth.CURRENT_HID_DESCRIPTOR_REVISION
import dev.arnv.bluke.bluetooth.HID_DESCRIPTOR_REVISION_PREFERENCE
import dev.arnv.bluke.sound.KeyboardSoundSynthesizer

/**
 * Home and the input surface it opens. Home itself is [HomeContent]; this wires it to the manager,
 * the activities it opens, and the surface, which goes back home whenever Bluetooth is blocked.
 */
@SuppressLint("MissingPermission")
@Composable
fun HomeScreen(
    btManager: BluetoothKeyboardManager,
    soundSynth: KeyboardSoundSynthesizer,
    onGrantPermission: () -> Unit = {},
) {
    val context = LocalContext.current
    val sharedPrefs = remember(context) { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
    val btLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    val homeViewModel: HomeViewModel = viewModel(
        factory = HomeViewModel.factory(btManager, sharedPrefs.hostListFilters()),
    )
    val homeUiState by homeViewModel.uiState.collectAsStateWithLifecycle()
    val connection = homeUiState.connection

    var openModeId by rememberSaveable { mutableStateOf<Int?>(null) }
    val openMode = openModeId?.let(InputMode::fromId)
    BackHandler(enabled = openMode != null) { openModeId = null }

    // C1: a surface never outlives Bluetooth. Checking and Starting leave it open.
    LaunchedEffect(connection.closesSurface, openModeId) {
        if (connection.closesSurface && openModeId != null) openModeId = null
    }

    var showMacAddress by remember { mutableStateOf(sharedPrefs.getBoolean("show_mac", false)) }
    var isHapticsEnabled by remember { mutableStateOf(sharedPrefs.getBoolean("haptics_enabled", true)) }
    DisposableEffect(sharedPrefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            when (key) {
                "show_mac" -> showMacAddress = prefs.getBoolean("show_mac", false)
                "haptics_enabled" -> isHapticsEnabled = prefs.getBoolean("haptics_enabled", true)
            }
        }
        sharedPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { sharedPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    var pendingGamepadGuide by rememberSaveable { mutableStateOf(false) }
    fun open(mode: InputMode) {
        if (shouldShowGamepadGuide(mode, sharedPrefs.getBoolean("has_seen_gamepad_guide", false))) {
            pendingGamepadGuide = true
            return
        }
        sharedPrefs.edit { putInt("launch_mode", mode.id) }
        openModeId = mode.id
    }

    DescriptorRefreshNotice(sharedPrefs)

    if (pendingGamepadGuide) {
        GamepadGuideDialog(
            onOpen = {
                sharedPrefs.edit { putBoolean("has_seen_gamepad_guide", true) }
                pendingGamepadGuide = false
                open(InputMode.GAMEPAD)
            },
            onDismiss = { pendingGamepadGuide = false },
        )
    }

    SurfaceOrientation(surfaceOpen = openMode != null)

    val surfaceHost = connection.surfaceHost(
        onReconnect = { host -> homeViewModel.device(host.address)?.let(btManager::connectDevice) },
    )
    val isConnected = surfaceHost.status == SurfaceHostStatus.Connected
    val keyboardState = rememberKeyboardSurfaceState(btManager, soundSynth, sharedPrefs, homeUiState, isConnected)

    AnimatedContent(
        targetState = openMode,
        transitionSpec = { fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) togetherWith fadeOut(spring(stiffness = Spring.StiffnessMedium)) },
        label = "home-surface",
    ) { mode ->
        if (mode != null) {
            InputSurface(
                keyboardState = keyboardState,
                btManager = btManager,
                soundSynth = soundSynth,
                sharedPrefs = sharedPrefs,
                launchMode = mode.id,
                isConnected = isConnected,
                host = surfaceHost,
                onClose = { openModeId = null },
                onModeChange = { open(InputMode.fromId(it)) },
            )
        } else {
            HomeContent(
                state = homeUiState,
                showMacAddress = showMacAddress,
                hapticsEnabled = isHapticsEnabled,
                callbacks = HomeCallbacks(
                    onAction = { action ->
                        when (action) {
                            ConnectionAction.GrantPermission -> onGrantPermission()
                            ConnectionAction.TurnOnBluetooth ->
                                btLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                            ConnectionAction.RetryCheck -> btManager.retryBluetoothCapabilities()
                            is ConnectionAction.Reconnect -> homeViewModel.device(action.host.address)?.let(btManager::connectDevice)
                            is ConnectionAction.Retry -> homeViewModel.device(action.host.address)?.let(btManager::connectDevice)
                            ConnectionAction.Scan -> btManager.startScanning()
                            ConnectionAction.StopScan -> btManager.stopScanning()
                            ConnectionAction.Cancel, ConnectionAction.Disconnect -> btManager.disconnectDevice()
                            ConnectionAction.RestartHid -> btManager.restartHidService()
                            ConnectionAction.Help -> context.startActivity(Intent(context, HelpActivity::class.java))
                        }
                    },
                    onConnectHost = { host -> homeViewModel.device(host.address)?.let(btManager::connectDevice) },
                    onToggleScan = { if (homeUiState.isScanning) btManager.stopScanning() else btManager.startScanning() },
                    onLaunch = ::open,
                    onModeSettings = { context.startActivity(Intent(context, QuickCycleActivity::class.java)) },
                    onOpenSettings = { context.startActivity(Intent(context, SettingsActivity::class.java)) },
                    onOpenHelp = { context.startActivity(Intent(context, HelpActivity::class.java)) },
                ),
            )
        }
    }
}

/** Tells someone upgrading across a descriptor change to re-pair; new installs never see it. */
@Composable
private fun DescriptorRefreshNotice(sharedPrefs: SharedPreferences) {
    var show by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(sharedPrefs) {
        when (
            launchNotice(
                savedVersionCode = sharedPrefs.getInt("last_run_version_code", 0),
                hasSeenOnboarding = sharedPrefs.getBoolean("has_seen_onboarding", false),
                savedDescriptorRevision = sharedPrefs.getInt(HID_DESCRIPTOR_REVISION_PREFERENCE, 1),
            )
        ) {
            LaunchNotice.DESCRIPTOR_REFRESH -> show = true
            LaunchNotice.NONE -> sharedPrefs.edit { putInt(HID_DESCRIPTOR_REVISION_PREFERENCE, CURRENT_HID_DESCRIPTOR_REVISION) }
        }
        sharedPrefs.edit { putInt("last_run_version_code", BuildConfig.VERSION_CODE) }
    }
    if (!show) return
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Pair your hosts again") },
        text = {
            Text(
                "Bluke now describes all its controls to hosts in one shared layout, and hosts remember the old one. " +
                    "On each host, remove Bluke from its Bluetooth devices; on this phone, forget the host. Then pair again once.",
            )
        },
        confirmButton = {
            Button(onClick = {
                show = false
                sharedPrefs.edit { putInt(HID_DESCRIPTOR_REVISION_PREFERENCE, CURRENT_HID_DESCRIPTOR_REVISION) }
            }) { Text("Got it") }
        },
    )
}

@Composable
private fun GamepadGuideDialog(onOpen: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.SportsEsports, contentDescription = null) },
        title = { Text("Before using the gamepad") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Choose Native for Windows and Linux games, Android for games on Android, or Web when a browser game ignores directions. Change it any time from the profile label between the shoulder buttons, or in Tune.")
                Text("On Windows, some newer games accept only Xbox XInput controllers. Bluke is a standard Bluetooth HID gamepad, so Steam Input or another compatibility layer may be needed.")
            }
        },
        confirmButton = { Button(onClick = onOpen) { Text("Open gamepad") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}

/** Surfaces run landscape and immersive; home runs portrait with system bars. */
@Composable
private fun SurfaceOrientation(surfaceOpen: Boolean) {
    val context = LocalContext.current
    LaunchedEffect(surfaceOpen) {
        val activity = context as? Activity ?: return@LaunchedEffect
        val window = activity.window ?: return@LaunchedEffect
        try {
            @Suppress("SourceLockedOrientationActivity")
            activity.requestedOrientation =
                if (surfaceOpen) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } catch (e: Exception) {
            Log.e("HomeScreen", "Error setting orientation: ${e.message}")
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars()
                if (surfaceOpen) {
                    window.insetsController?.hide(bars)
                    window.insetsController?.systemBarsBehavior =
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    window.insetsController?.show(bars)
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = if (surfaceOpen) {
                    View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                } else {
                    View.SYSTEM_UI_FLAG_VISIBLE
                }
            }
        } catch (e: Exception) {
            Log.e("HomeScreen", "Error changing system bars: ${e.message}")
        }
    }
}
