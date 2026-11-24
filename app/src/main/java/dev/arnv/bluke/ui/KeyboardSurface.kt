package dev.arnv.bluke.ui

import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import dev.arnv.bluke.sound.builtInSoundProfileId
import dev.arnv.bluke.sound.customSoundProfileId
import dev.arnv.bluke.sound.selectKeySound
import dev.arnv.bluke.sound.selectedBuiltInSound
import kotlinx.coroutines.delay

private const val KEY_SENSITIVITY_PREFERENCE = "key_sensitivity"

/** Keys an accessibility click latches rather than taps: the modifiers and Fn. */
private val LATCHING_KEYS = (KeyboardLayouts.MOD_LCTRL..KeyboardLayouts.MOD_RWIN).toSet() + KeyboardLayouts.KEY_FN

/** The key sound the keyboard plays: a built-in switch, or an imported pack by its id. */
sealed interface KeyboardSound {
    data class BuiltIn(val switchType: SwitchType) : KeyboardSound
    data class Custom(val packId: String) : KeyboardSound
}

/** The id [selectKeySound] takes for this sound. */
val KeyboardSound.profileId: String
    get() = when (this) {
        is KeyboardSound.BuiltIn -> builtInSoundProfileId(switchType)
        is KeyboardSound.Custom -> customSoundProfileId(packId)
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

    /**
     * Stores [next], chosen while this was showing. A newly chosen sound goes through [selectKeySound],
     * so picking one while muted turns key sounds on.
     */
    fun writeChange(prefs: SharedPreferences, next: KeyboardTuneState) {
        next.write(prefs)
        if (next.sound != sound) selectKeySound(prefs, next.sound.profileId)
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
 * editors when the host offers them. A host that lists key sounds or themes its own way passes
 * [showSound] or [showTheme] false.
 */
@Composable
fun KeyboardTuneContent(
    state: KeyboardTuneState,
    onChange: (KeyboardTuneState) -> Unit,
    options: KeyboardTuneOptions,
    onEditThemes: (() -> Unit)? = null,
    onManageSounds: (() -> Unit)? = null,
    showSound: Boolean = true,
    showTheme: Boolean = true,
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
    if (showTheme) TuneSection("Theme") {
        ThemeChoice(options.themes, state.themeId) { onChange(state.copy(themeId = it)) }
        if (onEditThemes != null) TuneAction("Edit themes", Icons.Default.Edit, onEditThemes)
    }
    if (showSound) TuneSection("Key sound") {
        val sounds = SwitchType.entries.map { KeyboardSound.BuiltIn(it) to it.displayName } +
            options.customSoundPacks.map { (id, name) -> KeyboardSound.Custom(id) to name }
        val names = sounds.toMap()
        TuneChoice(
            label = null,
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
internal fun ThemeChoice(themes: List<KeyboardThemeDefinition>, selectedId: String, onSelect: (String) -> Unit) {
    FlowRow(
        Modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        themes.forEach { theme ->
            // The check leads when selected, as on every other chip; the swatch always trails.
            TuneChoiceChip(
                selected = theme.id == selectedId,
                onClick = { onSelect(theme.id) },
                label = theme.name,
                trailingIcon = { ThemeSwatch(theme) },
            )
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

    private var lockLeds: LockLeds
        get() = LockLeds(isCapsLockActive, isNumLockActive, isScrollLockActive)
        set(value) {
            isCapsLockActive = value.capsLock
            isNumLockActive = value.numLock
            isScrollLockActive = value.scrollLock
        }

    /** A lock key went down on the phone: the lights toggle here unless the host drives them. */
    internal fun onLockKeyPressed(keyCode: Int, isConnected: Boolean) {
        val followHost = lockLightsFollowHost(isConnected, btManager.hostReportsLockLights.value)
        lockLeds = lockLeds.afterLockKeyPress(keyCode, followHost)
    }

    /** The host's latest lock-light state, shown once the host has reported on this connection. */
    internal fun onHostLockLights(host: LockLeds, followHost: Boolean) {
        lockLeds = lockLeds.withHostReport(host, followHost)
    }

    /** A tap on a toolbar lock light: connected, it sends the lock key; the light follows [onLockKeyPressed]. */
    fun toggleLock(keyCode: Int, isConnected: Boolean) {
        if (isConnected) {
            btManager.sendKey(keyCode, true)
            btManager.sendKey(keyCode, false)
        }
        onLockKeyPressed(keyCode, isConnected)
    }

    /** Modifiers (and Fn) an accessibility click holds down until the next other key's click. */
    val latchedKeys = mutableStateListOf<Int>()

    /**
     * An accessibility click on a key (TalkBack's double-tap), which can't hold one key while
     * tapping another. A modifier or Fn latches down on one click and lets go on the next; any
     * other key is pressed and released, then lets go of every latched modifier, so Shift then Q
     * types a capital Q. Each step goes through [onKeyPress], as touch does.
     */
    fun onKeyClick(keyCode: Int, haptics: SurfaceHaptics, isConnected: Boolean) {
        if (keyCode in LATCHING_KEYS) {
            if (latchedKeys.remove(keyCode)) {
                onKeyPress(keyCode, false, haptics, isConnected)
            } else {
                latchedKeys.add(keyCode)
                onKeyPress(keyCode, true, haptics, isConnected)
            }
            return
        }
        onKeyPress(keyCode, true, haptics, isConnected)
        onKeyPress(keyCode, false, haptics, isConnected)
        latchedKeys.toList().forEach { latched ->
            latchedKeys.remove(latched)
            onKeyPress(latched, false, haptics, isConnected)
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
                onLockKeyPressed(keyCode, isConnected)
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
    val isCapsLockActive = rememberSaveable { mutableStateOf(false) }
    val isNumLockActive = rememberSaveable { mutableStateOf(true) }
    val isScrollLockActive = rememberSaveable { mutableStateOf(false) }
    val state = remember {
        KeyboardSurfaceState(
            btManager, soundSynth, sharedPrefs,
            tune, selectedKeyboardTheme, soundOn, keySensitivity,
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

    val hostReportsLockLights by btManager.hostReportsLockLights.collectAsState()
    LaunchedEffect(isConnected, hostReportsLockLights, homeUiState.capsLock, homeUiState.numLock, homeUiState.scrollLock) {
        state.onHostLockLights(
            LockLeds(homeUiState.capsLock, homeUiState.numLock, homeUiState.scrollLock),
            lockLightsFollowHost(isConnected, hostReportsLockLights),
        )
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
                        state.toggleLock(listOf(CAPS_LOCK_KEY, NUM_LOCK_KEY, SCROLL_LOCK_KEY)[index], isConnected)
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

            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 4.dp)
                    // The Tune sheet is modal: while it is open, TalkBack finds only the sheet and the toolbar.
                    .then(if (tuneOpen) Modifier.clearAndSetSemantics {} else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                // A board narrower than it is tall (a split-screen window) would shrink every key under a
                // fingertip, so it asks for a landscape window instead; the toolbar above still works.
                if (maxWidth < maxHeight) {
                    TurnSidewaysState(
                        title = "Turn your phone sideways",
                        body = "The keyboard needs a landscape window. In split screen, make Bluke's window wider than it is tall.",
                        modifier = Modifier.testTag("keyboard_turn_sideways"),
                    )
                    return@BoxWithConstraints
                }
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
                    latchedKeys = state.latchedKeys,
                    onKeyClick = { code -> state.onKeyClick(code, haptics, isConnected) },
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
                onChange = { state.tune.writeChange(sharedPrefs, it) },
                options = rememberKeyboardTuneOptions(refresh = tuneOpen),
                onEditThemes = { context.startActivity(Intent(context, KeyboardThemesActivity::class.java)) },
                onManageSounds = { context.startActivity(Intent(context, SoundPacksActivity::class.java)) },
            )
        }
    }
}
