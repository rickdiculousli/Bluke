package dev.arnv.bluke.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Configuration
import android.hardware.SensorManager
import android.os.SystemClock
import android.view.KeyEvent
import android.view.OrientationEventListener
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.arnv.bluke.RemoteVolumeKeyHost
import dev.arnv.bluke.bluetooth.BluetoothKeyboardManager
import dev.arnv.bluke.bluetooth.ConsumerControl
import kotlin.math.abs
import kotlin.math.min

internal enum class MultimediaPosture {
    LANDSCAPE,
    PORTRAIT_HELD,
}

internal fun multimediaPostureForDegrees(
    degrees: Int,
    current: MultimediaPosture,
): MultimediaPosture {
    if (degrees == OrientationEventListener.ORIENTATION_UNKNOWN) return current
    val normalized = ((degrees % 360) + 360) % 360
    fun distanceTo(target: Int): Int {
        val direct = abs(normalized - target)
        return min(direct, 360 - direct)
    }
    val portraitDistance = min(distanceTo(0), distanceTo(180))
    val landscapeDistance = min(distanceTo(90), distanceTo(270))
    return when {
        portraitDistance <= POSTURE_ENTRY_DEGREES -> MultimediaPosture.PORTRAIT_HELD
        landscapeDistance <= POSTURE_ENTRY_DEGREES -> MultimediaPosture.LANDSCAPE
        else -> current
    }
}

internal class MultimediaPostureStabilizer(
    initial: MultimediaPosture,
    private val stabilityMillis: Long = POSTURE_STABILITY_MILLIS,
) {
    var current: MultimediaPosture = initial
        private set
    private var candidate: MultimediaPosture? = null
    private var candidateSinceMillis = 0L

    fun update(degrees: Int, nowMillis: Long): MultimediaPosture {
        val next = multimediaPostureForDegrees(degrees, current)
        if (next == current) {
            candidate = null
            return current
        }
        if (candidate != next) {
            candidate = next
            candidateSinceMillis = nowMillis
            return current
        }
        if (nowMillis - candidateSinceMillis >= stabilityMillis) {
            current = next
            candidate = null
        }
        return current
    }
}

internal fun consumerControlForHardwareVolumeKey(keyCode: Int): ConsumerControl? = when (keyCode) {
    KeyEvent.KEYCODE_VOLUME_UP -> ConsumerControl.VOLUME_UP
    KeyEvent.KEYCODE_VOLUME_DOWN -> ConsumerControl.VOLUME_DOWN
    else -> null
}

/** The multimedia remote's settings: the shared pointer speeds and whether the phone's volume buttons drive the host. */
data class MultimediaTuneState(
    val pointer: PointerTuneState = PointerTuneState(),
    val hardwareVolumeButtons: Boolean = false,
) {
    fun write(prefs: SharedPreferences) {
        pointer.write(prefs)
        prefs.edit { putBoolean(HARDWARE_VOLUME_REMOTE_PREFERENCE, hardwareVolumeButtons) }
    }

    companion object {
        val KEYS = PointerTuneState.KEYS + HARDWARE_VOLUME_REMOTE_PREFERENCE

        fun read(prefs: SharedPreferences) = MultimediaTuneState(
            pointer = PointerTuneState.read(prefs),
            hardwareVolumeButtons = prefs.getBoolean(HARDWARE_VOLUME_REMOTE_PREFERENCE, false),
        )
    }
}

/** The multimedia remote's settings, for its Tune sheet and the Settings screen. */
@Composable
fun MultimediaTuneContent(
    state: MultimediaTuneState,
    onChange: (MultimediaTuneState) -> Unit,
    haptics: SurfaceHaptics? = null,
) {
    TuneSection("Pointer") {
        PointerSpeedTune(state.pointer, { onChange(state.copy(pointer = it)) }, haptics)
    }
    TuneSection("Volume") {
        TuneSwitch(
            label = "Phone volume buttons",
            supporting = "Change the host's volume while the remote is open",
            checked = state.hardwareVolumeButtons,
            onCheckedChange = { onChange(state.copy(hardwareVolumeButtons = it)) },
        )
    }
}

