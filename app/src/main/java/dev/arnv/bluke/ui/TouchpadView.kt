package dev.arnv.bluke.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.ViewSidebar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import dev.arnv.bluke.bluetooth.BluetoothKeyboardManager
import dev.arnv.bluke.data.KeyboardThemeRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** How the pad's bottom edge clicks. [preferenceValue] is stored in [TOUCHPAD_BUTTON_MODE_PREFERENCE]. */
enum class TrackpadButtonMode(val preferenceValue: String, val displayName: String) {
    CLICKPAD("clickpad", "Clickpad"),
    TWO_BUTTONS("two_buttons", "2-button"),
    THREE_BUTTONS("three_buttons", "3-button"),
    ;

    companion object {
        /** An unknown or missing value reads as [CLICKPAD]. */
        fun fromPreference(value: String?): TrackpadButtonMode =
            entries.firstOrNull { it.preferenceValue == value } ?: CLICKPAD
    }
}

/**
 * The side the modifier rail last stood on ("left" or "right"), so the toolbar's on/off toggle turns it
 * back on where it was. [TOUCHPAD_MODIFIER_POSITION_PREFERENCE] still holds off/left/right.
 */
const val TOUCHPAD_MODIFIER_LAST_SIDE_PREFERENCE = "touchpad_modifier_last_side"

/** The side the rail returns to when switched on: the last side it stood on, left before any. */
fun lastModifierRailSide(prefs: SharedPreferences): TouchpadModifierPosition =
    TouchpadModifierPosition.fromPreference(prefs.getString(TOUCHPAD_MODIFIER_LAST_SIDE_PREFERENCE, null))
        .takeIf { it != TouchpadModifierPosition.OFF } ?: TouchpadModifierPosition.LEFT

/** Where a mouse report goes. The app sends it to the manager; tests provide a recorder through [LocalMouseReportSink]. */
fun interface MouseReportSink {
    fun send(buttons: Byte, x: Byte, y: Byte, wheel: Byte, pan: Byte)
}

/** Replaces the manager as the touchpad's report sink; tests set it to read what a gesture sends. */
val LocalMouseReportSink = compositionLocalOf<MouseReportSink?> { null }

/**
 * The touchpad's own settings: pointer and scroll speed, scroll direction and momentum, and the
 * modifier rail. Settings hosts [TouchpadTuneContent] with the same state, so the keys are its contract.
 */
data class TouchpadTuneState(
    val speeds: PointerTuneState = PointerTuneState(),
    val invertScroll: Boolean = TouchpadScrollSettings().inverted,
    val momentum: Boolean = TouchpadScrollSettings().momentum,
    val glide: Float = TouchpadScrollSettings().glide,
    val modifierPosition: TouchpadModifierPosition = TouchpadModifierPosition.OFF,
) {
    fun write(prefs: SharedPreferences) {
        speeds.write(prefs)
        prefs.edit {
            putBoolean(TOUCHPAD_INVERT_SCROLL_PREFERENCE, invertScroll)
            putBoolean(TOUCHPAD_SCROLL_MOMENTUM_PREFERENCE, momentum)
            putFloat(TOUCHPAD_SCROLL_GLIDE_PREFERENCE, glide.coerceIn(0f, 1f))
            putString(TOUCHPAD_MODIFIER_POSITION_PREFERENCE, modifierPosition.preferenceValue)
            if (modifierPosition != TouchpadModifierPosition.OFF) {
                putString(TOUCHPAD_MODIFIER_LAST_SIDE_PREFERENCE, modifierPosition.preferenceValue)
            }
        }
    }

    companion object {
        val KEYS = PointerTuneState.KEYS + arrayOf(
            TOUCHPAD_INVERT_SCROLL_PREFERENCE,
            TOUCHPAD_SCROLL_MOMENTUM_PREFERENCE,
            TOUCHPAD_SCROLL_GLIDE_PREFERENCE,
            TOUCHPAD_MODIFIER_POSITION_PREFERENCE,
        )

        fun read(prefs: SharedPreferences): TouchpadTuneState {
            val scroll = TouchpadScrollSettings.from(prefs)
            return TouchpadTuneState(
                speeds = PointerTuneState.read(prefs),
                invertScroll = scroll.inverted,
                momentum = scroll.momentum,
                glide = scroll.glide,
                modifierPosition = TouchpadModifierPosition.fromPreference(
                    prefs.getString(TOUCHPAD_MODIFIER_POSITION_PREFERENCE, null),
                ),
            )
        }
    }
}


