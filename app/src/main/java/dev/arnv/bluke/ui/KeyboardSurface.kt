package dev.arnv.bluke.ui

import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.arnv.bluke.KeyboardThemesActivity
import dev.arnv.bluke.SoundPacksActivity
import dev.arnv.bluke.bluetooth.BluetoothKeyboardManager
import dev.arnv.bluke.data.KEYBOARD_GEOMETRY_PREFERENCE
import dev.arnv.bluke.data.KEYBOARD_THEME_PREFERENCE
import dev.arnv.bluke.data.KeyboardThemeRepository
import dev.arnv.bluke.sound.CUSTOM_SOUND_PACK_PREFERENCE
import dev.arnv.bluke.sound.CustomSoundPackRepository
import dev.arnv.bluke.sound.KEY_SOUND_ENABLED_PREFERENCE
import dev.arnv.bluke.sound.KeyboardSoundSynthesizer
import dev.arnv.bluke.sound.SELECTED_BUILT_IN_SOUND_PREFERENCE
import dev.arnv.bluke.sound.SwitchType
import dev.arnv.bluke.sound.selectedBuiltInSound
import kotlinx.coroutines.delay

private const val KEY_SENSITIVITY_PREFERENCE = "key_sensitivity"
private const val LOCK_SYNC_MODE_PREFERENCE = "lock_sync_mode"

private const val KEY_CAPS_LOCK = 0x39
private const val KEY_NUM_LOCK = 0x53
private const val KEY_SCROLL_LOCK = 0x47

/** The key sound the keyboard plays: a built-in switch, or an imported pack by its id. */
sealed interface KeyboardSound {
    data class BuiltIn(val switchType: SwitchType) : KeyboardSound
    data class Custom(val packId: String) : KeyboardSound
}

/**
 * The keyboard's settings, as the Tune sheet (and spec 005's Settings) shows them. Its keys and stored
 * values are the ones the rest of the app already reads; [write] stores a choice the same way the
 * themes and sound-pack screens do.
 */
data class KeyboardTuneState(
    val geometry: KeyboardGeometry = KeyboardGeometry.fromPreference(null),
    val characterLayout: KeyboardCharacterLayout = KeyboardCharacterLayout.US_QWERTY,
    val modifierStyle: KeyboardModifierStyle = KeyboardModifierStyle.PC,
    val themeId: String = KeyboardThemeCatalog.defaultTheme.id,
    val sound: KeyboardSound = KeyboardSound.BuiltIn(SwitchType.CHERRY_MX_BROWN),
) {
    fun write(prefs: SharedPreferences) = prefs.edit {
        putString(KEYBOARD_GEOMETRY_PREFERENCE, geometry.name)
        putString(KEYBOARD_CHARACTER_LAYOUT_PREFERENCE, characterLayout.preferenceValue)
        putString(KEYBOARD_MODIFIER_STYLE_PREFERENCE, modifierStyle.preferenceValue)
        putString(KEYBOARD_THEME_PREFERENCE, themeId)
        when (sound) {
            is KeyboardSound.BuiltIn -> {
                remove(CUSTOM_SOUND_PACK_PREFERENCE)
                putString(SELECTED_BUILT_IN_SOUND_PREFERENCE, sound.switchType.name)
            }
            is KeyboardSound.Custom -> putString(CUSTOM_SOUND_PACK_PREFERENCE, sound.packId)
        }
    }

    companion object {
        val KEYS = arrayOf(
            KEYBOARD_GEOMETRY_PREFERENCE,
            KEYBOARD_CHARACTER_LAYOUT_PREFERENCE,
            KEYBOARD_MODIFIER_STYLE_PREFERENCE,
            KEYBOARD_THEME_PREFERENCE,
            CUSTOM_SOUND_PACK_PREFERENCE,
            SELECTED_BUILT_IN_SOUND_PREFERENCE,
        )

        fun read(prefs: SharedPreferences) = KeyboardTuneState(
            geometry = KeyboardGeometry.fromPreference(prefs.getString(KEYBOARD_GEOMETRY_PREFERENCE, null)),
            characterLayout = KeyboardCharacterLayout.fromPreference(prefs.getString(KEYBOARD_CHARACTER_LAYOUT_PREFERENCE, null)),
            modifierStyle = KeyboardModifierStyle.fromPreference(prefs.getString(KEYBOARD_MODIFIER_STYLE_PREFERENCE, null)),
            themeId = prefs.getString(KEYBOARD_THEME_PREFERENCE, null) ?: KeyboardThemeCatalog.defaultTheme.id,
            sound = prefs.getString(CUSTOM_SOUND_PACK_PREFERENCE, null)?.let(KeyboardSound::Custom)
                ?: KeyboardSound.BuiltIn(selectedBuiltInSound(prefs)),
        )
    }
}

