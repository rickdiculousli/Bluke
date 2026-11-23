package dev.arnv.bluke.ui

import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.SharedPreferences
import android.view.View
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.arnv.bluke.KeyboardLayoutsActivity
import dev.arnv.bluke.KeyboardThemesActivity
import dev.arnv.bluke.QuickCycleActivity
import dev.arnv.bluke.SoundPacksActivity
import dev.arnv.bluke.bluetooth.BluetoothKeyboardManager
import dev.arnv.bluke.data.CYCLE_KEYBOARD_GEOMETRIES_PREFERENCE
import dev.arnv.bluke.data.CYCLE_KEYBOARD_THEMES_PREFERENCE
import dev.arnv.bluke.data.KEYBOARD_GEOMETRY_PREFERENCE
import dev.arnv.bluke.data.KeyboardThemeRepository
import dev.arnv.bluke.sound.KeyboardSoundSynthesizer
import dev.arnv.bluke.sound.soundCycleSelection
import kotlinx.coroutines.delay

/**
 * The keyboard surface's state. The home screen remembers it, so it lives as long as the home
 * screen rather than the surface: lock LEDs, held keys and a pending character layout survive
 * closing the surface or switching to another one. The other surfaces read the keyboard theme
 * (for their case) and the modifier style.
 */