@Composable
internal fun MediaPresentationView(
    btManager: BluetoothKeyboardManager,
    chrome: SurfaceChrome,
    sharedPrefs: SharedPreferences,
    postureOverride: MultimediaPosture? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val volumeKeyHost = remember(context) { context.findRemoteVolumeKeyHost() }
    val haptics = rememberSurfaceHaptics(sharedPrefs)
    val tune by rememberPreference(sharedPrefs, *MultimediaTuneState.KEYS) { MultimediaTuneState.read(it) }
    var tuneOpen by rememberSaveable { mutableStateOf(false) }
    var isForeground by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    var posture by rememberSaveable { mutableStateOf(MultimediaPosture.LANDSCAPE) }

    DisposableEffect(context, postureOverride) {
        if (postureOverride != null) {
            posture = postureOverride
            onDispose {}
        } else {
            val stabilizer = MultimediaPostureStabilizer(posture)
            val listener = object : OrientationEventListener(context, SensorManager.SENSOR_DELAY_UI) {
                override fun onOrientationChanged(orientation: Int) {
                    posture = stabilizer.update(orientation, SystemClock.elapsedRealtime())
                }
            }
            if (listener.canDetectOrientation()) listener.enable()
            onDispose { listener.disable() }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> isForeground = true
                Lifecycle.Event.ON_STOP -> isForeground = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val useHardwareVolumeButtons = tune.hardwareVolumeButtons
    DisposableEffect(volumeKeyHost, useHardwareVolumeButtons, isForeground, btManager) {
        if (useHardwareVolumeButtons && isForeground) {
            volumeKeyHost?.setRemoteVolumeKeyHandler { keyCode, isPressed ->
                consumerControlForHardwareVolumeKey(keyCode)?.let { control ->
                    btManager.sendConsumerControl(if (isPressed) control else null)
                }
            }
        }
        onDispose {
            volumeKeyHost?.setRemoteVolumeKeyHandler(null)
            btManager.sendConsumerControl(null)
        }
    }

    val remote = remember(btManager, haptics, sharedPrefs) { RemoteSender(btManager, haptics, sharedPrefs) }
    val upright = (postureOverride ?: posture) == MultimediaPosture.PORTRAIT_HELD

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .testTag("media_presentation_view_root"),
    ) {
        // Upright, the phone is held portrait while the activity stays landscape: the whole remote is
        // laid out portrait and turned to match, so the toolbar sits at the top and every label reads level.
        // Inside the turned frame the screen is portrait, so the toolbar and sheet size themselves for it.
        val configuration = LocalConfiguration.current
        val frameConfiguration = remember(configuration, upright) {
            if (upright) configuration.heldUpright() else configuration
        }
        CompositionLocalProvider(LocalConfiguration provides frameConfiguration) {
        Box(Modifier.fillMaxSize().then(if (upright) Modifier.uprightFrame() else Modifier)) {
            Column(Modifier.fillMaxSize()) {
                SurfaceToolbar(chrome = chrome, haptics = haptics, onTune = { tuneOpen = true })
                val deck = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp).testTag("multimedia_deck")
                if (upright) {
                    UprightRemote(remote, tune, deck)
                } else {
                    LandscapeRemote(remote, tune, deck)
                }
            }
            SurfaceTuneSheet(visible = tuneOpen, title = "Multimedia", onDismiss = { tuneOpen = false }) {
                MultimediaTuneContent(tune, { it.write(sharedPrefs) }, haptics)
            }
        }
        }
    }
}

/** This configuration as the phone held upright sees it: width and height swapped, portrait. */
private fun Configuration.heldUpright(): Configuration = Configuration(this).also {
    it.screenWidthDp = screenHeightDp
    it.screenHeightDp = screenWidthDp
    it.orientation = Configuration.ORIENTATION_PORTRAIT
}

/** Lays its content out with width and height swapped and turns it a quarter left, so it reads level held upright. */
private fun Modifier.uprightFrame(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(
        Constraints(
            minWidth = constraints.minHeight,
            maxWidth = constraints.maxHeight,
            minHeight = constraints.minWidth,
            maxHeight = constraints.maxWidth,
        ),
    )
    layout(placeable.height, placeable.width) {
        placeable.placeWithLayer(
            x = (placeable.height - placeable.width) / 2,
            y = (placeable.width - placeable.height) / 2,
        ) { rotationZ = -90f }
    }
}

/**
 * Held flat: playback and volume beside the navigation pad, presentation keys under both, and the
 * pointer on the right. The pointer keeps enough width for its three labelled mouse buttons.
 */