/** Glide's word for [glide] (0..1): its slider's value and the Settings header's. */
internal fun glideLabel(glide: Float): String = when {
    glide < 0.34f -> "Short"
    glide < 0.67f -> "Medium"
    else -> "Long"
}

/** The touchpad's settings, for its Tune sheet and the Settings screen. Glide shows only while momentum is on. */
@Composable
fun TouchpadTuneContent(
    state: TouchpadTuneState,
    onChange: (TouchpadTuneState) -> Unit,
    haptics: SurfaceHaptics? = null,
) {
    TuneSection("Speed") {
        PointerSpeedTune(state.speeds, { onChange(state.copy(speeds = it)) }, haptics)
    }
    TuneSection("Scrolling") {
        TuneSwitch(
            label = "Invert scroll",
            checked = state.invertScroll,
            onCheckedChange = { onChange(state.copy(invertScroll = it)) },
            supporting = "Content follows your fingers, like a phone",
        )
        TuneSwitch(
            label = "Momentum",
            checked = state.momentum,
            onCheckedChange = { onChange(state.copy(momentum = it)) },
            supporting = "Keep scrolling after a flick",
        )
        AnimatedVisibility(
            visible = state.momentum,
            enter = expandVertically(spring(stiffness = Spring.StiffnessMedium)) + fadeIn(),
            exit = shrinkVertically(spring(stiffness = Spring.StiffnessMedium)) + fadeOut(),
        ) {
            TuneSlider(
                label = "Glide",
                value = state.glide,
                valueRange = 0f..1f,
                steps = 0,
                valueLabel = glideLabel(state.glide),
                onValueChange = { glide ->
                    if (glideLabel(glide) != glideLabel(state.glide)) haptics?.perform(HapticEvent.TICK)
                    onChange(state.copy(glide = glide))
                },
            )
        }
    }
    TuneSection("Modifier key strip", supporting = "Ctrl, Shift, Alt and the Win or Cmd key, beside the pad") {
        TuneChoice(
            label = null,
            options = TouchpadModifierPosition.entries,
            selected = state.modifierPosition,
            optionLabel = ::railLabel,
            onSelect = { onChange(state.copy(modifierPosition = it)) },
        )
    }
}

private fun railLabel(position: TouchpadModifierPosition): String = when (position) {
    TouchpadModifierPosition.OFF -> "Off"
    TouchpadModifierPosition.LEFT -> "Left"
    TouchpadModifierPosition.RIGHT -> "Right"
}