@Stable
class KeyboardSurfaceState internal constructor(
    private val btManager: BluetoothKeyboardManager,
    private val soundSynth: KeyboardSoundSynthesizer,
    private val sharedPrefs: SharedPreferences,
    internal val keyboardThemeRepository: KeyboardThemeRepository,
    selectedGeometry: MutableState<KeyboardGeometry>,
    selectedKeyboardTheme: MutableState<KeyboardThemeDefinition>,
    characterLayout: MutableState<KeyboardCharacterLayout>,
    modifierStyle: MutableState<KeyboardModifierStyle>,
    isMuted: MutableState<Boolean>,
    isCapsLockActive: MutableState<Boolean>,
    isNumLockActive: MutableState<Boolean>,
    isScrollLockActive: MutableState<Boolean>,
) {
    var selectedGeometry by selectedGeometry
    var selectedKeyboardTheme by selectedKeyboardTheme
    var characterLayout by characterLayout
    var modifierStyle by modifierStyle
    var isMuted by isMuted

    // Lock indicators: one source of truth, set by local presses and by the host's LED reports.
    var isCapsLockActive by isCapsLockActive
    var isNumLockActive by isNumLockActive
    var isScrollLockActive by isScrollLockActive

    var keySensitivity by mutableFloatStateOf(sharedPrefs.getFloat("key_sensitivity", 6f))
    var lockSyncMode by mutableStateOf(sharedPrefs.getString("lock_sync_mode", "host") ?: "host")
    var currentSoundProfileName by mutableStateOf(soundSynth.getSelectedSoundProfileName())

    // Keys held on screen, drawn pressed.
    val activePressedKeys = mutableStateListOf<Int>()
    var isFnActive by mutableStateOf(false)
    private var activeConsumerKey by mutableStateOf<Int?>(null)
    private val fnConsumedKeys = mutableSetOf<Int>()
    private var layoutCycleSpaceActive by mutableStateOf(false)
    var pendingCharacterLayout by mutableStateOf<KeyboardCharacterLayout?>(null)
    var layoutSwitcherRevision by mutableIntStateOf(0)
    var showLayoutSwitcher by mutableStateOf(false)

    /** The host shown in the toolbar after it disconnects, for reconnection. */
    var lastConnectedDevice by mutableStateOf<BluetoothDevice?>(null)

    /** Settings screens change these; re-read them when the home screen resumes. */
    internal fun reloadPreferences() {
        val soundEnabled = sharedPrefs.getBoolean("key_sound_enabled", true)
        isMuted = !soundEnabled
        soundSynth.setMute(!soundEnabled)
        currentSoundProfileName = soundSynth.getSelectedSoundProfileName()
        keySensitivity = sharedPrefs.getFloat("key_sensitivity", 6f)
        lockSyncMode = sharedPrefs.getString("lock_sync_mode", "host") ?: "host"
        characterLayout = KeyboardCharacterLayout.fromPreference(
            sharedPrefs.getString(KEYBOARD_CHARACTER_LAYOUT_PREFERENCE, null)
        )
        selectedGeometry = KeyboardGeometry.fromPreference(
            sharedPrefs.getString(KEYBOARD_GEOMETRY_PREFERENCE, null)
        )
        modifierStyle = KeyboardModifierStyle.fromPreference(
            sharedPrefs.getString(KEYBOARD_MODIFIER_STYLE_PREFERENCE, null)
        )
        selectedKeyboardTheme = keyboardThemeRepository.selectedTheme()
    }

    /** Process a local screen press or release of [keyCode]. */
    fun onKeyPress(keyCode: Int, isPress: Boolean, view: View, isHapticsEnabled: Boolean, isConnected: Boolean) {
        fun applyPendingCharacterLayoutIfReleased() {
            val shiftStillPressed = activePressedKeys.any {
                it == KeyboardLayouts.MOD_LSHIFT || it == KeyboardLayouts.MOD_RSHIFT
            }
            if (!layoutCycleSpaceActive && !shiftStillPressed) {
                pendingCharacterLayout?.let { nextLayout ->
                    characterLayout = nextLayout
                    sharedPrefs.edit {
                        putString(KEYBOARD_CHARACTER_LAYOUT_PREFERENCE, nextLayout.preferenceValue)
                    }
                    pendingCharacterLayout = null
                }
            }
        }

        if (keyCode == KeyboardLayouts.KEY_SPACE) {
            val isShiftPressed = activePressedKeys.any {
                it == KeyboardLayouts.MOD_LSHIFT || it == KeyboardLayouts.MOD_RSHIFT
            }
            if (isPress && isShiftPressed && !layoutCycleSpaceActive) {
                if (isHapticsEnabled) {
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_PRESS)
                }
                layoutCycleSpaceActive = true
                activePressedKeys.add(keyCode)
                soundSynth.playPress(keyCode)
                pendingCharacterLayout = (pendingCharacterLayout ?: characterLayout).next()
                showLayoutSwitcher = true
                layoutSwitcherRevision++
                return
            }
            if (!isPress && layoutCycleSpaceActive) {
                layoutCycleSpaceActive = false
                activePressedKeys.remove(keyCode)
                soundSynth.playRelease(keyCode)
                applyPendingCharacterLayoutIfReleased()
                return
            }
        }

        if (keyCode == KeyboardLayouts.KEY_FN) {
            if (isPress && !isFnActive) {
                if (isHapticsEnabled) {
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_PRESS)
                }
                isFnActive = true
                activePressedKeys.add(keyCode)
                soundSynth.playPress(keyCode)
            } else if (!isPress && isFnActive) {
                isFnActive = false
                activePressedKeys.remove(keyCode)
                soundSynth.playRelease(keyCode)
                if (activeConsumerKey != null) {
                    btManager.sendConsumerControl(null)
                    activeConsumerKey = null
                }
            }
            return
        }

        val fnControl = if (isPress && isFnActive) consumerControlForFnKey(keyCode) else null
        if (fnControl != null) {
            if (isHapticsEnabled) {
                view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_PRESS)
            }
            if (fnConsumedKeys.add(keyCode)) {
                activePressedKeys.add(keyCode)
                soundSynth.playPress(keyCode)
                activeConsumerKey = keyCode
                btManager.sendConsumerControl(fnControl)
            }
            return
        }

        if (!isPress && fnConsumedKeys.remove(keyCode)) {
            activePressedKeys.remove(keyCode)
            soundSynth.playRelease(keyCode)
            if (activeConsumerKey == keyCode) {
                btManager.sendConsumerControl(null)
                activeConsumerKey = null
            }
            return
        }

        if (isPress) {
            if (isHapticsEnabled) {
                view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_PRESS)
            }
            if (!activePressedKeys.contains(keyCode)) {
                activePressedKeys.add(keyCode)
                soundSynth.playPress(keyCode)
                btManager.sendKey(keyCode, true)

                // Only toggle status indicators locally if we are disconnected OR in device-controlled mode.
                // In Host-Controlled mode, we wait for the Host OS to send an LED Output Report (to ensure true sync).
                if (!isConnected || lockSyncMode == "device") {
                    when (keyCode) {
                        0x39 -> isCapsLockActive = !isCapsLockActive // KEY_CAPSLOCK
                        0x47 -> isScrollLockActive = !isScrollLockActive // KEY_SCROLL_LOCK
                        0x53 -> isNumLockActive = !isNumLockActive // KEY_NUM_LOCK
                    }
                }
            }
        } else {
            activePressedKeys.remove(keyCode)
            soundSynth.playRelease(keyCode)
            btManager.sendKey(keyCode, false)
            applyPendingCharacterLayoutIfReleased()
        }
    }
}