@Composable
private fun LandscapeRemote(remote: RemoteSender, tune: MultimediaTuneState, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(GROUP_GAP)) {
        Column(Modifier.weight(1f - POINTER_SHARE).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(GROUP_GAP)) {
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(GROUP_GAP)) {
                Column(Modifier.weight(0.9f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(GROUP_GAP)) {
                    PlaybackGroup(remote, Modifier.weight(1f), fillHeight = true)
                    VolumeGroup(remote, tune.hardwareVolumeButtons)
                }
                NavigationGroup(remote, Modifier.weight(1f).fillMaxHeight())
            }
            RemoteGroup("Presentation") {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(BUTTON_GAP)) {
                    presentationActions.forEach { action ->
                        val share = if (action.caption == null) 0.6f else 1f
                        RemoteActionButton(action, remote, neutralColors(), Modifier.weight(share).fillMaxHeight())
                    }
                }
            }
        }
        Column(Modifier.weight(POINTER_SHARE).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(BUTTON_GAP)) {
            PointerPad(remote, tune, Modifier.weight(1f).fillMaxWidth())
            ButtonRow(mouseActions, remote)
            ButtonRow(pageActions, remote)
        }
    }
}

/** Held upright: transport and volume across the top, navigation beside the presentation keys, the pointer below. */
@Composable
private fun UprightRemote(remote: RemoteSender, tune: MultimediaTuneState, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(GROUP_GAP)) {
        PlaybackGroup(remote)
        VolumeGroup(remote, tune.hardwareVolumeButtons)
        NavigationBesidePresentation(
            navigation = { NavigationGroup(remote, Modifier.fillMaxSize()) },
            presentation = {
                RemoteGroup("Presentation") {
                    presentationActions.forEach {
                        RemoteActionButton(it, remote, neutralColors(), Modifier.fillMaxWidth())
                    }
                }
            },
        )
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(BUTTON_GAP)) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(BUTTON_GAP)) {
                PointerPad(remote, tune, Modifier.weight(1f).fillMaxWidth())
                ButtonRow(mouseActions, remote)
            }
            Column(Modifier.width(UPRIGHT_PAGE_WIDTH).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(BUTTON_GAP)) {
                pageActions.forEach { RemoteActionButton(it, remote, neutralColors(), Modifier.fillMaxWidth().weight(1f)) }
            }
        }
    }
}

/**
 * The navigation pad at [UPRIGHT_DPAD_WIDTH] with the presentation keys beside it, the pair as tall as
 * the keys' labels need (and never shorter than the pad's own minimum). The keys are measured first,
 * at their real width, because a wrapping label's intrinsic height can come out short.
 */
@Composable
private fun NavigationBesidePresentation(navigation: @Composable () -> Unit, presentation: @Composable () -> Unit) {
    Layout(contents = listOf(navigation, presentation), modifier = Modifier.fillMaxWidth()) { (nav, keys), constraints ->
        val navWidth = UPRIGHT_DPAD_WIDTH.roundToPx()
        val keysWidth = constraints.maxWidth - navWidth - GROUP_GAP.roundToPx()
        val keysPlaceable = keys.single().measure(Constraints(minWidth = keysWidth, maxWidth = keysWidth))
        val height = maxOf(keysPlaceable.height, nav.single().minIntrinsicHeight(navWidth))
        val navPlaceable = nav.single().measure(Constraints.fixed(navWidth, height))
        layout(constraints.maxWidth, height) {
            navPlaceable.placeRelative(0, 0)
            keysPlaceable.placeRelative(constraints.maxWidth - keysWidth, 0)
        }
    }
}

/** Transport first: play/pause is the largest key on the remote, previous and next beside it. [fillHeight] stretches it over what its column leaves. */
@Composable
private fun PlaybackGroup(remote: RemoteSender, modifier: Modifier = Modifier, fillHeight: Boolean = false) {
    RemoteGroup("Playback", modifier) {
        Row(
            Modifier.fillMaxWidth().then(if (fillHeight) Modifier.weight(1f) else Modifier.height(TRANSPORT_HEIGHT)),
            horizontalArrangement = Arrangement.spacedBy(BUTTON_GAP),
        ) {
            RemoteActionButton(previousAction, remote, secondaryColors(), Modifier.width(56.dp).fillMaxHeight())
            RemoteActionButton(
                playPauseAction,
                remote,
                RemoteButtonColors(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary),
                Modifier.weight(1f).fillMaxHeight().testTag("multimedia_play_pause"),
                shape = MaterialTheme.shapes.large,
                iconSize = 32.dp,
            )
            RemoteActionButton(nextAction, remote, secondaryColors(), Modifier.width(56.dp).fillMaxHeight())
        }
    }
}