@SuppressLint("MissingPermission")
@Composable
fun TouchpadView(
    btManager: BluetoothKeyboardManager,
    chrome: SurfaceChrome,
    sharedPrefs: SharedPreferences,
    caseBrush: Brush,
    modifierStyle: KeyboardModifierStyle = KeyboardModifierStyle.PC,
) {
    val context = LocalContext.current
    val haptics = rememberSurfaceHaptics(sharedPrefs)

    val buttonMode by rememberPreference(sharedPrefs, TOUCHPAD_BUTTON_MODE_PREFERENCE) {
        TrackpadButtonMode.fromPreference(it.getString(TOUCHPAD_BUTTON_MODE_PREFERENCE, null))
    }
    val numpad by rememberPreference(sharedPrefs, TOUCHPAD_NUMPAD_PREFERENCE) {
        it.getBoolean(TOUCHPAD_NUMPAD_PREFERENCE, false)
    }
    val tune by rememberPreference(sharedPrefs, *TouchpadTuneState.KEYS) { TouchpadTuneState.read(it) }
    val scrollSettings by rememberPreference(sharedPrefs, *TouchpadScrollSettings.KEYS) { TouchpadScrollSettings.from(it) }
    var tuneOpen by rememberSaveable { mutableStateOf(false) }
    val keyboardTheme = remember(context) { KeyboardThemeRepository(context).selectedTheme() }

    fun setNumpad(on: Boolean) = sharedPrefs.edit { putBoolean(TOUCHPAD_NUMPAD_PREFERENCE, on) }
    fun setButtonMode(mode: TrackpadButtonMode) =
        sharedPrefs.edit { putString(TOUCHPAD_BUTTON_MODE_PREFERENCE, mode.preferenceValue) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(caseBrush)
            .navigationBarsPadding()
            .testTag("touchpad_view_root"),
    ) {
        Column(Modifier.fillMaxSize()) {
            SurfaceToolbar(chrome = chrome, haptics = haptics, onTune = { tuneOpen = true }) {
                // On/off here; the rail's side is a Tune setting, and on returns to the last side chosen.
                val railOn = tune.modifierPosition != TouchpadModifierPosition.OFF
                SurfaceToolbarToggles(
                    listOf(
                        SurfaceToolbarToggle(
                            label = "Numpad",
                            icon = Icons.Default.Dialpad,
                            on = numpad,
                            onToggle = { setNumpad(!numpad) },
                            testTag = "touchpad_numpad_toggle",
                        ),
                        SurfaceToolbarToggle(
                            label = "Modifiers",
                            // A rail beside the pad: a keyboard glyph here read as "switch to keyboard".
                            icon = Icons.Default.ViewSidebar,
                            on = railOn,
                            onToggle = {
                                val next = if (railOn) TouchpadModifierPosition.OFF else lastModifierRailSide(sharedPrefs)
                                tune.copy(modifierPosition = next).write(sharedPrefs)
                            },
                            stateDescription = if (railOn) "On, ${railLabel(tune.modifierPosition).lowercase()}" else "Off",
                            testTag = "touchpad_modifier_position",
                        ),
                    ),
                )
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // While Tune is open the pad sits under its scrim: TalkBack reaches only the sheet.
                    .then(if (tuneOpen) Modifier.clearAndSetSemantics {} else Modifier)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (tune.modifierPosition == TouchpadModifierPosition.LEFT) {
                    TouchpadModifierRail(false, modifierStyle, keyboardTheme, btManager, haptics)
                }
                TouchpadPad(
                    btManager = btManager,
                    sensitivity = tune.speeds.pointerSpeed,
                    scrollSettings = scrollSettings,
                    buttonMode = buttonMode,
                    numpad = numpad,
                    haptics = haptics,
                )
                if (tune.modifierPosition == TouchpadModifierPosition.RIGHT) {
                    TouchpadModifierRail(true, modifierStyle, keyboardTheme, btManager, haptics)
                }
            }
        }

        SurfaceTuneSheet(visible = tuneOpen, title = "Touchpad", onDismiss = { tuneOpen = false }) {
            TuneSection("Buttons", supporting = "How the pad's bottom edge clicks") {
                TuneChoice(
                    label = null,
                    options = TrackpadButtonMode.entries,
                    selected = buttonMode,
                    optionLabel = { it.displayName },
                    onSelect = ::setButtonMode,
                )
                TuneSwitch(
                    label = "Numpad",
                    checked = numpad,
                    onCheckedChange = ::setNumpad,
                    supporting = "Number keys drawn on the pad",
                )
            }
            TouchpadTuneContent(state = tune, onChange = { it.write(sharedPrefs) }, haptics = haptics)
        }
    }
}

@Composable
private fun RowScope.TouchpadPad(
    btManager: BluetoothKeyboardManager,
    sensitivity: Float,
    scrollSettings: TouchpadScrollSettings,
    buttonMode: TrackpadButtonMode,
    numpad: Boolean,
    haptics: SurfaceHaptics,
) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.large
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(shape)
            .background(colors.surfaceContainer)
            .border(1.dp, colors.outlineVariant, shape)
            .testTag("glass_touch_surface"),
    ) {
        if (!numpad) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(if (buttonMode == TrackpadButtonMode.CLICKPAD) 1f else 1f - BUTTON_STRIP_FRACTION)
                    // Named like the multimedia remote's pad, its hint read with the name.
                    .semantics(mergeDescendants = true) { contentDescription = "Pointer pad" },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Tap to click · Two fingers to scroll",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp).testTag("touchpad_hint"),
                )
            }
        }

        TouchGestureLayer(
            btManager = btManager,
            sensitivity = sensitivity,
            scrollSettings = scrollSettings,
            buttonMode = buttonMode,
            showNumpadLed = numpad,
            onClick = { haptics.perform(HapticEvent.CLICK) },
        )

        if (numpad) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(if (buttonMode != TrackpadButtonMode.CLICKPAD) 1f - BUTTON_STRIP_FRACTION else 1f)
                    .padding(12.dp),
            ) {
                NumpadLedGrid(onKeyPress = { legend, isPress ->
                    if (isPress) haptics.perform(HapticEvent.PRESS)
                    btManager.sendNumpadKey(legend, isPress)
                })
            }
        }
    }
}