/** Remembers the keyboard surface's state for as long as the caller (the home screen) stays composed. */
@Composable
internal fun rememberKeyboardSurfaceState(
    btManager: BluetoothKeyboardManager,
    soundSynth: KeyboardSoundSynthesizer,
    sharedPrefs: SharedPreferences,
    homeUiState: HomeUiState,
    isConnected: Boolean,
): KeyboardSurfaceState {
    val context = LocalContext.current
    val keyboardThemeRepository = remember(context) { KeyboardThemeRepository(context) }
    val selectedGeometry = rememberSaveable {
        mutableStateOf(
            KeyboardGeometry.fromPreference(sharedPrefs.getString(KEYBOARD_GEOMETRY_PREFERENCE, null))
        )
    }
    val selectedKeyboardTheme = remember { mutableStateOf(keyboardThemeRepository.selectedTheme()) }
    val characterLayout = rememberSaveable {
        mutableStateOf(
            KeyboardCharacterLayout.fromPreference(
                sharedPrefs.getString(KEYBOARD_CHARACTER_LAYOUT_PREFERENCE, null)
            )
        )
    }
    val modifierStyle = rememberSaveable {
        mutableStateOf(
            KeyboardModifierStyle.fromPreference(sharedPrefs.getString(KEYBOARD_MODIFIER_STYLE_PREFERENCE, null))
        )
    }
    val isMuted = rememberSaveable { mutableStateOf(!sharedPrefs.getBoolean("key_sound_enabled", true)) }
    val isCapsLockActive = rememberSaveable { mutableStateOf(false) }
    val isNumLockActive = rememberSaveable { mutableStateOf(true) }
    val isScrollLockActive = rememberSaveable { mutableStateOf(false) }
    val state = remember {
        KeyboardSurfaceState(
            btManager, soundSynth, sharedPrefs, keyboardThemeRepository,
            selectedGeometry, selectedKeyboardTheme, characterLayout, modifierStyle, isMuted,
            isCapsLockActive, isNumLockActive, isScrollLockActive,
        )
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, state) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) state.reloadPreferences()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.layoutSwitcherRevision) {
        if (state.layoutSwitcherRevision > 0) {
            delay(1_400L)
            state.showLayoutSwitcher = false
        }
    }

    LaunchedEffect(isConnected, homeUiState.capsLock, homeUiState.numLock, homeUiState.scrollLock, state.lockSyncMode) {
        if (isConnected && state.lockSyncMode == "host") {
            state.isCapsLockActive = homeUiState.capsLock
            state.isNumLockActive = homeUiState.numLock
            state.isScrollLockActive = homeUiState.scrollLock
        }
    }

    LaunchedEffect(isConnected, homeUiState.connectedDevice) {
        if (isConnected) {
            homeUiState.connectedDevice?.let { state.lastConnectedDevice = it }
        }
    }
    return state
}