@Composable
private fun VolumeGroup(remote: RemoteSender, hardwareVolumeButtons: Boolean) {
    RemoteGroup(if (hardwareVolumeButtons) "Volume · phone buttons on" else "Volume") {
        ButtonRow(volumeActions, remote, secondaryColors())
    }
}

/** Arrows around OK, with Esc, Menu, Home and End in the corners; the arrow column is the narrow one. */
@Composable
private fun NavigationGroup(remote: RemoteSender, modifier: Modifier) {
    RemoteGroup("Navigation", modifier) {
        Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(BUTTON_GAP)) {
            navigationGrid.forEach { row ->
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(BUTTON_GAP)) {
                    row.forEachIndexed { column, action ->
                        val colors = if (action.icons.isNotEmpty() || action == okAction) secondaryColors() else neutralColors()
                        val share = if (column == 1) ARROW_COLUMN_SHARE else 1f
                        RemoteActionButton(action, remote, colors, Modifier.weight(share).fillMaxHeight())
                    }
                }
            }
        }
    }
}

/** A row of equal buttons, as tall as its tallest label needs and never under 48dp. */
@Composable
private fun ButtonRow(actions: List<RemoteAction>, remote: RemoteSender, colors: RemoteButtonColors = neutralColors()) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(BUTTON_GAP)) {
        actions.forEach { RemoteActionButton(it, remote, colors, Modifier.weight(1f).fillMaxHeight()) }
    }
}

/** The pointer pad: it moves the pointer and scrolls with the touchpad's scroll feel at this remote's speed. */
@Composable
private fun PointerPad(remote: RemoteSender, tune: MultimediaTuneState, modifier: Modifier) {
    Box(
        modifier
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .testTag("multimedia_touchpad"),
    ) {
        val scrollFeel by rememberPreference(remote.prefs, *TouchpadScrollSettings.KEYS) { TouchpadScrollSettings.from(it) }
        TouchGestureLayer(
            btManager = remote.btManager,
            sensitivity = tune.pointer.pointerSpeed,
            scrollSettings = scrollFeel.copy(speed = tune.pointer.scrollSpeed),
            buttonMode = TrackpadButtonMode.CLICKPAD,
            showNumpadLed = false,
            onClick = { remote.haptics.perform(HapticEvent.CLICK) },
        )
    }
}

/** A quiet group label over its buttons. */
@Composable
private fun RemoteGroup(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { heading() },
        )
        content()
    }
}

@Composable
private fun RemoteActionButton(
    action: RemoteAction,
    remote: RemoteSender,
    colors: RemoteButtonColors,
    modifier: Modifier,
    shape: androidx.compose.ui.graphics.Shape = MaterialTheme.shapes.medium,
    iconSize: Dp = 24.dp,
) {
    RemoteHoldButton(
        description = action.description,
        colors = colors,
        shape = shape,
        modifier = modifier,
        icons = action.icons,
        iconSize = iconSize,
        text = action.text,
        caption = action.caption,
        onPress = remote::press,
        onPressedChange = { pressed -> remote.send(action, pressed) },
    )
}

@Composable
private fun secondaryColors() =
    RemoteButtonColors(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)

@Composable
private fun neutralColors() =
    RemoteButtonColors(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurface)

/** Sends what a remote button means and plays its touch-down haptic. */
private class RemoteSender(val btManager: BluetoothKeyboardManager, val haptics: SurfaceHaptics, val prefs: SharedPreferences) {
    fun press() = haptics.perform(HapticEvent.PRESS)

    fun send(action: RemoteAction, pressed: Boolean) {
        when (val output = action.output) {
            is RemoteOutput.Consumer -> btManager.sendConsumerControl(if (pressed) output.control else null)
            is RemoteOutput.Key -> btManager.sendKey(output.keyCode, pressed)
            is RemoteOutput.Mouse -> btManager.sendMouseReport(if (pressed) output.mask.toByte() else 0, 0, 0, 0)
        }
    }
}

private sealed interface RemoteOutput {
    data class Consumer(val control: ConsumerControl) : RemoteOutput
    data class Key(val keyCode: Int) : RemoteOutput
    data class Mouse(val mask: Int) : RemoteOutput
}