@Composable
private fun TouchpadModifierRail(
    rightHandKeys: Boolean,
    modifierStyle: KeyboardModifierStyle,
    theme: KeyboardThemeDefinition,
    btManager: BluetoothKeyboardManager,
    haptics: SurfaceHaptics,
) {
    // The keyboard's names for the same keys, so Alt and the GUI key read alike on both surfaces.
    val alt = modifierStyle.altLabel
    val gui = modifierStyle.guiLabel
    val keys = if (rightHandKeys) {
        listOf("Ctrl" to 0xE4, "Shift" to 0xE5, alt to 0xE6, gui to 0xE7)
    } else {
        listOf("Ctrl" to 0xE0, "Shift" to 0xE1, alt to 0xE2, gui to 0xE3)
    }
    Column(
        modifier = Modifier
            .width(72.dp)
            .fillMaxHeight()
            .testTag(if (rightHandKeys) "touchpad_rail_right" else "touchpad_rail_left"),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        keys.forEach { (label, keyCode) ->
            MechanicalModifierKey(
                label = label,
                keyCode = keyCode,
                // One unpressed style for all four, so only a held modifier looks held.
                style = theme.modifierStyle,
                btManager = btManager,
                haptics = haptics,
            )
        }
    }
}

@Composable
private fun MechanicalModifierKey(
    label: String,
    keyCode: Int,
    style: KeyboardKeyStyle,
    btManager: BluetoothKeyboardManager,
    haptics: SurfaceHaptics,
) {
    // Touch holds the key while the finger is down; TalkBack, which can't hold, latches it until activated again.
    var isPressed by remember { mutableStateOf(false) }
    var latched by remember { mutableStateOf(false) }
    val held = isPressed || latched
    DisposableEffect(btManager, keyCode) {
        onDispose {
            if (isPressed || latched) btManager.sendKey(keyCode, false)
        }
    }
    KeyCap(
        legend = label,
        shiftedLegend = "",
        width = 68.dp,
        height = 68.dp,
        isPressed = held,
        keyBgColor = Color(style.backgroundArgb),
        legendColor = Color(style.legendArgb),
        legendScale = style.legendScale,
        baseUnitWidth = 68.dp,
        modifier = Modifier
            .semantics {
                contentDescription = spokenModifierName(label)
                role = Role.Switch
                stateDescription = if (held) "Held" else "Released"
                onClick {
                    latched = !latched
                    haptics.perform(HapticEvent.PRESS)
                    btManager.sendKey(keyCode, latched)
                    true
                }
            }
            .testTag("touchpad_rail_key_$label")
            .pointerInput(keyCode) {
                detectTapGestures(
                    onPress = {
                        // A touch on a latched key takes it over: lifting the finger releases it.
                        latched = false
                        isPressed = true
                        haptics.perform(HapticEvent.PRESS)
                        btManager.sendKey(keyCode, true)
                        try {
                            tryAwaitRelease()
                        } finally {
                            btManager.sendKey(keyCode, false)
                            isPressed = false
                        }
                    },
                )
            },
    )
}

/** What TalkBack says for a rail legend: the key's full name, as the keyboard says it. */
private fun spokenModifierName(legend: String): String = when (legend) {
    "Ctrl" -> "Control"
    KeyboardModifierStyle.MAC.altLabel -> "Option"
    KeyboardModifierStyle.MAC.guiLabel -> "Command"
    KeyboardModifierStyle.PC.guiLabel -> "Windows"
    else -> legend
}