/** The keyboard surface: its toolbar over the keyboard on the case. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun KeyboardSurface(
    state: KeyboardSurfaceState,
    btManager: BluetoothKeyboardManager,
    soundSynth: KeyboardSoundSynthesizer,
    sharedPrefs: SharedPreferences,
    caseBrush: Brush,
    launchMode: Int,
    isConnected: Boolean,
    isHapticsEnabled: Boolean,
    connectedDevice: BluetoothDevice?,
    onClose: () -> Unit,
    onModeChange: (Int) -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    // Seamless full-screen canvas acting as the aluminum keyboard plate and chassis
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(caseBrush)
            .padding(bottom = 6.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 1. Sleek Frosted Glass Top Settings Bar (KBSim Web Style Toggles Toolbar)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left Section: Exit, Connection status and Reconnect Button
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Exit Button (Pill style to match other buttons)
                Row(
                    modifier = Modifier
                        .height(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(alpha = 0.15f))
                        .clickable(onClick = onClose)
                        .padding(horizontal = 8.dp)
                        .testTag("exit_keyboard_btn"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Exit",
                        tint = Color.White,
                        modifier = Modifier.size(10.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Close",
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Rotating Mode Switcher
                Row(
                    modifier = Modifier
                        .height(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(alpha = 0.15f))
                        .combinedClickable(
                            onClickLabel = "Next input mode",
                            onLongClickLabel = "Configure input mode cycle",
                            onClick = {
                                val enabledModes = sharedPrefs.enabledInputModes().map(InputMode::id)
                                val currentIndexInEnabled = enabledModes.indexOf(launchMode)
                                val nextIndex = (currentIndexInEnabled + 1) % enabledModes.size
                                val nextMode = enabledModes[nextIndex]
                                onModeChange(nextMode)
                                if (isHapticsEnabled) {
                                    view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                                }
                            },
                            onLongClick = {
                                context.startActivity(Intent(context, QuickCycleActivity::class.java))
                            },
                        )
                        .padding(horizontal = 8.dp)
                        .testTag("keyboard_mode_cycle_btn"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Keyboard,
                        contentDescription = "Switch Mode",
                        tint = Color.White,
                        modifier = Modifier.size(11.dp)
                    )
                    Text(
                        text = "Keyboard Mode",
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Status LED and connection details
                val statusLedColor = if (isConnected) Color(0xFF39FF14) else Color(0xFFFF9800)
                // Use collected state (not .value) so UI reacts to changes from background
                val activeDevice = connectedDevice ?: state.lastConnectedDevice ?: btManager.getReconnectTarget()
                val deviceName = activeDevice?.name ?: "No Host"

                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(statusLedColor)
                )

                Text(
                    text = deviceName,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif
                )

                // Reconnect Button
                ReconnectHostButton(btManager)
            }

            // Right Section: lock LEDs indicators, configuration pills, and mute button
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // 1. Keyboard Status Lock LEDs Panel
                Row(
                    modifier = Modifier
                        .height(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(alpha = 0.15f))
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // CAPS
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable {
                                if (isConnected) {
                                    btManager.sendKey(0x39, true)
                                    btManager.sendKey(0x39, false)
                                    if (state.lockSyncMode == "device") {
                                        state.isCapsLockActive = !state.isCapsLockActive
                                    }
                                } else {
                                    state.isCapsLockActive = !state.isCapsLockActive
                                }
                            }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(if (state.isCapsLockActive) Color(0xFF39FF14) else Color.White.copy(alpha = 0.15f))
                        )
                        Text(
                            text = "CAPS",
                            color = if (state.isCapsLockActive) Color.White else Color.White.copy(alpha = 0.4f),
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.SansSerif,
                            style = androidx.compose.ui.text.TextStyle(
                                platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)
                            )
                        )
                    }

                    // NUM
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable {
                                if (isConnected) {
                                    btManager.sendKey(0x53, true)
                                    btManager.sendKey(0x53, false)
                                    if (state.lockSyncMode == "device") {
                                        state.isNumLockActive = !state.isNumLockActive
                                    }
                                } else {
                                    state.isNumLockActive = !state.isNumLockActive
                                }
                            }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(if (state.isNumLockActive) Color(0xFF39FF14) else Color.White.copy(alpha = 0.15f))
                        )
                        Text(
                            text = "NUM",
                            color = if (state.isNumLockActive) Color.White else Color.White.copy(alpha = 0.4f),
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.SansSerif,
                            style = androidx.compose.ui.text.TextStyle(
                                platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)
                            )
                        )
                    }

                    // SCR
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable {
                                if (isConnected) {
                                    btManager.sendKey(0x47, true)
                                    btManager.sendKey(0x47, false)
                                    if (state.lockSyncMode == "device") {
                                        state.isScrollLockActive = !state.isScrollLockActive
                                    }
                                } else {
                                    state.isScrollLockActive = !state.isScrollLockActive
                                }
                            }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(if (state.isScrollLockActive) Color(0xFF39FF14) else Color.White.copy(alpha = 0.15f))
                        )
                        Text(
                            text = "SCR",
                            color = if (state.isScrollLockActive) Color.White else Color.White.copy(alpha = 0.4f),
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.SansSerif,
                            style = androidx.compose.ui.text.TextStyle(
                                platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)
                            )
                        )
                    }
                }

                // 2. Layout Selector Pill
                Row(
                    modifier = Modifier
                        .height(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(alpha = 0.15f))
                        .combinedClickable(
                            onClickLabel = "Next keyboard layout",
                            onLongClickLabel = "Configure keyboard layout cycle",
                            onClick = {
                                val savedSet = sharedPrefs.getStringSet(CYCLE_KEYBOARD_GEOMETRIES_PREFERENCE, null)
                                val enabledLayouts = if (savedSet == null) {
                                    KeyboardGeometry.entries
                                } else {
                                    KeyboardGeometry.entries.filter { savedSet.contains(it.name) }
                                }.ifEmpty { listOf(state.selectedGeometry) }
                                val currentIndexInEnabled = enabledLayouts.indexOf(state.selectedGeometry)
                                val nextIndex = if (currentIndexInEnabled < 0) 0 else (currentIndexInEnabled + 1) % enabledLayouts.size
                                state.selectedGeometry = enabledLayouts[nextIndex]
                                sharedPrefs.edit { putString(KEYBOARD_GEOMETRY_PREFERENCE, state.selectedGeometry.name) }
                                soundSynth.playPress()
                            },
                            onLongClick = {
                                context.startActivity(Intent(context, KeyboardLayoutsActivity::class.java))
                            },
                        )
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Keyboard,
                        contentDescription = "Layout",
                        tint = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.size(10.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = state.selectedGeometry.displayName,
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 90.dp),
                    )
                }

                // 3. Switch Selector Pill
                Row(
                    modifier = Modifier
                        .height(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(alpha = 0.15f))
                        .combinedClickable(
                            onClickLabel = "Next key sound",
                            onLongClickLabel = "Manage key sounds",
                            onClick = {
                                val enabledProfiles = soundCycleSelection(
                                    sharedPrefs,
                                    dev.arnv.bluke.sound.CustomSoundPackRepository(context).listPacks().map { it.id },
                                )
                                soundSynth.cycleSoundProfile(enabledProfiles)
                                state.currentSoundProfileName = soundSynth.getSelectedSoundProfileName()
                                soundSynth.playPress()
                            },
                            onLongClick = {
                                context.startActivity(Intent(context, SoundPacksActivity::class.java))
                            },
                        )
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = "Switch",
                        tint = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.size(10.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = state.currentSoundProfileName,
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // 4. Keyboard Theme Selector Pill
                Row(
                    modifier = Modifier
                        .height(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(alpha = 0.15f))
                        .combinedClickable(
                            onClickLabel = "Next keyboard theme",
                            onLongClickLabel = "Customize keyboard themes",
                            onClick = {
                                val availableThemes = state.keyboardThemeRepository.allThemes()
                                val savedThemeIds = sharedPrefs.getStringSet(
                                    CYCLE_KEYBOARD_THEMES_PREFERENCE,
                                    null,
                                )
                                val enabledThemes = if (savedThemeIds == null) {
                                    availableThemes
                                } else {
                                    availableThemes.filter { theme -> theme.id in savedThemeIds }
                                }.ifEmpty { listOf(state.selectedKeyboardTheme) }
                                val currentIndex = enabledThemes.indexOfFirst {
                                    it.id == state.selectedKeyboardTheme.id
                                }
                                val nextIndex = if (currentIndex < 0) {
                                    0
                                } else {
                                    (currentIndex + 1) % enabledThemes.size
                                }
                                state.selectedKeyboardTheme = enabledThemes[nextIndex]
                                state.keyboardThemeRepository.selectTheme(state.selectedKeyboardTheme.id)
                                soundSynth.playPress()
                            },
                            onLongClick = {
                                context.startActivity(
                                    Intent(context, KeyboardThemesActivity::class.java),
                                )
                            },
                        )
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(state.selectedKeyboardTheme.accentStyle.backgroundArgb))
                            .border(0.5.dp, Color.White, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = state.selectedKeyboardTheme.name,
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 80.dp),
                    )
                }

                // 5. Mute Speaker Button (Squarish, height 28dp, radius 6dp)
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(alpha = 0.15f))
                        .clickable {
                            val muted = !state.isMuted
                            state.isMuted = muted
                            soundSynth.setMute(muted)
                            sharedPrefs.edit { putBoolean("key_sound_enabled", !muted) }
                        }
                        .testTag("mute_toggle"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (state.isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = "Mute",
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }

        // 2. Main Mechanical Keyboard Chassis (centered with custom margins)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            KeyboardView(
                geometry = state.selectedGeometry,
                theme = state.selectedKeyboardTheme,
                characterLayout = state.characterLayout,
                modifierStyle = state.modifierStyle,
                activePressedKeys = state.activePressedKeys,
                isConnected = isConnected,
                isCapsLockActive = state.isCapsLockActive,
                isNumLockActive = state.isNumLockActive,
                isScrollLockActive = state.isScrollLockActive,
                isFnActive = state.isFnActive,
                keySensitivity = state.keySensitivity,
                onKeyPressChange = { code, press -> state.onKeyPress(code, press, view, isHapticsEnabled, isConnected) }
            )
            KeyboardLayoutSwitcherOverlay(
                visible = state.showLayoutSwitcher,
                selectedLayout = state.pendingCharacterLayout ?: state.characterLayout,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}