/** What [KeyboardTuneContent] offers: every theme, and the imported sound packs by id and name. */
data class KeyboardTuneOptions(
    val themes: List<KeyboardThemeDefinition>,
    val customSoundPacks: List<Pair<String, String>>,
)

/** The keyboard's tune options, read from the theme and sound-pack stores; re-read when [refresh] changes. */
@Composable
fun rememberKeyboardTuneOptions(refresh: Any? = null): KeyboardTuneOptions {
    val context = LocalContext.current
    return remember(context, refresh) {
        KeyboardTuneOptions(
            themes = KeyboardThemeRepository(context).allThemes(),
            customSoundPacks = CustomSoundPackRepository(context).listPacks().map { it.id to it.name },
        )
    }
}

/**
 * The keyboard's settings: geometry, typing layout, modifier keys, theme and key sound. Stateless, so
 * the Tune sheet and Settings host the same controls. [onEditThemes] and [onManageSounds] open the
 * editors when the host offers them.
 */
@Composable
fun KeyboardTuneContent(
    state: KeyboardTuneState,
    onChange: (KeyboardTuneState) -> Unit,
    options: KeyboardTuneOptions,
    onEditThemes: (() -> Unit)? = null,
    onManageSounds: (() -> Unit)? = null,
) {
    TuneSection("Layout") {
        TuneChoice(
            label = "Geometry",
            options = KeyboardGeometry.entries,
            selected = state.geometry,
            optionLabel = KeyboardGeometry::displayName,
            onSelect = { onChange(state.copy(geometry = it)) },
        )
        TuneChoice(
            label = "Typing layout",
            options = KeyboardCharacterLayout.entries,
            selected = state.characterLayout,
            optionLabel = KeyboardCharacterLayout::displayName,
            onSelect = { onChange(state.copy(characterLayout = it)) },
        )
        TuneChoice(
            label = "Modifier keys",
            options = KeyboardModifierStyle.entries,
            selected = state.modifierStyle,
            optionLabel = { "${it.displayName} · ${it.summary.substringBefore(',')}" },
            onSelect = { onChange(state.copy(modifierStyle = it)) },
        )
    }
    TuneSection("Theme") {
        ThemeChoice(options.themes, state.themeId) { onChange(state.copy(themeId = it)) }
        if (onEditThemes != null) TuneAction("Edit themes", Icons.Default.Edit, onEditThemes)
    }
    TuneSection("Key sound") {
        val sounds = SwitchType.entries.map { KeyboardSound.BuiltIn(it) to it.displayName } +
            options.customSoundPacks.map { (id, name) -> KeyboardSound.Custom(id) to name }
        val names = sounds.toMap()
        TuneChoice(
            label = "Sound pack",
            options = sounds.map { it.first },
            selected = state.sound,
            optionLabel = { names[it] ?: "" },
            onSelect = { onChange(state.copy(sound = it)) },
        )
        if (onManageSounds != null) TuneAction("Manage sound packs", Icons.Default.LibraryMusic, onManageSounds)
    }
}

/** Themes as chips, each with a swatch of its keycaps, so the choice shows what it picks. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThemeChoice(themes: List<KeyboardThemeDefinition>, selectedId: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Keycaps", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            themes.forEach { theme ->
                FilterChip(
                    selected = theme.id == selectedId,
                    onClick = { onSelect(theme.id) },
                    label = { Text(theme.name) },
                    leadingIcon = { ThemeSwatch(theme) },
                )
            }
        }
    }
}

@Composable
private fun ThemeSwatch(theme: KeyboardThemeDefinition) {
    Box(
        Modifier
            .size(18.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .padding(1.dp)
            .background(Color(theme.alphaStyle.backgroundArgb), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(8.dp).background(Color(theme.accentStyle.backgroundArgb), CircleShape))
    }
}

/**
 * The keyboard surface's state. The home screen remembers it, so it lives as long as the home
 * screen rather than the surface: lock LEDs, held keys and a pending character layout survive
 * closing the surface or switching to another one. The other surfaces read the keyboard theme
 * (for their case) and the modifier style. Settings are read from preferences as they change.
 */