/** One remote button: what TalkBack calls it, what it shows, and what it sends. */
private data class RemoteAction(
    val description: String,
    val output: RemoteOutput,
    val icons: List<ImageVector> = emptyList(),
    val text: String? = null,
    val caption: String? = null,
)

private fun consumer(description: String, icon: ImageVector, control: ConsumerControl) =
    RemoteAction(description, RemoteOutput.Consumer(control), icons = listOf(icon))

private val previousAction = consumer("Previous track", Icons.Default.SkipPrevious, ConsumerControl.PREVIOUS_TRACK)
private val playPauseAction = RemoteAction(
    "Play or pause",
    RemoteOutput.Consumer(ConsumerControl.PLAY_PAUSE),
    icons = listOf(Icons.Default.PlayArrow, Icons.Default.Pause),
)
private val nextAction = consumer("Next track", Icons.Default.SkipNext, ConsumerControl.NEXT_TRACK)

private val volumeActions = listOf(
    consumer("Volume down", Icons.AutoMirrored.Filled.VolumeDown, ConsumerControl.VOLUME_DOWN),
    consumer("Mute", Icons.AutoMirrored.Filled.VolumeOff, ConsumerControl.MUTE),
    consumer("Volume up", Icons.AutoMirrored.Filled.VolumeUp, ConsumerControl.VOLUME_UP),
)

private fun key(description: String, keyCode: Int, text: String? = null, icon: ImageVector? = null, caption: String? = null) =
    RemoteAction(description, RemoteOutput.Key(keyCode), icons = listOfNotNull(icon), text = text, caption = caption)

private val okAction = key("OK (Enter)", KeyboardLayouts.KEY_ENTER, text = "OK")

private val navigationGrid = listOf(
    listOf(
        key("Escape", KeyboardLayouts.KEY_ESC, text = "Esc"),
        key("Up", KeyboardLayouts.KEY_UP, icon = Icons.Default.KeyboardArrowUp),
        key("Menu (application key)", KEY_APPLICATION, text = "Menu"),
    ),
    listOf(
        key("Left", KeyboardLayouts.KEY_LEFT, icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft),
        okAction,
        key("Right", KeyboardLayouts.KEY_RIGHT, icon = Icons.AutoMirrored.Filled.KeyboardArrowRight),
    ),
    listOf(
        key("Home", KeyboardLayouts.KEY_HOME, text = "Home"),
        key("Down", KeyboardLayouts.KEY_DOWN, icon = Icons.Default.KeyboardArrowDown),
        key("End", KeyboardLayouts.KEY_END, text = "End"),
    ),
)

private val presentationActions = listOf(
    key("Start slideshow (F5)", KeyboardLayouts.KEY_F5, text = "Start show", caption = "F5"),
    key("Blank the presentation screen (B)", KeyboardLayouts.KEY_B, text = "Blank screen", caption = "B"),
    key("Back (Backspace)", KeyboardLayouts.KEY_BACKSPACE, text = "Back"),
)

private val pageActions = listOf(
    key("Page up, previous slide", KeyboardLayouts.KEY_PAGEUP, text = "Page up"),
    key("Page down, next slide", KeyboardLayouts.KEY_PAGEDOWN, text = "Page down"),
)

private val mouseActions = listOf(
    RemoteAction("Left click", RemoteOutput.Mouse(0x01), text = "Left"),
    RemoteAction("Middle click", RemoteOutput.Mouse(0x04), text = "Middle"),
    RemoteAction("Right click", RemoteOutput.Mouse(0x02), text = "Right"),
)

private val GROUP_GAP = 12.dp
private val BUTTON_GAP = 8.dp
private val TRANSPORT_HEIGHT = 64.dp
private val UPRIGHT_DPAD_WIDTH = 186.dp
private val UPRIGHT_PAGE_WIDTH = 64.dp
private const val ARROW_COLUMN_SHARE = 0.8f
/** The pointer's share of a landscape remote's width: enough for "Left", "Middle" and "Right" at large font sizes. */
private const val POINTER_SHARE = 0.37f

private const val KEY_APPLICATION = 0x65
private const val POSTURE_ENTRY_DEGREES = 20
internal const val POSTURE_STABILITY_MILLIS = 500L

private tailrec fun Context.findRemoteVolumeKeyHost(): RemoteVolumeKeyHost? = when (this) {
    is RemoteVolumeKeyHost -> this
    is ContextWrapper -> if (baseContext === this) null else baseContext.findRemoteVolumeKeyHost()
    else -> null
}