/** The numpad drawn on the pad: five columns of keys, lit on touch-down in the same frame. */
@Composable
fun NumpadLedGrid(onKeyPress: (String, Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val cols = listOf(
        listOf("7" to 1f, "4" to 1f, "1" to 1f, "0" to 1f),
        listOf("8" to 1f, "5" to 1f, "2" to 1f, "." to 1f),
        listOf("9" to 1f, "6" to 1f, "3" to 1f, "%" to 1f),
        listOf("/" to 1f, "*" to 1f, "-" to 1f, "+" to 1f),
        listOf("⌫" to 2f, "Enter" to 1f, "=" to 1f),
    )
    Box(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxSize()) {
            cols.forEach { col ->
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    col.forEach { (legend, weight) ->
                        NumpadKey(legend, onKeyPress, Modifier.weight(weight).fillMaxWidth())
                    }
                }
            }
        }
        val lineColor = colors.outlineVariant
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 1.dp.toPx()
            for (i in 1..4) {
                val x = size.width * (i / 5f)
                drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), stroke)
            }
            for (i in 1..3) {
                val y = size.height * (i / 4f)
                // The backspace key spans the first two rows of the last column.
                val endX = if (i == 1) size.width * (4f / 5f) else size.width
                drawLine(lineColor, Offset(0f, y), Offset(endX, y), stroke)
            }
        }
    }
}

@Composable
private fun NumpadKey(legend: String, onKeyPress: (String, Boolean) -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    var isPressed by remember { mutableStateOf(false) }
    val press by rememberUpdatedState(onKeyPress)
    Box(
        modifier = modifier
            .background(if (isPressed) colors.secondaryContainer else Color.Transparent)
            .semantics {
                contentDescription = if (legend == "⌫") "Backspace" else legend
                selected = isPressed
            }
            .testTag("numpad_key_$legend")
            .pointerInput(legend) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        press(legend, true)
                        try {
                            tryAwaitRelease()
                        } finally {
                            isPressed = false
                            press(legend, false)
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = legend,
            style = if (legend == "Enter") MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleLarge,
            color = if (isPressed) colors.onSecondaryContainer else colors.primary,
        )
    }
}

private const val SCROLL_FRAME_MILLIS = 8L

/** The share of the pad's height the 2- and 3-button strip takes. */
private const val BUTTON_STRIP_FRACTION = 0.18f

/** Pointer counts per dp of finger travel at 1x: the feel Bluke had on a 420 dpi phone, now the same on every screen. */
private const val POINTER_COUNTS_PER_DP = 2.625f

private const val LEFT_BUTTON: Byte = 1
private const val RIGHT_BUTTON: Byte = 2
private const val MIDDLE_BUTTON: Byte = 4

/**
 * Turns touches on the pad into mouse reports: one finger points, two scroll, a tap clicks, and in
 * 2- and 3-button modes the bottom strip is the buttons. [onClick] runs when a tap or button click
 * lands, for the caller's haptic; nothing runs on moves or scrolls. Speed and scroll settings apply
 * mid-gesture, without restarting the touch handling.
 */