@Stable
class KeyboardSurfaceState internal constructor(
    private val btManager: BluetoothKeyboardManager,
    private val soundSynth: KeyboardSoundSynthesizer,
    private val sharedPrefs: SharedPreferences,
    tune: State<KeyboardTuneState>,
    selectedKeyboardTheme: State<KeyboardThemeDefinition>,
    soundOn: State<Boolean>,
    keySensitivity: State<Float>,
    lockSyncMode: State<String>,
    isCapsLockActive: MutableState<Boolean>,
    isNumLockActive: MutableState<Boolean>,
    isScrollLockActive: MutableState<Boolean>,
) {
    val tune by tune
    val selectedKeyboardTheme by selectedKeyboardTheme
    val selectedGeometry: KeyboardGeometry get() = tune.geometry
    val characterLayout: KeyboardCharacterLayout get() = tune.characterLayout
    val modifierStyle: KeyboardModifierStyle get() = tune.modifierStyle
    val isSoundOn by soundOn
    val keySensitivity by keySensitivity
    val lockSyncMode by lockSyncMode

    // Lock indicators: one source of truth, set by local presses and by the host's LED reports.
    var isCapsLockActive by isCapsLockActive
    var isNumLockActive by isNumLockActive
    var isScrollLockActive by isScrollLockActive

    // Keys held on screen, drawn pressed.
    val activePressedKeys = mutableStateListOf<Int>()
    var isFnActive by mutableStateOf(false)
    private var activeConsumerKey by mutableStateOf<Int?>(null)
    private val fnConsumedKeys = mutableSetOf<Int>()
    private var layoutCycleSpaceActive by mutableStateOf(false)
    var pendingCharacterLayout by mutableStateOf<KeyboardCharacterLayout?>(null)
    var layoutSwitcherRevision by mutableIntStateOf(0)
    var showLayoutSwitcher by mutableStateOf(false)

    /** Turns key sound on or off, for now and for next time. */
    fun setSoundOn(on: Boolean) {
        soundSynth.setMute(!on)
        sharedPrefs.edit { putBoolean(KEY_SOUND_ENABLED_PREFERENCE, on) }
    }

    /**
     * A tap on a toolbar lock light. Connected, it sends the lock key and, unless the device owns the
     * lights, waits for the host's LED report; offline, it only flips the light.
     */
    fun toggleLock(keyCode: Int, isConnected: Boolean) {
        if (isConnected) {
            btManager.sendKey(keyCode, true)
            btManager.sendKey(keyCode, false)
            if (lockSyncMode == "device") flipLock(keyCode)
        } else {
            flipLock(keyCode)
        }
    }

    private fun flipLock(keyCode: Int) {
        when (keyCode) {
            KEY_CAPS_LOCK -> isCapsLockActive = !isCapsLockActive
            KEY_SCROLL_LOCK -> isScrollLockActive = !isScrollLockActive
            KEY_NUM_LOCK -> isNumLockActive = !isNumLockActive
        }
    }

    /** Process a local screen press or release of [keyCode]. A press plays one haptic, on touch-down. */
    fun onKeyPress(keyCode: Int, isPress: Boolean, haptics: SurfaceHaptics, isConnected: Boolean) {
        fun applyPendingCharacterLayoutIfReleased() {
            val shiftStillPressed = activePressedKeys.any {
                it == KeyboardLayouts.MOD_LSHIFT || it == KeyboardLayouts.MOD_RSHIFT
            }
            if (!layoutCycleSpaceActive && !shiftStillPressed) {
                pendingCharacterLayout?.let { nextLayout ->
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
                haptics.perform(HapticEvent.PRESS)
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
                haptics.perform(HapticEvent.PRESS)
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
            if (fnConsumedKeys.add(keyCode)) {
                haptics.perform(HapticEvent.PRESS)
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
            if (!activePressedKeys.contains(keyCode)) {
                haptics.perform(HapticEvent.PRESS)
                activePressedKeys.add(keyCode)
                soundSynth.playPress(keyCode)
                btManager.sendKey(keyCode, true)

                // Only toggle status indicators locally if we are disconnected OR in device-controlled mode.
                // In Host-Controlled mode, we wait for the Host OS to send an LED Output Report (to ensure true sync).
                if (!isConnected || lockSyncMode == "device") flipLock(keyCode)
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
    val tune = rememberPreference(sharedPrefs, *KeyboardTuneState.KEYS) { KeyboardTuneState.read(it) }

    // A custom theme's colours live in a file, not a preference: re-resolve the selected theme when
    // the home screen resumes, so an edit made in the themes screen shows on return.
    var resumes by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumes++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val themeId = tune.value.themeId
    val selectedKeyboardTheme = remember { mutableStateOf(keyboardThemeRepository.selectedTheme()) }
    LaunchedEffect(themeId, resumes) {
        selectedKeyboardTheme.value = keyboardThemeRepository.resolve(themeId) ?: keyboardThemeRepository.selectedTheme()
    }

    val soundOn = rememberPreference(sharedPrefs, KEY_SOUND_ENABLED_PREFERENCE) { it.getBoolean(KEY_SOUND_ENABLED_PREFERENCE, true) }
    val keySensitivity = rememberPreference(sharedPrefs, KEY_SENSITIVITY_PREFERENCE) { it.getFloat(KEY_SENSITIVITY_PREFERENCE, 6f) }
    val lockSyncMode = rememberPreference(sharedPrefs, LOCK_SYNC_MODE_PREFERENCE) {
        it.getString(LOCK_SYNC_MODE_PREFERENCE, "host") ?: "host"
    }
    val isCapsLockActive = rememberSaveable { mutableStateOf(false) }
    val isNumLockActive = rememberSaveable { mutableStateOf(true) }
    val isScrollLockActive = rememberSaveable { mutableStateOf(false) }
    val state = remember {
        KeyboardSurfaceState(
            btManager, soundSynth, sharedPrefs,
            tune, selectedKeyboardTheme, soundOn, keySensitivity, lockSyncMode,
            isCapsLockActive, isNumLockActive, isScrollLockActive,
        )
    }

    LaunchedEffect(soundOn.value) { soundSynth.setMute(!soundOn.value) }
    LaunchedEffect(tune.value.sound) { soundSynth.reloadSelectedSoundPack() }

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
    return state
}

/** The keyboard surface: the shared toolbar over the keyboard on its case, with the Tune sheet over both. */
@Composable
internal fun KeyboardSurface(
    state: KeyboardSurfaceState,
    btManager: BluetoothKeyboardManager,
    soundSynth: KeyboardSoundSynthesizer,
    sharedPrefs: SharedPreferences,
    caseBrush: Brush,
    chrome: SurfaceChrome,
    isConnected: Boolean,
) {
    val haptics = rememberSurfaceHaptics(sharedPrefs)
    val context = LocalContext.current
    var tuneOpen by rememberSaveable { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(caseBrush)) {
        Column(Modifier.fillMaxSize()) {
            SurfaceToolbar(chrome = chrome, haptics = haptics, onTune = { tuneOpen = true }) {
                SurfaceToolbarLeds(
                    leds = listOf(
                        "CAPS" to state.isCapsLockActive,
                        "NUM" to state.isNumLockActive,
                        "SCR" to state.isScrollLockActive,
                    ),
                    onToggle = { index ->
                        state.toggleLock(listOf(KEY_CAPS_LOCK, KEY_NUM_LOCK, KEY_SCROLL_LOCK)[index], isConnected)
                    },
                )
                SurfaceToolbarIconButton(
                    icon = if (state.isSoundOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                    contentDescription = "Key sound",
                    onClick = { state.setSoundOn(!state.isSoundOn) },
                    selected = state.isSoundOn,
                    modifier = Modifier.testTag("keyboard_sound_toggle"),
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
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
                    onKeyPressChange = { code, press -> state.onKeyPress(code, press, haptics, isConnected) },
                )
                KeyboardLayoutSwitcherOverlay(
                    visible = state.showLayoutSwitcher,
                    selectedLayout = state.pendingCharacterLayout ?: state.characterLayout,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }

        SurfaceTuneSheet(visible = tuneOpen, title = "Keyboard", onDismiss = { tuneOpen = false }) {
            KeyboardTuneContent(
                state = state.tune,
                onChange = { it.write(sharedPrefs) },
                options = rememberKeyboardTuneOptions(refresh = tuneOpen),
                onEditThemes = { context.startActivity(Intent(context, KeyboardThemesActivity::class.java)) },
                onManageSounds = { context.startActivity(Intent(context, SoundPacksActivity::class.java)) },
            )
        }
    }
}
