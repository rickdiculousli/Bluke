package dev.arnv.bluke.ui
import android.annotation.SuppressLint
import android.app.Activity
import android.util.Log
import android.content.Intent
import android.bluetooth.BluetoothAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.SystemClock
import android.view.View
import androidx.compose.animation.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import android.content.Context
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import dev.arnv.bluke.R
import dev.arnv.bluke.HelpActivity
import androidx.core.content.edit
import androidx.core.net.toUri
import dev.arnv.bluke.bluetooth.BluetoothKeyboardManager
import dev.arnv.bluke.bluetooth.BluetoothState
import dev.arnv.bluke.bluetooth.CURRENT_HID_DESCRIPTOR_REVISION
import dev.arnv.bluke.bluetooth.HID_DESCRIPTOR_REVISION_PREFERENCE
import dev.arnv.bluke.bluetooth.requiresHidDescriptorRefresh
import dev.arnv.bluke.sound.KeyboardSoundSynthesizer
import kotlinx.coroutines.delay

@SuppressLint("MissingPermission")
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    btManager: BluetoothKeyboardManager,
    soundSynth: KeyboardSoundSynthesizer
) {
    val context = LocalContext.current
    val btLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    val homeViewModel: HomeViewModel = viewModel(factory = HomeViewModel.factory(btManager))
    val homeUiState by homeViewModel.uiState.collectAsStateWithLifecycle()
    
    // UI state
    val sharedPrefs = remember(context) {
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    }
    var isKeyboardActive by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = isKeyboardActive) {
        isKeyboardActive = false
    }
    var hideUnknownDevices by remember { mutableStateOf(sharedPrefs.getBoolean("hide_unknown", false)) }
    var hideUnsupportedDevices by remember { mutableStateOf(sharedPrefs.getBoolean("hide_unsupported", true)) }
    var showMacAddress by remember { mutableStateOf(sharedPrefs.getBoolean("show_mac", false)) }
    var easterEggClicks by remember { mutableIntStateOf(0) }
    
    val view = LocalView.current
    var isHapticsEnabled by remember { mutableStateOf(sharedPrefs.getBoolean("haptics_enabled", true)) }
    var launchMode by rememberSaveable {
        val enabledModes = sharedPrefs.enabledInputModes().map(InputMode::id)
        val savedMode = sharedPrefs.getInt("launch_mode", InputMode.KEYBOARD.id)
        mutableIntStateOf(savedMode.takeIf(enabledModes::contains) ?: enabledModes.first())
    }

    var devModeRefreshTrigger by remember { mutableIntStateOf(0) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, sharedPrefs, soundSynth) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hideUnknownDevices = sharedPrefs.getBoolean("hide_unknown", false)
                hideUnsupportedDevices = sharedPrefs.getBoolean("hide_unsupported", true)
                showMacAddress = sharedPrefs.getBoolean("show_mac", false)
                isHapticsEnabled = sharedPrefs.getBoolean("haptics_enabled", true)
                val enabledModes = sharedPrefs.enabledInputModes().map(InputMode::id)
                val savedLaunchMode = sharedPrefs.getInt("launch_mode", 0)
                launchMode = if (enabledModes.contains(savedLaunchMode)) savedLaunchMode else enabledModes.first()
                devModeRefreshTrigger++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    
    // Bluetooth status flows
    val realBtState = homeUiState.bluetoothState
    
    val btState = remember(realBtState, devModeRefreshTrigger) {
        if (sharedPrefs.getBoolean("is_developer_mode", false)) {
            if (sharedPrefs.getBoolean("mock_bt_disabled", false)) return@remember dev.arnv.bluke.bluetooth.BluetoothState.BluetoothOff
            if (sharedPrefs.getBoolean("mock_device_unsupported", false)) return@remember dev.arnv.bluke.bluetooth.BluetoothState.Unsupported
            if (sharedPrefs.getBoolean("mock_hid_unsupported", false)) return@remember dev.arnv.bluke.bluetooth.BluetoothState.ProfileNotSupported
        }
        realBtState
    }
    val btMessage = homeUiState.statusMessage
    

    
    val bondedDevices = homeUiState.bondedDevices
    val scannedDevices = homeUiState.scannedDevices
    val isScanning = homeUiState.isScanning
    
    var showUpdateDialog by rememberSaveable { mutableStateOf(false) }
    var descriptorRefreshRequired by rememberSaveable { mutableStateOf(false) }
    var showGamepadGuide by rememberSaveable { mutableStateOf(false) }
    var pendingGamepadMode by rememberSaveable { mutableStateOf<Int?>(null) }

    fun switchInputMode(newMode: Int) {
        if (shouldShowGamepadGuide(newMode, sharedPrefs.getBoolean("has_seen_gamepad_guide", false))) {
            pendingGamepadMode = newMode
            showGamepadGuide = true
        } else {
            launchMode = newMode
            sharedPrefs.edit { putInt("launch_mode", newMode) }
        }
    }
    var connectionAttempts by rememberSaveable { mutableIntStateOf(0) }
    var showTroubleshootingNudge by rememberSaveable { mutableStateOf(false) }
    var previousConnectionState by remember { mutableStateOf<Boolean?>(null) }
    var connectionTransitions by remember { mutableStateOf(emptyList<Long>()) }

    LaunchedEffect(devModeRefreshTrigger) {
        if (sharedPrefs.getBoolean("is_developer_mode", false)) {
            if (sharedPrefs.getBoolean("mock_gamepad_guide", false)) showGamepadGuide = true
            if (sharedPrefs.getBoolean("mock_troubleshooting_nudge", false)) {
                showTroubleshootingNudge = true
            }
        }
    }

    LaunchedEffect(sharedPrefs) {
        val currentVersionCode = dev.arnv.bluke.BuildConfig.VERSION_CODE
        val savedVersionCode = sharedPrefs.getInt("last_run_version_code", 0)
        val hasSeenOnboarding = sharedPrefs.getBoolean("has_seen_onboarding", false)
        descriptorRefreshRequired = requiresHidDescriptorRefresh(
            savedRevision = sharedPrefs.getInt(HID_DESCRIPTOR_REVISION_PREFERENCE, 1),
            isExistingInstallation = savedVersionCode > 0 || hasSeenOnboarding,
        )
        
        // If they have seen onboarding, they are an existing user.
        // If savedVersionCode < currentVersionCode, it's an update.
        if ((savedVersionCode > 0 && savedVersionCode < currentVersionCode) || 
            (savedVersionCode == 0 && hasSeenOnboarding) || descriptorRefreshRequired) {
            showUpdateDialog = true
        }
        
        if (savedVersionCode != currentVersionCode) {
            sharedPrefs.edit { putInt("last_run_version_code", currentVersionCode) }
        }
    }
    
    if (sharedPrefs.getBoolean("is_developer_mode", false) && sharedPrefs.getBoolean("mock_update_popup", false)) {
        showUpdateDialog = true
    }

    if (showUpdateDialog) {
        val versionName = dev.arnv.bluke.BuildConfig.VERSION_NAME
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { 
                showUpdateDialog = false
                sharedPrefs.edit { putBoolean("mock_update_popup", false) }
            },
            title = {
                Text(
                    if (descriptorRefreshRequired) "Bluetooth pairing refresh required"
                    else "Updated to v$versionName"
                )
            },
            text = {
                Text(
                    if (descriptorRefreshRequired) {
                        "Bluke now uses one shared HID descriptor for Native, Android and Web, " +
                            "but Bluetooth hosts cache the old layout. To use the new controls, forget the host on this phone, " +
                            "remove Bluke on the host, then pair again once. Reinstalling the app alone is not enough. " +
                            "After this one-time refresh, all controller modes switch live without reconnecting or re-pairing."
                    } else {
                        "We've added new features and made significant underlying changes to the controller!"
                    }
                )
            },
            confirmButton = {
                Button(onClick = { 
                    showUpdateDialog = false 
                    sharedPrefs.edit {
                        putBoolean("mock_update_popup", false)
                        if (descriptorRefreshRequired) {
                            putInt(HID_DESCRIPTOR_REVISION_PREFERENCE, CURRENT_HID_DESCRIPTOR_REVISION)
                        }
                    }
                    descriptorRefreshRequired = false
                }) {
                    Text("Got it")
                }
            }
        )
    }

    // Connection helper declared at outer scope
    val isConnected = btState is BluetoothState.Connected
    val offerSettingsPairingHelp = shouldOfferSettingsPairingHelp(btState, homeUiState.hidLifecycleState)

    LaunchedEffect(offerSettingsPairingHelp) {
        if (offerSettingsPairingHelp) showTroubleshootingNudge = true
    }

    LaunchedEffect(isConnected) {
        val previousState = previousConnectionState
        if (previousState != null && previousState != isConnected) {
            val now = SystemClock.elapsedRealtime()
            connectionTransitions = (connectionTransitions + now).filter {
                now - it <= CONNECTION_HELP_CHURN_WINDOW_MILLIS
            }
            if (
                shouldOfferConnectionHelp(
                    transitionTimestamps = connectionTransitions,
                    nowMillis = now,
                )
            ) {
                showTroubleshootingNudge = true
            }
        }
        previousConnectionState = isConnected
        if (isConnected) {
            connectionAttempts = 0
            showTroubleshootingNudge = false
        }
    }

    LaunchedEffect(btMessage, isConnected) {
        val attemptInProgress = btMessage.contains("connecting", ignoreCase = true) ||
            btMessage.contains("pairing", ignoreCase = true) ||
            btMessage.contains("switching", ignoreCase = true)
        if (!isConnected && attemptInProgress) {
            kotlinx.coroutines.delay(CONNECTION_HELP_STALL_MILLIS)
            if (shouldOfferConnectionHelp(stalledForMillis = CONNECTION_HELP_STALL_MILLIS)) {
                showTroubleshootingNudge = true
            }
        }
    }

    if (showGamepadGuide) {
        AlertDialog(
            onDismissRequest = {
                showGamepadGuide = false
                pendingGamepadMode = null
                sharedPrefs.edit { putBoolean("mock_gamepad_guide", false) }
            },
            icon = { Icon(Icons.Default.SportsEsports, contentDescription = null) },
            title = { Text("Before using the gamepad") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Choose Native for Windows/Linux games, Android for games on Android, or Web when a browser game ignores directions. Tap the controller mode button to switch; hold it to open settings.")
                    Text("On Windows, some newer games accept only Xbox XInput controllers. Bluke is a standard Bluetooth HID gamepad, so Steam Input or another compatibility layer may be needed.")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        sharedPrefs.edit { putBoolean("has_seen_gamepad_guide", true) }
                        sharedPrefs.edit { putBoolean("mock_gamepad_guide", false) }
                        showGamepadGuide = false
                        pendingGamepadMode?.let {
                            launchMode = it
                            sharedPrefs.edit { putInt("launch_mode", it) }
                        }
                        pendingGamepadMode = null
                        isKeyboardActive = true
                    },
                ) { Text("Open gamepad") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showGamepadGuide = false
                    pendingGamepadMode = null
                    sharedPrefs.edit { putBoolean("mock_gamepad_guide", false) }
                }) { Text("Not now") }
            },
        )
    }

    val keyboardState = rememberKeyboardSurfaceState(btManager, soundSynth, sharedPrefs, homeUiState, isConnected)

    // Show Toast for connection errors, timeouts, rejections, or pairing failures
    LaunchedEffect(btMessage, btState) {
        if (shouldShowBluetoothErrorToast(btState, btMessage)) {
            android.widget.Toast.makeText(context, btMessage, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // Toggle orientation and full-screen layout helper automatically
    LaunchedEffect(isKeyboardActive) {
        val activity = context as? Activity ?: return@LaunchedEffect
        val window = activity.window ?: return@LaunchedEffect
        if (isKeyboardActive) {
            try {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            } catch (e: Exception) {
                Log.e("HomeScreen", "Error setting orientation to landscape: ${e.message}")
            }
            // Hide System UI for immersive mechanical keypad experience
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    window.insetsController?.hide(
                        android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars()
                    )
                    window.insetsController?.systemBarsBehavior =
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    @Suppress("DEPRECATION")
                    window.decorView.systemUiVisibility = (
                        View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    )
                }
            } catch (e: Exception) {
                Log.e("HomeScreen", "Error hiding system bars: ${e.message}")
            }
        } else {
            try {
                @Suppress("SourceLockedOrientationActivity")
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } catch (e: Exception) {
                Log.e("HomeScreen", "Error setting orientation to portrait: ${e.message}")
            }
            // Show system UI normally in config mode
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    window.insetsController?.show(
                        android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars()
                    )
                } else {
                    @Suppress("DEPRECATION")
                    window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
                }
            } catch (e: Exception) {
                Log.e("HomeScreen", "Error showing system bars: ${e.message}")
            }
        }
    }

    AnimatedContent(
        targetState = isKeyboardActive,
        transitionSpec = {
            fadeIn() togetherWith fadeOut()
        },
        label = "screen_navigation"
    ) { keyboardActive ->
        if (keyboardActive) {
            InputSurface(
                keyboardState = keyboardState,
                btManager = btManager,
                soundSynth = soundSynth,
                sharedPrefs = sharedPrefs,
                launchMode = launchMode,
                isConnected = isConnected,
                host = surfaceHost(btManager, homeUiState, context),
                onClose = { isKeyboardActive = false },
                onModeChange = { newMode -> switchInputMode(newMode) },
            )
        } else {
            // Configuration & Setup Portrait Screen
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                topBar = {
                    TopAppBar(
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = stringResource(id = R.string.app_name),
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleLarge,
                                    modifier = Modifier.clickable {
                                        easterEggClicks++
                                        if (easterEggClicks < 5) {
                                            android.widget.Toast.makeText(context, "${5 - easterEggClicks} more clicks to activate", android.widget.Toast.LENGTH_SHORT).show()
                                        } else {
                                            easterEggClicks = 0
                                            val intent = Intent(Intent.ACTION_VIEW, "https://www.youtube.com/watch?v=dQw4w9WgXcQ".toUri())
                                            context.startActivity(intent)
                                        }
                                    }
                                )
                            }
                        },
                        actions = {
                            IconButton(onClick = { 
                                context.startActivity(Intent(context, dev.arnv.bluke.SettingsActivity::class.java))
                            }) {
                                Icon(Icons.Default.Settings, contentDescription = "Settings")
                            }
                        }
                    )
                }
            ) { innerPadding ->
                // Immersive Hub UI
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = innerPadding.calculateTopPadding())
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    if (btState.blocksInputLaunch()) {
                        ProfileNotSupportedScreen(
                            bluetoothState = btState,
                            onEnableBluetooth = {
                                btLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                            },
                            onRetry = btManager::retryBluetoothCapabilities
                        )
                    } else {
                        var isPairedExpanded by rememberSaveable { mutableStateOf(true) }
                        var isDiscoveredExpanded by rememberSaveable { mutableStateOf(true) }
                        val connectedDeviceState = homeUiState.connectedDevice

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .navigationBarsPadding(),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 100.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // Unified Top Scan & Status Card
                            item {
                                StatusHeaderCard(
                                    bluetoothState = btState,
                                    hidLifecycleState = homeUiState.hidLifecycleState,
                                    isScanning = isScanning,
                                    onToggleScan = {
                                        if (isScanning) btManager.stopScanning() else btManager.startScanning()
                                    },
                                    onRestartHid = btManager::restartHidService
                                )
                            }

                            if (showTroubleshootingNudge) {
                                item {
                                    TroubleshootingNudgeCard(
                                        offerSettingsPairingHelp = offerSettingsPairingHelp,
                                        onScan = { btManager.startScanning() },
                                        onOpenHelp = {
                                            context.startActivity(Intent(context, HelpActivity::class.java))
                                        },
                                        onDismiss = {
                                            showTroubleshootingNudge = false
                                            sharedPrefs.edit { putBoolean("mock_troubleshooting_nudge", false) }
                                        },
                                    )
                                }
                            }

                            DeviceListSection(
                                bluetoothState = btState,
                                statusMessage = btMessage,
                                connectedDevice = connectedDeviceState,
                                connectionTargetAddress = homeUiState.connectionTargetAddress,
                                bondedDevices = bondedDevices,
                                scannedDevices = scannedDevices,
                                isScanning = isScanning,
                                showMacAddress = showMacAddress,
                                hideUnknownDevices = hideUnknownDevices,
                                hideUnsupportedDevices = hideUnsupportedDevices,
                                isPairedExpanded = isPairedExpanded,
                                isDiscoveredExpanded = isDiscoveredExpanded,
                                onPairedExpandedChange = { isPairedExpanded = it },
                                onDiscoveredExpandedChange = { isDiscoveredExpanded = it },
                                onConnect = { device ->
                                    connectionAttempts += 1
                                    if (shouldOfferConnectionHelp(connectionAttempts = connectionAttempts)) {
                                        showTroubleshootingNudge = true
                                    }
                                    btManager.connectDevice(device)
                                },
                                onDisconnect = btManager::disconnectDevice
                            )
                        }
                    }

                    // Sticky Launch Button
                    if (!btState.blocksInputLaunch()) Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.background)
                                )
                            )
                            .navigationBarsPadding()
                            .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 24.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Circular Mode Toggle Indicator Button
                            IconButton(
                                onClick = {
                                    val enabledModes = sharedPrefs.enabledInputModes().map(InputMode::id)
                                    val currentIndex = enabledModes.indexOf(launchMode).coerceAtLeast(0)
                                    val nextMode = enabledModes[(currentIndex + 1) % enabledModes.size]
                                    launchMode = nextMode
                                    sharedPrefs.edit { putInt("launch_mode", nextMode) }
                                    if (isHapticsEnabled) {
                                        view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                                    }
                                },
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.secondaryContainer)
                                    .testTag("mode_toggle_btn")
                            ) {
                                val modeIcon = when (launchMode) {
                                    1 -> Icons.Default.Mouse
                                    2 -> Icons.Default.SportsEsports
                                    4 -> Icons.Filled.SmartDisplay
                                    else -> Icons.Default.Keyboard
                                }
                                Icon(
                                    imageVector = modeIcon,
                                    contentDescription = "Cycle Input Mode",
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(26.dp)
                                )
                            }

                            // Dynamic Launch Option button
                            Button(
                                onClick = {
                                    if (shouldShowGamepadGuide(launchMode, sharedPrefs.getBoolean("has_seen_gamepad_guide", false))) {
                                        showGamepadGuide = true
                                    } else {
                                        isKeyboardActive = true
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(64.dp)
                                    .testTag("start_keyboard_btn"),
                                shape = CircleShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                val launchText = when (launchMode) {
                                    1 -> "Launch Touchpad"
                                    2 -> "Launch Gamepad"
                                    4 -> "Launch Multimedia"
                                    else -> "Launch Keyboard"
                                }
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowUp,
                                    contentDescription = "Launch Icon",
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = launchText,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

            }
        }
    }
}

}

@Composable
private fun TroubleshootingNudgeCard(
    offerSettingsPairingHelp: Boolean,
    onScan: () -> Unit,
    onOpenHelp: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
        shape = RoundedCornerShape(24.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = null)
            Column(modifier = Modifier.weight(1f)) {
                Text("Having trouble connecting?", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (offerSettingsPairingHelp) {
                        "If you paired through Bluetooth settings, forget the pairing on both devices, then use Scan in Bluke to pair and connect again."
                    } else {
                        "Check both pairing prompts or follow the safe repair steps. A slow attempt does not automatically mean this phone is incompatible."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (offerSettingsPairingHelp) {
                        TextButton(onClick = onScan) { Text("Scan") }
                    }
                    TextButton(onClick = onOpenHelp) { Text("Help") }
                }
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Dismiss")
            }
        }
    }
}