@Composable
fun TouchGestureLayer(
    btManager: BluetoothKeyboardManager,
    sensitivity: Float,
    scrollSettings: TouchpadScrollSettings,
    buttonMode: TrackpadButtonMode,
    showNumpadLed: Boolean,
    onClick: () -> Unit,
) {
    val sink = LocalMouseReportSink.current
    val sendMouse: (Byte, Byte, Byte, Byte, Byte) -> Unit = remember(sink, btManager) {
        if (sink != null) sink::send else { b, x, y, w, p -> btManager.sendMouseReport(b, x, y, w, p) }
    }
    fun sendMouseReport(buttons: Byte, x: Byte, y: Byte, wheel: Byte, pan: Byte = 0) = sendMouse(buttons, x, y, wheel, pan)

    val currentSensitivity by rememberUpdatedState(sensitivity)
    val currentOnClick by rememberUpdatedState(onClick)

    var lastActivePointerId by remember { mutableStateOf<PointerId?>(null) }
    var accumulatedX by remember { mutableFloatStateOf(0f) }
    var accumulatedY by remember { mutableFloatStateOf(0f) }
    var lastPointX by remember { mutableFloatStateOf(0f) }
    var lastPointY by remember { mutableFloatStateOf(0f) }

    var isTwoFingerActive by remember { mutableStateOf(false) }
    // Where the scroll has travelled: it follows whichever finger moves more, as a Mac trackpad does.
    var scrollTrackX by remember { mutableFloatStateOf(0f) }
    var scrollTrackY by remember { mutableFloatStateOf(0f) }
    val scroller = remember { TouchpadScroller() }
    var scrollTicker by remember { mutableStateOf<Job?>(null) }
    SideEffect { scroller.settings = scrollSettings }

    val pointerDownInfo = remember { mutableMapOf<PointerId, Pair<Long, Offset>>() }
    var maxPointersInTap by remember { mutableIntStateOf(0) }
    val gestureScope = rememberCoroutineScope()
    val tapDrag = remember(btManager, sendMouse, buttonMode, showNumpadLed) {
        TouchpadTapDragGesture(
            gestureScope,
            sendButton = { sendMouseReport(it, 0, 0, 0) },
            onClick = { currentOnClick() },
        )
    }

    // Rate limiting to prevent Bluetooth L2CAP packet flooding
    var lastReportTime by remember { mutableLongStateOf(0L) }

    // Where the fingers are, drawn as markers so the pad shows it is tracking them.
    var activeTouchPoints by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var activeMouseButton by remember { mutableStateOf<Byte>(0) }

    fun sendScroll(report: ScrollReport?) {
        report ?: return
        sendMouseReport(activeMouseButton, 0, 0, report.wheel.toByte(), report.pan.toByte())
    }

    // A momentum glide plays out on a steady frame clock after the fingers lift.
    fun runScroll() {
        if (scrollTicker?.isActive == true) return
        scrollTicker = gestureScope.launch {
            while (isActive && scroller.isActive) {
                sendScroll(scroller.tick(android.os.SystemClock.uptimeMillis()))
                delay(SCROLL_FRAME_MILLIS)
            }
        }
    }

    DisposableEffect(btManager, tapDrag) {
        onDispose {
            scroller.stop()
            scrollTicker?.cancel()
            tapDrag.cancel()
            // Pointer cancellation (mode change, rotation, backgrounding) must never strand a host button down.
            sendMouseReport(0, 0, 0, 0)
        }
    }

    val density = LocalDensity.current
    val pxPerDp = density.density

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("touchpad_gesture_layer")
            .pointerInput(buttonMode, showNumpadLed, sendMouse) {
                val tapSlopPx = TouchpadGesturePolicy.TAP_SLOP_DP.dp.toPx()
                try {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val changes = event.changes

                        if (showNumpadLed) {
                            activeTouchPoints = emptyList()
                            continue
                        }

                        val pressedChanges = changes.filter { it.pressed }
                        val downCount = pressedChanges.size
                        activeTouchPoints = pressedChanges.map { it.position }
                        if (downCount > maxPointersInTap) {
                            maxPointersInTap = downCount
                        }

                        // 1. Process Down Events for Taps
                        changes.forEach { change ->
                            if (change.pressed && !change.previousPressed) {
                                if (pointerDownInfo.isEmpty()) {
                                    scroller.stop()
                                    btManager.holdLinkAwake() // the link is ready by the time the finger moves
                                }
                                pointerDownInfo[change.id] = change.uptimeMillis to change.position

                                if (pointerDownInfo.size == 1) {
                                    tapDrag.down(change.uptimeMillis, change.position.x, change.position.y)
                                } else {
                                    tapDrag.cancel()
                                }
                            }
                        }

                        if (downCount == 1) {
                            val pointer = changes.first { it.pressed }
                            if (isTwoFingerActive) {
                                isTwoFingerActive = false
                                sendScroll(scroller.end(pointer.uptimeMillis))
                            }

                            if (!scroller.allowsPointing(pointer.uptimeMillis)) {
                                // Just after a scroll: the remaining finger rests; it starts pointing from here.
                                lastActivePointerId = null
                            } else if (lastActivePointerId != pointer.id || !pointer.previousPressed) {
                                lastActivePointerId = pointer.id
                                accumulatedX = 0f
                                accumulatedY = 0f
                                lastPointX = pointer.position.x
                                lastPointY = pointer.position.y
                            } else {
                                accumulatedX += (pointer.position.x - lastPointX) / pxPerDp * POINTER_COUNTS_PER_DP * currentSensitivity
                                accumulatedY += (pointer.position.y - lastPointY) / pxPerDp * POINTER_COUNTS_PER_DP * currentSensitivity

                                val now = System.currentTimeMillis()
                                if (now - lastReportTime >= 10L) {
                                    val sendX = accumulatedX.roundToInt().coerceIn(-127, 127)
                                    val sendY = accumulatedY.roundToInt().coerceIn(-127, 127)

                                    if (sendX != 0 || sendY != 0) {
                                        if (tapDrag.move()) {
                                            sendMouseReport(1, sendX.toByte(), sendY.toByte(), 0)
                                        } else {
                                            sendMouseReport(activeMouseButton, sendX.toByte(), sendY.toByte(), 0)
                                        }
                                        accumulatedX -= sendX
                                        accumulatedY -= sendY
                                        lastReportTime = now
                                    }
                                }

                                lastPointX = pointer.position.x
                                lastPointY = pointer.position.y
                            }

                            if (!pointer.previousPressed) {
                                val touchYVal = pointer.position.y
                                val touchXVal = pointer.position.x
                                val height = size.height
                                val width = size.width

                                if (touchYVal > height * (1f - BUTTON_STRIP_FRACTION) && !tapDrag.isArmed) {
                                    currentOnClick()
                                    val btnMask = when (buttonMode) {
                                        TrackpadButtonMode.TWO_BUTTONS ->
                                            if (touchXVal < width / 2f) LEFT_BUTTON else RIGHT_BUTTON
                                        TrackpadButtonMode.THREE_BUTTONS -> when {
                                            touchXVal < width * 0.35f -> LEFT_BUTTON
                                            touchXVal < width * 0.65f -> MIDDLE_BUTTON
                                            else -> RIGHT_BUTTON
                                        }
                                        TrackpadButtonMode.CLICKPAD -> LEFT_BUTTON
                                    }
                                    activeMouseButton = btnMask
                                    sendMouseReport(btnMask, 0, 0, 0)
                                }
                            }

                        } else if (downCount == 2) {
                            val pressedList = changes.filter { it.pressed }
                            if (pressedList.size == 2) {
                                val time = pressedList[0].uptimeMillis
                                if (!isTwoFingerActive) {
                                    isTwoFingerActive = true
                                    lastActivePointerId = null
                                    accumulatedX = 0f
                                    accumulatedY = 0f
                                    scrollTrackX = 0f
                                    scrollTrackY = 0f
                                    scroller.begin(0f, 0f, time)
                                } else {
                                    val lead = TouchpadGesturePolicy.leadingFingerMove(
                                        pressedList[0].position - pressedList[0].previousPosition,
                                        pressedList[1].position - pressedList[1].previousPosition,
                                    )
                                    scrollTrackX += lead.x / pxPerDp
                                    scrollTrackY += lead.y / pxPerDp
                                    sendScroll(scroller.move(scrollTrackX, scrollTrackY, time))
                                }
                            }
                        } else {
                            val time = changes.firstOrNull()?.uptimeMillis ?: android.os.SystemClock.uptimeMillis()
                            if (isTwoFingerActive) sendScroll(scroller.end(time))
                            if (downCount == 0) sendScroll(scroller.liftAll(time))
                            if (scroller.isActive) runScroll()
                            isTwoFingerActive = false
                            lastActivePointerId = null

                            val sendX = accumulatedX.roundToInt().coerceIn(-127, 127)
                            val sendY = accumulatedY.roundToInt().coerceIn(-127, 127)
                            if (sendX != 0 || sendY != 0) {
                                if (tapDrag.move()) {
                                    sendMouseReport(1, sendX.toByte(), sendY.toByte(), 0)
                                } else {
                                    sendMouseReport(activeMouseButton, sendX.toByte(), sendY.toByte(), 0)
                                }
                            }
                            accumulatedX = 0f
                            accumulatedY = 0f
                        }

                        // 3. Process Up Events for Taps (Clicks)
                        changes.forEach { change ->
                            if (change.changedToUp()) {
                                val downInfo = pointerDownInfo.remove(change.id)
                                if (tapDrag.release()) {
                                    activeMouseButton = 0
                                } else if (downInfo != null) {
                                    val height = size.height
                                    val touchYStart = downInfo.second.y
                                    if (touchYStart > height * (1f - BUTTON_STRIP_FRACTION)) {
                                        // Started in the button strip: release the button.
                                        activeMouseButton = 0
                                        sendMouseReport(0, 0, 0, 0)
                                    } else {
                                        val duration = change.uptimeMillis - downInfo.first
                                        val dx = change.position.x - downInfo.second.x
                                        val dy = change.position.y - downInfo.second.y
                                        val distanceSq = dx * dx + dy * dy

                                        if (TouchpadGesturePolicy.isTap(
                                            durationMillis = duration,
                                            distanceSquaredPx = distanceSq,
                                            tapSlopPx = tapSlopPx,
                                        )) {
                                            if (downCount == 0) {
                                                currentOnClick()

                                                val clickButton = if (maxPointersInTap >= 3) 4 else if (maxPointersInTap == 2) 2 else 1

                                                if (clickButton == 1) {
                                                    // Match main: defer the click so tap-then-drag holds a
                                                    // single press instead of starting with a double-click.
                                                    tapDrag.queueClick(change.uptimeMillis, change.position.x, change.position.y)
                                                } else {
                                                    // Multi-finger click (Right/Middle click): send immediately
                                                    sendMouseReport(clickButton.toByte(), 0, 0, 0)
                                                    sendMouseReport(0, 0, 0, 0)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (downCount == 0) {
                            maxPointersInTap = 0
                        }
                    }
                }
                } finally {
                    // A cancelled pointer handler may remain composed (for example after
                    // changing button mode). Never leave a delayed click or held button behind.
                    tapDrag.cancel()
                    sendMouseReport(0, 0, 0, 0)
                    activeMouseButton = 0
                    pointerDownInfo.clear()
                    lastActivePointerId = null
                    maxPointersInTap = 0
                    accumulatedX = 0f
                    accumulatedY = 0f
                    scroller.stop()
                    isTwoFingerActive = false
                    activeTouchPoints = emptyList()
                }
            },
    ) {
        if (buttonMode != TrackpadButtonMode.CLICKPAD) {
            ButtonStrip(
                buttons = if (buttonMode == TrackpadButtonMode.TWO_BUTTONS) {
                    listOf("Left" to LEFT_BUTTON, "Right" to RIGHT_BUTTON)
                } else {
                    listOf("Left" to LEFT_BUTTON, "Middle" to MIDDLE_BUTTON, "Right" to RIGHT_BUTTON)
                },
                weights = if (buttonMode == TrackpadButtonMode.TWO_BUTTONS) listOf(0.5f, 0.5f) else listOf(0.35f, 0.3f, 0.35f),
                held = activeMouseButton,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        // A marker under each finger, shown at once and gone at once: it tracks, it doesn't animate.
        if (!showNumpadLed) {
            val colors = MaterialTheme.colorScheme
            val markerSize = if (activeTouchPoints.size >= 2) 24.dp else 28.dp
            val half = with(density) { (markerSize / 2).toPx() }
            activeTouchPoints.forEach { point ->
                Box(
                    modifier = Modifier
                        .offset { IntOffset((point.x - half).roundToInt(), (point.y - half).roundToInt()) }
                        .size(markerSize)
                        .shadow(elevation = 2.dp, shape = CircleShape, clip = false)
                        .background(colors.surfaceContainerHighest, CircleShape)
                        .border(1.dp, colors.outline, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(6.dp).background(colors.onSurfaceVariant, CircleShape))
                }
            }
        }
    }
}

/** The 2- or 3-button strip along the pad's bottom edge; the held button shows pressed. */
@Composable
private fun ButtonStrip(
    buttons: List<Pair<String, Byte>>,
    weights: List<Float>,
    held: Byte,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().fillMaxHeight(BUTTON_STRIP_FRACTION)) {
        // Outline, not outlineVariant: these lines are the buttons' boundaries, so they need 3:1.
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.outline))
        Row(Modifier.fillMaxSize()) {
            buttons.forEachIndexed { index, (label, mask) ->
                if (index > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(colors.outline))
                val pressed = held == mask
                Box(
                    modifier = Modifier
                        .weight(weights[index])
                        .fillMaxHeight()
                        .background(if (pressed) colors.secondaryContainer else colors.surfaceContainerHigh)
                        .testTag("touchpad_button_${label.lowercase()}"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (pressed) colors.onSecondaryContainer else colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

