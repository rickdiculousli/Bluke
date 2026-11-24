package dev.arnv.bluke.ui

import android.annotation.SuppressLint
import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.outlined.FilterNone
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import dev.arnv.bluke.bluetooth.BluetoothKeyboardManager
import dev.arnv.bluke.bluetooth.GAMEPAD_DPAD_MODE_PREFERENCE
import dev.arnv.bluke.bluetooth.GAMEPAD_GUIDE_BUTTON_INDEX
import dev.arnv.bluke.bluetooth.GAMEPAD_SHARE_BUTTON_INDEX
import dev.arnv.bluke.bluetooth.GamepadDpadOutputMode
import dev.arnv.bluke.data.LayoutRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.milliseconds

// ── Consoles ──

/** How a face button's symbol is drawn: a printed letter, or one of the four shapes. */
private enum class FaceGlyph { LETTER, TRIANGLE, CIRCLE, CROSS, SQUARE }

/** A button: its printed label, what TalkBack calls it, and its bit in the report's button mask. */
private data class ButtonDef(
    val label: String,
    val description: String,
    val mappingId: Int,
    val faceColor: Color = Color.Unspecified,
    val glyph: FaceGlyph = FaceGlyph.LETTER,
)

private data class ConsoleConfig(
    val id: String,
    val name: String,
    val faceTop: ButtonDef,
    val faceRight: ButtonDef,
    val faceBottom: ButtonDef,
    val faceLeft: ButtonDef,
    val leftBumper: ButtonDef,
    val rightBumper: ButtonDef,
    val leftTrigger: ButtonDef,
    val rightTrigger: ButtonDef,
    val selectButton: ButtonDef,
    val startButton: ButtonDef,
    val shareButton: ButtonDef = ButtonDef("SHARE", "Share", GAMEPAD_SHARE_BUTTON_INDEX),
    val guideButton: ButtonDef = ButtonDef("GUIDE", "Guide", GAMEPAD_GUIDE_BUTTON_INDEX),
    /** Xbox-style puts the left stick above the D-pad; PlayStation-style swaps them. */
    val leftStickAboveDpad: Boolean,
)

/**
 * The face buttons' printed colours. They are content, the same in every theme (like the keyboard's
 * colourways), and each reads at least 3:1 on the unpressed cap.
 */
private object GamepadFaceColors {
    val XboxY = Color(0xFFFFCA28)
    val XboxB = Color(0xFFEF5350)
    val XboxA = Color(0xFF66BB6A)
    val XboxX = Color(0xFF42A5F5)
    val PsTriangle = Color(0xFF4DB6AC)
    val PsCircle = Color(0xFFEF5350)
    val PsCross = Color(0xFF7986CB)
    val PsSquare = Color(0xFFF06292)
}

private const val GAMEPAD_REPORT_INTERVAL_MILLIS = 8L
private const val LEFT_STICK_BUTTON_INDEX = 10
private const val RIGHT_STICK_BUTTON_INDEX = 11

private val CONSOLES = listOf(
    ConsoleConfig(
        id = "xbox_series",
        name = "Xbox-style",
        faceTop = ButtonDef("Y", "Y", 3, GamepadFaceColors.XboxY),
        faceRight = ButtonDef("B", "B", 1, GamepadFaceColors.XboxB),
        faceBottom = ButtonDef("A", "A", 0, GamepadFaceColors.XboxA),
        faceLeft = ButtonDef("X", "X", 2, GamepadFaceColors.XboxX),
        leftBumper = ButtonDef("LB", "Left bumper", 4),
        rightBumper = ButtonDef("RB", "Right bumper", 5),
        leftTrigger = ButtonDef("LT", "Left trigger", 6),
        rightTrigger = ButtonDef("RT", "Right trigger", 7),
        selectButton = ButtonDef("VIEW", "View", 8),
        startButton = ButtonDef("MENU", "Menu", 9),
        leftStickAboveDpad = true,
    ),
    ConsoleConfig(
        id = "playstation_5",
        name = "PlayStation-style",
        faceTop = ButtonDef("△", "Triangle", 3, GamepadFaceColors.PsTriangle, FaceGlyph.TRIANGLE),
        faceRight = ButtonDef("◯", "Circle", 1, GamepadFaceColors.PsCircle, FaceGlyph.CIRCLE),
        faceBottom = ButtonDef("✕", "Cross", 0, GamepadFaceColors.PsCross, FaceGlyph.CROSS),
        faceLeft = ButtonDef("☐", "Square", 2, GamepadFaceColors.PsSquare, FaceGlyph.SQUARE),
        leftBumper = ButtonDef("L1", "L1", 4),
        rightBumper = ButtonDef("R1", "R1", 5),
        leftTrigger = ButtonDef("L2", "L2", 6),
        rightTrigger = ButtonDef("R2", "R2", 7),
        selectButton = ButtonDef("CREATE", "Create", 8),
        startButton = ButtonDef("OPTIONS", "Options", 9),
        leftStickAboveDpad = false,
    ),
)

/** The console stored under [GAMEPAD_CONSOLE_PREFERENCE]; an unknown or missing id reads as the first. */
private fun consoleFor(id: String?): ConsoleConfig = CONSOLES.firstOrNull { it.id == id } ?: CONSOLES.first()

// ── Tune ──

/** The gamepad's settings, as its Tune sheet and the Settings screen edit them. */
internal data class GamepadTuneState(
    val profile: GamepadDpadOutputMode = GamepadDpadOutputMode.NATIVE_HAT,
) {
    fun write(prefs: SharedPreferences) = prefs.edit {
        putString(GAMEPAD_DPAD_MODE_PREFERENCE, profile.preferenceValue)
    }

    companion object {
        val KEYS = arrayOf(GAMEPAD_DPAD_MODE_PREFERENCE)

        fun read(prefs: SharedPreferences) = GamepadTuneState(
            profile = GamepadDpadOutputMode.fromPreference(prefs.getString(GAMEPAD_DPAD_MODE_PREFERENCE, null)),
        )
    }
}

/** The gamepad's settings: which output profile the host's games get. Every profile shares one descriptor, so it switches live. */
@Composable
internal fun GamepadTuneContent(state: GamepadTuneState, onChange: (GamepadTuneState) -> Unit) {
    TuneSection("Profile", supporting = "Match the games on the host") {
        TuneChoice(
            label = null,
            options = GamepadDpadOutputMode.entries,
            selected = state.profile,
            optionLabel = { it.label },
            onSelect = { onChange(state.copy(profile = it)) },
        )
        Text(
            state.profile.description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun GamepadDpadOutputMode.icon(): ImageVector = when (this) {
    GamepadDpadOutputMode.NATIVE_HAT -> Icons.Default.Gamepad
    GamepadDpadOutputMode.ANDROID -> Icons.Default.Android
    GamepadDpadOutputMode.WEB_BUTTONS -> Icons.Default.Language
}

// ── Layout ──

/** The parts the user can move and resize. [key] names their stored offsets: `<console>_<key>_x|y|scale`. */
private enum class GamepadPart(val key: String, val description: String) {
    LEFT_TRIGGER("left_trigger", "left trigger"),
    LEFT_BUMPER("left_bumper", "left bumper"),
    RIGHT_TRIGGER("right_trigger", "right trigger"),
    RIGHT_BUMPER("right_bumper", "right bumper"),
    LEFT_STICK("left_stick", "left stick"),
    RIGHT_STICK("right_stick", "right stick"),
    DPAD("dpad", "D-pad"),
    FACE_BUTTONS("face_buttons", "face buttons"),
    GUIDE("guide", "Guide button"),
    SELECT("select", "left center button"),
    SHARE("share", "Share button"),
    START("start", "right center button"),
}

/** How far the user moved a part from where the layout puts it, in dp, and how much they scaled it. */
private data class PartTransform(val x: Float = 0f, val y: Float = 0f, val scale: Float = 1f)

private const val SHOULDER_WIDTH_DP = 112f
private const val EDGE_MARGIN_DP = 16f
/**
 * The space above the shoulder row, under the toolbar, on screen at every size: a thumb reaching for a
 * trigger lands clear of Close, the mode button and Tune. More than [TARGET_GAP_DP], since a trigger is
 * pressed blind.
 */
private const val SHOULDER_TOP_DP = 12f
/** The space kept below the lower clusters, above the controls area's bottom, on screen at every size. */
private const val BOTTOM_MARGIN_DP = 4f
/** Where the outer cluster (left stick or D-pad; face buttons) sits, from its side edge: under a resting thumb. */
private const val OUTER_CLUSTER_DP = 128f
/** Where the inner, lower cluster sits, from its side edge. */
private const val INNER_CLUSTER_DP = 248f
private const val CENTER_BUTTON_SPACING_DP = 60f
/** The face-button cluster's square touch box: two spacings plus one button. */
private const val FACE_SPACING_DP = 40f
private const val FACE_BUTTON_DP = 48f
private const val FACE_CLUSTER_DP = 2 * FACE_SPACING_DP + FACE_BUTTON_DP
private const val STICK_WELL_DP = 104f
/** A stick part's height: its well. The L3/R3 badge sits beside the well, in the part's outer column. */
private const val STICK_PART_HEIGHT_DP = STICK_WELL_DP
private const val DPAD_DP = 120f
/** The clear space kept between two parts' touch boxes. */
private const val PART_GAP_DP = 4f
/** The clear space between two stacked clusters, and between neighbouring targets, as the user sees it. */
private const val TARGET_GAP_DP = 8f
/** The smallest touch target any control has, as the user sees it. */
private const val MIN_TARGET_DP = 48f

/**
 * The smallest controls area the full-size layout fits with no two parts touching: the center row needs
 * the width between the inner clusters, and the right side stacks shoulders, face buttons and right stick
 * in the height. 312dp is a 360dp-tall landscape phone under the toolbar. A smaller area gets the same
 * layout scaled down whole (see [gamepadMetrics]).
 */
private const val INNER_CLUSTERS_WIDTH_DP = 2 * (INNER_CLUSTER_DP + DPAD_DP / 2f + PART_GAP_DP)
private const val LAYOUT_MIN_HEIGHT_DP =
    SHOULDER_TOP_DP + MIN_TARGET_DP + TARGET_GAP_DP + FACE_CLUSTER_DP + TARGET_GAP_DP + STICK_PART_HEIGHT_DP + BOTTOM_MARGIN_DP

/**
 * How the layout fits an area: [scale] is how much the parts shrink (1 at or above the minimum layout),
 * and [target] is the size, before that scale, that a touch target needs to stay [MIN_TARGET_DP] on screen.
 */
private data class GamepadMetrics(val scale: Float, val target: Float) {
    /** Center to center of the View/Share/Menu row, keeping [TARGET_GAP_DP] between them on screen. */
    val centerSpacing: Float get() = maxOf(CENTER_BUTTON_SPACING_DP, target + TARGET_GAP_DP / scale)
    val minWidth: Float get() = INNER_CLUSTERS_WIDTH_DP + 2 * centerSpacing + target
    val minHeight: Float get() =
        LAYOUT_MIN_HEIGHT_DP + (target - MIN_TARGET_DP) + (topMargin - SHOULDER_TOP_DP) + (bottomMargin - BOTTOM_MARGIN_DP)
    /** [SHOULDER_TOP_DP] and [BOTTOM_MARGIN_DP] before the scale, so they stay that size on screen. */
    val topMargin: Float get() = SHOULDER_TOP_DP / scale
    val bottomMargin: Float get() = BOTTOM_MARGIN_DP / scale
    /** A stick part's L3/R3 badge column, and so how far the part's centre sits from its well's. */
    val stickBadgeShift: Float get() = target / 2f
    val shoulderCenterY: Float get() = topMargin + target / 2f
}

/**
 * The metrics for an area [width]×[height] dp. Shrinking the parts grows the targets they keep, which
 * widens the layout, so the scale is found by iterating to its fixed point.
 */
private fun gamepadMetrics(width: Float, height: Float): GamepadMetrics {
    var metrics = GamepadMetrics(1f, MIN_TARGET_DP)
    repeat(24) {
        val scale = minOf(1f, width / metrics.minWidth, height / metrics.minHeight)
        // A dp of slack so rounding to pixels never leaves a target under the minimum.
        metrics = GamepadMetrics(scale, if (scale >= 1f) MIN_TARGET_DP else MIN_TARGET_DP / scale + 1f)
    }
    return metrics
}

/** The pre-scale size a touch target is drawn at, from the area's [GamepadMetrics]. */
private val LocalGamepadTarget = compositionLocalOf { MIN_TARGET_DP }

/**
 * Where each part's centre sits in a controls area [width]×[height] dp, before the user's edits.
 * Shoulders run along the top edge; each side has an upper outer cluster under the resting thumb and
 * a lower inner one, so both thumbs have the full height; Guide and the center buttons sit between.
 */
private fun baseLayout(config: ConsoleConfig, width: Float, height: Float): Map<GamepadPart, Offset> {
    // Lay out at full size in the area as the scaled-down parts see it, then scale the positions back.
    val metrics = gamepadMetrics(width, height)
    return fullSizeLayout(config, width / metrics.scale, height / metrics.scale, metrics)
        .mapValues { it.value * metrics.scale }
}

private fun fullSizeLayout(config: ConsoleConfig, width: Float, height: Float, metrics: GamepadMetrics): Map<GamepadPart, Offset> {
    val top = metrics.topMargin + metrics.target + TARGET_GAP_DP
    val bottom = height - metrics.bottomMargin
    val area = bottom - top

    /**
     * The centres of one side's upper and lower clusters, of heights [upper] and [lower]: at their resting
     * thumb heights when the area is tall, stacked with a gap between them when it is not.
     */
    fun side(upper: Float, lower: Float): Pair<Float, Float> {
        val lowerY = minOf(bottom - lower / 2f, maxOf(top + area * 0.76f, top + upper + TARGET_GAP_DP + lower / 2f))
        val upperY = maxOf(top + upper / 2f, minOf(top + area * 0.34f, lowerY - (upper + lower) / 2f - TARGET_GAP_DP))
        return upperY to lowerY
    }

    val shift = metrics.stickBadgeShift
    val (leftUpperY, leftLowerY) =
        if (config.leftStickAboveDpad) side(STICK_PART_HEIGHT_DP, DPAD_DP) else side(DPAD_DP, STICK_PART_HEIGHT_DP)
    val (faceY, rightStickY) = side(FACE_CLUSTER_DP, STICK_PART_HEIGHT_DP)
    val outerLeft = Offset(OUTER_CLUSTER_DP, leftUpperY)
    val innerLeft = Offset(INNER_CLUSTER_DP, leftLowerY)
    val leftStick = if (config.leftStickAboveDpad) outerLeft else innerLeft
    val dpad = if (config.leftStickAboveDpad) innerLeft else outerLeft
    val centerX = width / 2f
    val guideY = top + area * 0.36f
    val centerRowY = top + area * 0.68f
    val triggerX = EDGE_MARGIN_DP + SHOULDER_WIDTH_DP / 2f
    val bumperX = triggerX + SHOULDER_WIDTH_DP + 8f
    val shoulderY = metrics.shoulderCenterY
    val spacing = metrics.centerSpacing
    return mapOf(
        GamepadPart.LEFT_TRIGGER to Offset(triggerX, shoulderY),
        GamepadPart.LEFT_BUMPER to Offset(bumperX, shoulderY),
        GamepadPart.RIGHT_BUMPER to Offset(width - bumperX, shoulderY),
        GamepadPart.RIGHT_TRIGGER to Offset(width - triggerX, shoulderY),
        GamepadPart.LEFT_STICK to leftStick - Offset(shift, 0f),
        GamepadPart.DPAD to dpad,
        GamepadPart.FACE_BUTTONS to Offset(width - OUTER_CLUSTER_DP, faceY),
        GamepadPart.RIGHT_STICK to Offset(width - INNER_CLUSTER_DP + shift, rightStickY),
        GamepadPart.GUIDE to Offset(centerX, guideY),
        GamepadPart.SELECT to Offset(centerX - spacing, centerRowY),
        GamepadPart.SHARE to Offset(centerX, centerRowY),
        GamepadPart.START to Offset(centerX + spacing, centerRowY),
    )
}

/** The widest the status slot between the bumpers can be in an area [width]×[height] dp, keeping a gap to each. */
private fun statusSlotWidth(width: Float, height: Float): Float {
    val metrics = gamepadMetrics(width, height)
    val bumperInnerEdge = EDGE_MARGIN_DP + 2 * SHOULDER_WIDTH_DP + 8f
    return (width / metrics.scale - 2 * bumperInnerEdge) * metrics.scale - 2 * TARGET_GAP_DP
}

/**
 * The user's edits to one console's layout. They persist in [LayoutRepository]; older builds kept them
 * in app preferences under the same names, which seed a part until the repository answers.
 */
@Stable
private class GamepadLayoutEdits(
    private val consoleId: String,
    private val prefs: SharedPreferences,
    private val repository: LayoutRepository,
    private val scope: CoroutineScope,
) {
    private val transforms = mutableStateMapOf<GamepadPart, PartTransform>().apply {
        GamepadPart.entries.forEach { part ->
            put(
                part,
                PartTransform(
                    prefs.getFloat(name(part, "x"), 0f),
                    prefs.getFloat(name(part, "y"), 0f),
                    prefs.getFloat(name(part, "scale"), 1f),
                ),
            )
        }
    }
    private val pending = mutableMapOf<String, Float>()

    operator fun get(part: GamepadPart): PartTransform = transforms.getValue(part)

    val isModified: Boolean get() = transforms.values.any { it != PartTransform() }

    suspend fun load() {
        val stored = repository.load(consoleId)
        GamepadPart.entries.forEach { part ->
            val current = transforms.getValue(part)
            transforms[part] = PartTransform(
                stored[name(part, "x")] ?: current.x,
                stored[name(part, "y")] ?: current.y,
                stored[name(part, "scale")] ?: current.scale,
            )
        }
    }

    fun update(part: GamepadPart, transform: PartTransform) {
        transforms[part] = transform
        pending[name(part, "x")] = transform.x
        pending[name(part, "y")] = transform.y
        pending[name(part, "scale")] = transform.scale
    }

    fun commit() {
        val values = pending.toMap()
        pending.clear()
        scope.launch { repository.save(values) }
    }

    /** Every part's transform as it is now, for [restore]. */
    fun snapshot(): Map<GamepadPart, PartTransform> = transforms.toMap()

    /** Puts back a [snapshot] and saves it. */
    fun restore(snapshot: Map<GamepadPart, PartTransform>) {
        snapshot.forEach { (part, transform) -> update(part, transform) }
        commit()
    }

    fun reset() {
        GamepadPart.entries.forEach { transforms[it] = PartTransform() }
        pending.clear()
        prefs.edit {
            GamepadPart.entries.forEach { part -> listOf("x", "y", "scale").forEach { remove(name(part, it)) } }
        }
        scope.launch { repository.clear(consoleId) }
    }

    private fun name(part: GamepadPart, field: String) = "${consoleId}_${part.key}_$field"
}

/**
 * Places this element with its centre at [center] (dp, in the parent's coordinates), given the size of
 * the area it is placed in (dp, the parent's maximum constraints). Read at placement, so moving a part
 * only re-places it.
 */
private fun Modifier.centeredAt(center: (area: Size) -> Offset) = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    layout(placeable.width, placeable.height) {
        val c = center(Size(constraints.maxWidth.toDp().value, constraints.maxHeight.toDp().value))
        placeable.place(c.x.dp.roundToPx() - placeable.width / 2, c.y.dp.roundToPx() - placeable.height / 2)
    }
}

/** Like [centeredAt], and scales this element about its centre by the area's [GamepadMetrics.scale]. */
private fun Modifier.layoutPlaced(
    center: (area: Size) -> Offset,
    onScale: (Float) -> Unit,
    onSize: (longestDp: Float) -> Unit = {},
) = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    onSize(maxOf(placeable.width, placeable.height).toDp().value)
    layout(placeable.width, placeable.height) {
        val area = Size(constraints.maxWidth.toDp().value, constraints.maxHeight.toDp().value)
        val c = center(area)
        val scale = gamepadMetrics(area.width, area.height).scale
        onScale(scale)
        placeable.placeWithLayer(c.x.dp.roundToPx() - placeable.width / 2, c.y.dp.roundToPx() - placeable.height / 2) {
            scaleX = scale
            scaleY = scale
        }
    }
}

// ── Main view ──

@SuppressLint("MissingPermission")
@Composable
fun GamepadView(
    btManager: BluetoothKeyboardManager,
    chrome: SurfaceChrome,
    sharedPrefs: SharedPreferences,
    @Suppress("UNUSED_PARAMETER") caseBrush: Brush,
    initialEditMode: Boolean = false,
    initialTuneOpen: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberSurfaceHaptics(sharedPrefs)
    val colors = MaterialTheme.colorScheme

    val consoleId by rememberPreference(sharedPrefs, GAMEPAD_CONSOLE_PREFERENCE) {
        it.getString(GAMEPAD_CONSOLE_PREFERENCE, null)
    }
    val config = consoleFor(consoleId)
    val selectConsole = { next: ConsoleConfig ->
        if (next.id != config.id) {
            haptics.perform(HapticEvent.MODE_SWITCH)
            sharedPrefs.edit { putString(GAMEPAD_CONSOLE_PREFERENCE, next.id) }
        }
    }
    val tune by rememberPreference(sharedPrefs, *GamepadTuneState.KEYS) { GamepadTuneState.read(it) }
    val applyTune = { next: GamepadTuneState ->
        if (next.profile != tune.profile && btManager.changeGamepadMode(next.profile)) {
            haptics.perform(HapticEvent.TICK)
        }
    }

    var isEditMode by rememberSaveable { mutableStateOf(initialEditMode) }
    var tuneOpen by rememberSaveable { mutableStateOf(initialTuneOpen) }

    val layoutRepository = remember(context) { LayoutRepository(context) }
    val edits = remember(config.id, sharedPrefs, layoutRepository) {
        GamepadLayoutEdits(config.id, sharedPrefs, layoutRepository, scope)
    }
    LaunchedEffect(edits) { edits.load() }
    val snackbar = remember { SnackbarHostState() }
    // A reset wipes every saved move and resize, so it offers to put them back.
    val resetLayout = {
        val previous = edits.snapshot()
        edits.reset()
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            if (snackbar.showSnackbar("Layout reset", actionLabel = "Undo") == SnackbarResult.ActionPerformed) {
                edits.restore(previous)
            }
        }
        Unit
    }

    // ── Report state: what reaches btManager. Unchanged by the look. ──
    var buttonMask by remember { mutableIntStateOf(0) }
    var dpadMask by remember { mutableIntStateOf(0) }
    var isGamepadDirty by remember { mutableStateOf(false) }

    var leftStickX by remember { mutableFloatStateOf(0f) }
    var leftStickY by remember { mutableFloatStateOf(0f) }
    var rightStickX by remember { mutableFloatStateOf(0f) }
    var rightStickY by remember { mutableFloatStateOf(0f) }
    // The stick clicks the user has latched down with their L3/R3 badge, as button-mask bits.
    var heldSticks by remember { mutableIntStateOf(0) }

    val transmitGamepadState = { force: Boolean ->
        if (force) {
            btManager.sendGamepadReport(
                buttonMask,
                dpadMask,
                leftStickX,
                leftStickY,
                rightStickX,
                rightStickY
            )
            isGamepadDirty = false
        } else {
            isGamepadDirty = true
        }
    }

    LaunchedEffect(btManager) {
        while (isActive) {
            delay(GAMEPAD_REPORT_INTERVAL_MILLIS.milliseconds)
            if (isGamepadDirty) {
                btManager.sendGamepadReport(
                    buttonMask,
                    dpadMask,
                    leftStickX,
                    leftStickY,
                    rightStickX,
                    rightStickY
                )
                isGamepadDirty = false
            }
        }
    }

    val inputLifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(btManager, inputLifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                buttonMask = 0
                dpadMask = 0
                leftStickX = 0f
                leftStickY = 0f
                rightStickX = 0f
                rightStickY = 0f
                heldSticks = 0
                isGamepadDirty = false
                btManager.sendGamepadReport(0, 0, 0f, 0f, 0f, 0f)
            }
        }
        inputLifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            inputLifecycleOwner.lifecycle.removeObserver(observer)
            // Do not leave the host with a sampled stick/button state after this UI and its
            // 8 ms ticker are disposed. Release reports are safety-critical and bypass sampling.
            btManager.sendGamepadReport(0, 0, 0f, 0f, 0f, 0f)
        }
    }

    val setButton = { bitIndex: Int, down: Boolean ->
        buttonMask = if (down) buttonMask or (1 shl bitIndex) else buttonMask and (1 shl bitIndex).inv()
        transmitGamepadState(true)
    }
    // A press plays its haptic on touch-down; a release plays nothing.
    val pressButton = { bitIndex: Int ->
        haptics.perform(HapticEvent.PRESS)
        setButton(bitIndex, true)
    }
    val releaseButton = { bitIndex: Int -> setButton(bitIndex, false) }
    val onDpadChange = { mask: Int ->
        if (mask and dpadMask.inv() != 0) haptics.perform(HapticEvent.PRESS)
        dpadMask = mask
        transmitGamepadState(true)
    }
    // A stick click is known only when the tap lifts: it plays its haptic then and is down for 100ms.
    val stickClick = { bitIndex: Int ->
        haptics.perform(HapticEvent.PRESS)
        scope.launch {
            setButton(bitIndex, true)
            delay(100L.milliseconds)
            setButton(bitIndex, false)
        }
        Unit
    }
    val stickHold = { bitIndex: Int, hold: Boolean ->
        haptics.perform(HapticEvent.PRESS)
        heldSticks = if (hold) heldSticks or (1 shl bitIndex) else heldSticks and (1 shl bitIndex).inv()
        setButton(bitIndex, hold)
    }
    fun isDown(bitIndex: Int) = buttonMask and (1 shl bitIndex) != 0
    fun isHeld(bitIndex: Int) = heldSticks and (1 shl bitIndex) != 0

    // Editing takes Back to finish, so Back never closes the surface mid-edit.
    BackHandler(enabled = isEditMode) { isEditMode = false }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surface)
            .navigationBarsPadding()
            .testTag("gamepad_view_root")
    ) {
        Column(Modifier.fillMaxSize()) {
            SurfaceToolbar(chrome = chrome, haptics = haptics, onTune = { tuneOpen = true }) {
                if (isEditMode) {
                    TextButton(
                        onClick = resetLayout,
                        enabled = edits.isModified,
                        modifier = Modifier.testTag("gamepad_edit_reset"),
                    ) {
                        Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Reset", modifier = Modifier.padding(start = 8.dp))
                    }
                    FilledTonalButton(
                        onClick = { isEditMode = false },
                        modifier = Modifier.testTag("gamepad_edit_done"),
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Done", modifier = Modifier.padding(start = 8.dp))
                    }
                } else {
                    ConsoleControl(config, onClick = { selectConsole(CONSOLES[(CONSOLES.indexOf(config) + 1) % CONSOLES.size]) })
                    // A plain button: once tapped it gives way to Done, so it never shows an on state.
                    SurfaceToolbarIconButton(
                        icon = Icons.Default.Edit,
                        contentDescription = "Edit layout",
                        onClick = { isEditMode = true },
                        modifier = Modifier.testTag("edit_layout_toggle_btn"),
                    )
                }
            }

            // Parts find their place from the area's size when they are placed (see centeredAt); the
            // constraints choose between the controller and the rotate state, and size the touch targets.
            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
                    // Above the system's bottom gesture zone too, so the lowest arm never sits in the home swipe.
                    .windowInsetsPadding(WindowInsets.mandatorySystemGestures.only(WindowInsetsSides.Bottom))
                    // The Tune sheet is modal: while it is open, TalkBack finds only the sheet and the toolbar.
                    .then(if (tuneOpen) Modifier.clearAndSetSemantics {} else Modifier)
                    .testTag("gamepad_controls_area")
            ) {
                // A held controller is landscape. A window taller than it is wide (split screen, or a
                // large screen that ignores the orientation lock) gets the rotate state instead.
                if (maxWidth < maxHeight) {
                    TurnSidewaysState(
                        title = "Turn your phone sideways",
                        body = "The gamepad needs a landscape window. In split screen, make Bluke's window wider than it is tall.",
                        modifier = Modifier.align(Alignment.Center).testTag("gamepad_turn_sideways"),
                    )
                    return@BoxWithConstraints
                }

                val metrics = gamepadMetrics(maxWidth.value, maxHeight.value)
                // Each part's frame as last placed, in this area's pixels: the resize handles avoid them.
                val frames = remember { mutableStateMapOf<GamepadPart, Rect>() }
                var areaOrigin by remember { mutableStateOf(Offset.Zero) }

                @Composable
                fun Part(part: GamepadPart, content: @Composable () -> Unit) {
                    EditablePart(
                        part = part,
                        isEditMode = isEditMode,
                        transform = edits[part],
                        originIn = { area -> baseLayout(config, area.width, area.height).getValue(part) },
                        onTransform = { edits.update(part, it) },
                        onTransformEnd = edits::commit,
                        onFrame = { frames[part] = it.translate(-areaOrigin) },
                        content = content,
                    )
                }

                Box(Modifier.fillMaxSize().onPlaced { areaOrigin = it.positionInRoot() })
                CompositionLocalProvider(LocalGamepadTarget provides metrics.target) {
                    Part(GamepadPart.LEFT_TRIGGER) { ShoulderButton(config.leftTrigger, triggerShape(isLeft = true), pressButton, releaseButton) }
                    Part(GamepadPart.LEFT_BUMPER) { ShoulderButton(config.leftBumper, BumperShape, pressButton, releaseButton) }
                    Part(GamepadPart.RIGHT_BUMPER) { ShoulderButton(config.rightBumper, BumperShape, pressButton, releaseButton) }
                    Part(GamepadPart.RIGHT_TRIGGER) { ShoulderButton(config.rightTrigger, triggerShape(isLeft = false), pressButton, releaseButton) }
                    Part(GamepadPart.LEFT_STICK) {
                        AnalogStick(
                            description = "Left stick",
                            holdLabel = "L3",
                            badgeOnStart = true,
                            held = isHeld(LEFT_STICK_BUTTON_INDEX),
                            pressed = isDown(LEFT_STICK_BUTTON_INDEX),
                            onMove = { x, y -> leftStickX = x; leftStickY = y; transmitGamepadState(false) },
                            onStickClick = { stickClick(LEFT_STICK_BUTTON_INDEX) },
                            onToggleHold = { stickHold(LEFT_STICK_BUTTON_INDEX, it) },
                        )
                    }
                    Part(GamepadPart.DPAD) { Dpad(onDpadChange) }
                    Part(GamepadPart.FACE_BUTTONS) { FaceButtons(config, pressButton, releaseButton) }
                    Part(GamepadPart.RIGHT_STICK) {
                        AnalogStick(
                            description = "Right stick",
                            holdLabel = "R3",
                            badgeOnStart = false,
                            held = isHeld(RIGHT_STICK_BUTTON_INDEX),
                            pressed = isDown(RIGHT_STICK_BUTTON_INDEX),
                            onMove = { x, y -> rightStickX = x; rightStickY = y; transmitGamepadState(false) },
                            onStickClick = { stickClick(RIGHT_STICK_BUTTON_INDEX) },
                            onToggleHold = { stickHold(RIGHT_STICK_BUTTON_INDEX, it) },
                        )
                    }
                    Part(GamepadPart.GUIDE) { GuideButton(config.guideButton, pressButton, releaseButton) }
                    Part(GamepadPart.SELECT) { CenterButton(config.selectButton, Icons.Outlined.FilterNone, pressButton, releaseButton) }
                    Part(GamepadPart.SHARE) { CenterButton(config.shareButton, Icons.Default.IosShare, pressButton, releaseButton) }
                    Part(GamepadPart.START) { CenterButton(config.startButton, Icons.Default.Menu, pressButton, releaseButton) }
                }

                // The status slot between the shoulder pairs: the output profile, or how editing works.
                // It keeps to the width between the bumpers, so its touch box never reaches them.
                val slotWidth = statusSlotWidth(maxWidth.value, maxHeight.value).dp
                Box(
                    Modifier
                        .centeredAt { area -> Offset(area.width / 2f, metrics.shoulderCenterY * metrics.scale) }
                        .widthIn(max = slotWidth),
                ) {
                    if (isEditMode) EditHint() else ProfileChip(tune.profile, onClick = { tuneOpen = true })
                }

                // Above every part, so a handle always wins its touch over the frames under it.
                if (isEditMode) {
                    ResizeHandles(
                        frames = frames,
                        areaScale = metrics.scale,
                        scaleOf = { edits[it].scale },
                        onResize = { part, scale -> edits.update(part, edits[part].copy(scale = scale)) },
                        onResizeEnd = edits::commit,
                    )
                }
            }
        }

        SurfaceTuneSheet(visible = tuneOpen, title = "Gamepad", onDismiss = { tuneOpen = false }) {
            TuneSection("Console", supporting = "Button names and where the left stick sits") {
                TuneChoice(
                    label = null,
                    options = CONSOLES,
                    selected = config,
                    optionLabel = { it.name },
                    onSelect = selectConsole,
                )
            }
            GamepadTuneContent(tune, applyTune)
            TuneSection("Layout") {
                TuneAction("Edit layout", Icons.Default.Edit, onClick = {
                    tuneOpen = false
                    isEditMode = true
                })
                // TuneAction's look, but off while there is nothing to reset.
                OutlinedButton(
                    onClick = resetLayout,
                    enabled = edits.isModified,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("gamepad_tune_reset"),
                ) {
                    Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Reset layout", modifier = Modifier.padding(start = 8.dp))
                }
            }
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
}

// ── Chrome on the surface ──

/** The console toggle in the toolbar: shows the current console and switches to the next. */
@Composable
private fun ConsoleControl(config: ConsoleConfig, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .heightIn(min = 40.dp)
            .clip(CircleShape)
            // A text button: the mode button stays the toolbar's one filled pill, and with no outline it
            // never reads as the host chip, which reports status.
            .clickable(onClickLabel = "Switch console", role = Role.Button, onClick = onClick)
            .semantics { stateDescription = config.name }
            .padding(start = 12.dp, end = 16.dp)
            .testTag("console_selector_pill"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Default.SportsEsports, contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
        Text(config.name, style = MaterialTheme.typography.labelLarge, color = colors.primary, maxLines = 1)
    }
}

/**
 * Which output profile the host gets, always in view: a quiet status label, not a button to look at,
 * though a tap opens Tune to change it.
 */
@Composable
private fun ProfileChip(profile: GamepadDpadOutputMode, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(CircleShape)
            .clickable(onClickLabel = "Change profile", role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp)
            .testTag("gamepad_profile"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(profile.icon(), contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Text("${profile.label} profile", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun EditHint() {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .clip(MaterialTheme.shapes.large)
            .background(colors.primaryContainer)
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag("gamepad_edit_hint"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Drag a control to move it", style = MaterialTheme.typography.labelMedium, color = colors.onPrimaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("Drag a corner handle to resize", style = MaterialTheme.typography.labelMedium, color = colors.onPrimaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private const val MIN_PART_SCALE = 0.6f
private const val MAX_PART_SCALE = 1.8f
/** A small part may grow until its longer side is this long, as large as a cluster. */
private const val MAX_PART_SIDE_DP = 128f
private const val HANDLE_DP = 48f

/**
 * One movable part at its place in the controls area. It reports its frame, as placed, to [onFrame], and
 * keeps the system's Back swipe off itself, so a held control near a side edge never closes the surface.
 * In edit mode an outline covers it: dragging it moves the part, pinching resizes it, and the part itself
 * takes no input. Its resize handle is drawn above every part, by [ResizeHandles].
 */
@Composable
private fun EditablePart(
    part: GamepadPart,
    isEditMode: Boolean,
    transform: PartTransform,
    originIn: (area: Size) -> Offset,
    onTransform: (PartTransform) -> Unit,
    onTransformEnd: () -> Unit,
    onFrame: (Rect) -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current.density
    val current by rememberUpdatedState(transform)
    // Where the part sits and the area it sits in, as last placed; dragging clamps against them.
    val placed = remember { PlacedArea() }
    val currentOnTransform by rememberUpdatedState(onTransform)
    val currentOnTransformEnd by rememberUpdatedState(onTransformEnd)
    val currentOnFrame by rememberUpdatedState(onFrame)
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium

    // Keep the part's centre inside the controls area.
    fun clamped(next: PartTransform) = next.copy(
        x = next.x.coerceIn(-placed.origin.x, placed.area.width - placed.origin.x),
        y = next.y.coerceIn(-placed.origin.y, placed.area.height - placed.origin.y),
        scale = next.scale.coerceIn(MIN_PART_SCALE, maxPartScale(placed.longestDp)),
    )

    Box(
        modifier = Modifier
            .layoutPlaced(
                center = { area ->
                    val origin = originIn(area)
                    placed.origin = origin
                    placed.area = area
                    Offset(origin.x + current.x, origin.y + current.y)
                },
                onScale = { placed.scale = it },
                onSize = { placed.longestDp = it },
            )
            .graphicsLayer {
                scaleX = current.scale
                scaleY = current.scale
            }
            .onGloballyPositioned { currentOnFrame(it.boundsInRoot()) }
            .systemGestureExclusion()
            // Editing, the part sits on a selected tile, under its outline, so the control stays in view.
            .then(if (isEditMode) Modifier.background(colors.secondaryContainer, shape) else Modifier)
            .testTag("gamepad_part_${part.key}"),
        contentAlignment = Alignment.Center,
    ) {
        content()
        if (isEditMode) {
            Box(
                Modifier
                    .matchParentSize()
                    .border(2.dp, colors.primary, shape)
                    .semantics { contentDescription = "Move ${part.description}" }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var event: PointerEvent
                            do {
                                event = awaitPointerEvent()
                                val pan = event.calculatePan()
                                val zoom = event.calculateZoom()
                                val t = current
                                // The pan is in the part's own scaled space; offsets are in the area's dp.
                                val toArea = t.scale * placed.scale / density
                                currentOnTransform(
                                    clamped(
                                        PartTransform(
                                            x = t.x + pan.x * toArea,
                                            y = t.y + pan.y * toArea,
                                            scale = t.scale * zoom,
                                        ),
                                    ),
                                )
                                event.changes.forEach { it.consume() }
                            } while (event.changes.any { it.pressed })
                            currentOnTransformEnd()
                        }
                    }
                    .testTag("gamepad_frame_${part.key}")
            )
        }
    }
}

/** A frame corner a resize handle sits on, by which way is outward from the part's centre on each axis. */
private enum class HandleCorner(val sx: Float, val sy: Float) {
    BOTTOM_END(1f, 1f),
    BOTTOM_START(-1f, 1f),
    TOP_END(1f, -1f),
    TOP_START(-1f, -1f);

    fun of(frame: Rect) = Offset(if (sx > 0) frame.right else frame.left, if (sy > 0) frame.bottom else frame.top)
}

/**
 * The touch box of a resize handle on [corner] of [frame]: a [size]-px square centred on the corner, slid
 * just far enough to lie wholly in [area] (all px). Near an edge it still covers its corner.
 */
private fun handleBox(frame: Rect, corner: HandleCorner, area: Rect, size: Float): Rect {
    val c = corner.of(frame)
    val left = (c.x - size / 2f).coerceIn(area.left, maxOf(area.left, area.right - size))
    val top = (c.y - size / 2f).coerceIn(area.top, maxOf(area.top, area.bottom - size))
    return Rect(left, top, left + size, top + size)
}

/**
 * Which corner of its own frame each part's resize handle (its [handleBox], [size] px) sits on, given every
 * part's [frames] in an [area] (both px). The first of bottom-end, bottom-start, top-end and top-start whose
 * box covers its corner and no other part's frame or placed handle; failing that, the first that covers
 * its corner; failing that, bottom-end. A part in [frozen] (its handle is being dragged) keeps its corner.
 */
private fun resizeHandleCorners(
    frames: Map<GamepadPart, Rect>,
    area: Rect,
    size: Float,
    frozen: Map<GamepadPart, HandleCorner>,
): Map<GamepadPart, HandleCorner> {
    fun box(part: GamepadPart, corner: HandleCorner) = handleBox(frames.getValue(part), corner, area, size)
    val placed = linkedMapOf<GamepadPart, HandleCorner>()
    for (part in frames.keys.sortedBy { it.ordinal }) {
        val corner = frozen[part] ?: run {
            val others = frames.filterKeys { it != part }.values + placed.map { (p, c) -> box(p, c) }
            val onCorner = HandleCorner.entries.filter { c ->
                val b = box(part, c)
                c.of(frames.getValue(part)).let { it.x in b.left..b.right && it.y in b.top..b.bottom }
            }
            onCorner.firstOrNull { c -> others.none { it.overlaps(box(part, c)) } } ?: onCorner.firstOrNull() ?: HandleCorner.BOTTOM_END
        }
        placed[part] = corner
    }
    return placed
}

/** The largest scale a part whose longer side is [longestDp] (unscaled) may take: 1.8×, or a cluster's size for a small part. */
private fun maxPartScale(longestDp: Float): Float = maxOf(MAX_PART_SCALE, MAX_PART_SIDE_DP / longestDp)

/**
 * Every part's resize handle, on a corner of its frame, in one layer over the controls area. Dragging a
 * handle away from its part's centre grows the part about that centre, so the corner follows the finger.
 * [scaleOf] is a part's current scale, [areaScale] the area's [GamepadMetrics.scale], and [onResize] gets
 * the part's new scale.
 */
@Composable
private fun ResizeHandles(
    frames: Map<GamepadPart, Rect>,
    areaScale: Float,
    scaleOf: (GamepadPart) -> Float,
    onResize: (GamepadPart, Float) -> Unit,
    onResizeEnd: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val currentFrames by rememberUpdatedState(frames)
    val currentScaleOf by rememberUpdatedState(scaleOf)
    val currentOnResize by rememberUpdatedState(onResize)
    val currentOnResizeEnd by rememberUpdatedState(onResizeEnd)
    // The handle being dragged keeps its corner until it lets go, even if the part grows into a neighbour.
    var dragging by remember { mutableStateOf<Pair<GamepadPart, HandleCorner>?>(null) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current.density
        val sizePx = HANDLE_DP * density
        val area = Rect(0f, 0f, constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        val corners = resizeHandleCorners(frames.toMap(), area, sizePx, dragging?.let { mapOf(it) } ?: emptyMap())
        corners.forEach { (part, corner) ->
            val box = handleBox(frames.getValue(part), corner, area, sizePx)
            // Where the corner sits from the box's centre: the dot marks the corner itself.
            val dot = corner.of(frames.getValue(part)) - box.center
            key(part) {
                // Where the handle sits in the root, as last placed: the drag is measured in the root, so the
                // handle moving with the corner it follows doesn't change what the finger has done.
                val origin = remember { floatArrayOf(0f, 0f) }
                Box(
                    Modifier
                        .offset { IntOffset(box.left.roundToInt(), box.top.roundToInt()) }
                        .size(HANDLE_DP.dp)
                        .onGloballyPositioned { it.positionInRoot().let { p -> origin[0] = p.x; origin[1] = p.y } }
                        .systemGestureExclusion()
                        .semantics { contentDescription = "Resize ${part.description}" }
                        .pointerInput(part, corner) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()
                                val frame = currentFrames[part] ?: return@awaitEachGesture
                                val startScale = currentScaleOf(part)
                                // The corner's offset from the centre at scale 1; growing by one scale step moves the
                                // corner by its length, along it.
                                val half = Offset(corner.sx * frame.width / 2f, corner.sy * frame.height / 2f) / startScale
                                val length = half.getDistance()
                                val maxScale = maxPartScale(maxOf(frame.width, frame.height) / startScale / areaScale / density)
                                fun root(p: Offset) = Offset(origin[0] + p.x, origin[1] + p.y)
                                val start = root(down.position)
                                dragging = part to corner
                                try {
                                    while (true) {
                                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                        if (!change.pressed) break
                                        val moved = root(change.position) - start
                                        val along = (moved.x * half.x + moved.y * half.y) / length
                                        currentOnResize(part, (startScale + along / length).coerceIn(MIN_PART_SCALE, maxScale))
                                        change.consume()
                                    }
                                } finally {
                                    dragging = null
                                    currentOnResizeEnd()
                                }
                            }
                        }
                        .testTag("gamepad_resize_${part.key}"),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .offset { IntOffset(dot.x.roundToInt(), dot.y.roundToInt()) }
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(colors.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.OpenInFull, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

/** A part's last placement: not state, since only the drag handler reads it. */
private class PlacedArea {
    var origin: Offset = Offset.Zero
    var area: Size = Size.Zero
    var scale: Float = 1f
    /** The part's longer side before any scale, in dp. */
    var longestDp: Float = MAX_PART_SIDE_DP
}

// ── Controls ──

/**
 * A gamepad button's touch and TalkBack handling: it presses on touch-down and releases on lift, and a
 * TalkBack activation sends a press and a release.
 */
private fun Modifier.gamepadButton(
    button: ButtonDef,
    onPress: (Int) -> Unit,
    onRelease: (Int) -> Unit,
    onPressedChange: (Boolean) -> Unit,
): Modifier = this
    .semantics {
        contentDescription = button.description
        role = Role.Button
        onClick {
            onPress(button.mappingId)
            onRelease(button.mappingId)
            true
        }
    }
    .pointerInput(button.mappingId) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val targetPointerId = down.id
            onPressedChange(true)
            onPress(button.mappingId)
            down.consume()
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == targetPointerId } ?: break
                if (!change.pressed) {
                    onPressedChange(false)
                    onRelease(button.mappingId)
                    break
                }
                change.consume()
            }
        }
    }

/**
 * A cap's fill and its label colour, straight from the pressed state: the pressed look lands with the
 * touch. Pressed fills with primary, as Guide does, so it stands at least 3:1 off the resting cap.
 */
internal fun capColors(colors: ColorScheme, pressed: Boolean): Pair<Color, Color> =
    if (pressed) colors.primary to colors.onPrimary
    else colors.surfaceContainerHighest to colors.onSurface

@Composable
private fun capColors(pressed: Boolean): Pair<Color, Color> = capColors(MaterialTheme.colorScheme, pressed)

private val BumperShape = RoundedCornerShape(24.dp)

private fun triggerShape(isLeft: Boolean): Shape = if (isLeft) {
    RoundedCornerShape(topStart = 24.dp, topEnd = 8.dp, bottomEnd = 8.dp, bottomStart = 8.dp)
} else {
    RoundedCornerShape(topStart = 8.dp, topEnd = 24.dp, bottomEnd = 8.dp, bottomStart = 8.dp)
}

@Composable
private fun ShoulderButton(button: ButtonDef, shape: Shape, onPress: (Int) -> Unit, onRelease: (Int) -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val (fill, content) = capColors(pressed)
    val currentOnPress by rememberUpdatedState(onPress)
    val currentOnRelease by rememberUpdatedState(onRelease)
    Box(
        modifier = Modifier
            .size(width = SHOULDER_WIDTH_DP.dp, height = LocalGamepadTarget.current.dp)
            .clip(shape)
            .background(fill)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .gamepadButton(button, { currentOnPress(it) }, { currentOnRelease(it) }) { pressed = it }
            .testTag("gamepad_button_${button.mappingId}"),
        contentAlignment = Alignment.Center,
    ) {
        Text(button.label, style = MaterialTheme.typography.labelLarge, color = content)
    }
}

@Composable
private fun CenterButton(button: ButtonDef, icon: ImageVector, onPress: (Int) -> Unit, onRelease: (Int) -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val (fill, content) = capColors(pressed)
    val currentOnPress by rememberUpdatedState(onPress)
    val currentOnRelease by rememberUpdatedState(onRelease)
    Box(
        modifier = Modifier
            .size(LocalGamepadTarget.current.dp)
            .gamepadButton(button, { currentOnPress(it) }, { currentOnRelease(it) }) { pressed = it }
            .testTag("gamepad_button_${button.mappingId}"),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(fill)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
        }
    }
}

/** Guide: a house, which reads as Home/Guide on either console without borrowing a maker's logo. */
@Composable
private fun GuideButton(button: ButtonDef, onPress: (Int) -> Unit, onRelease: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    var pressed by remember { mutableStateOf(false) }
    val currentOnPress by rememberUpdatedState(onPress)
    val currentOnRelease by rememberUpdatedState(onRelease)
    Box(
        modifier = Modifier
            .size(maxOf(56f, LocalGamepadTarget.current).dp)
            .gamepadButton(button, { currentOnPress(it) }, { currentOnRelease(it) }) { pressed = it }
            .testTag("gamepad_guide"),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(if (pressed) colors.primary else colors.surfaceContainerHighest)
                .border(2.dp, if (pressed) colors.primary else colors.outlineVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Home, contentDescription = null, tint = if (pressed) colors.onPrimary else colors.onSurface, modifier = Modifier.size(28.dp))
        }
    }
}

/**
 * The four face buttons. One touch handler covers the cluster so a thumb resting between two buttons
 * presses both, as on a real pad.
 */
@Composable
private fun FaceButtons(config: ConsoleConfig, onPress: (Int) -> Unit, onRelease: (Int) -> Unit) {
    val active = remember { mutableStateListOf<Int>() }
    val currentOnPress by rememberUpdatedState(onPress)
    val currentOnRelease by rememberUpdatedState(onRelease)
    val slots = listOf(
        config.faceTop to Offset(0f, -1f),
        config.faceRight to Offset(1f, 0f),
        config.faceBottom to Offset(0f, 1f),
        config.faceLeft to Offset(-1f, 0f),
    )

    Box(
        modifier = Modifier
            .size(FACE_CLUSTER_DP.dp)
            .pointerInput(config.id) {
                val spacingPx = FACE_SPACING_DP.dp.toPx()
                // Proximity radius covering multi-button thumb presses.
                val touchRadiusPx = 35.dp.toPx()

                fun update(pointers: Collection<Offset>) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val touched = slots.mapNotNull { (button, direction) ->
                        val buttonCenter = center + direction * spacingPx
                        button.mappingId.takeIf {
                            pointers.any { (it - buttonCenter).getDistance() <= touchRadiusPx }
                        }
                    }.toSet()
                    touched.filterNot(active::contains).forEach {
                        active.add(it)
                        currentOnPress(it)
                    }
                    active.filterNot(touched::contains).forEach {
                        active.remove(it)
                        currentOnRelease(it)
                    }
                }

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val positions = mutableMapOf<PointerId, Offset>(down.id to down.position)
                    update(positions.values)
                    while (true) {
                        val event = awaitPointerEvent()
                        for (change in event.changes) {
                            if (change.pressed) positions[change.id] = change.position else positions.remove(change.id)
                        }
                        if (positions.isEmpty()) {
                            active.toList().forEach(currentOnRelease)
                            active.clear()
                            break
                        }
                        update(positions.values)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        slots.forEach { (button, direction) ->
            FaceButton(
                button = button,
                pressed = button.mappingId in active,
                onAccessibilityClick = {
                    currentOnPress(button.mappingId)
                    currentOnRelease(button.mappingId)
                },
                modifier = Modifier.offset(x = (direction.x * FACE_SPACING_DP).dp, y = (direction.y * FACE_SPACING_DP).dp),
            )
        }
    }
}

@Composable
private fun FaceButton(button: ButtonDef, pressed: Boolean, onAccessibilityClick: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    // Pressed, the cap fills with its printed colour and the symbol cuts out of it.
    val fill = if (pressed) button.faceColor else colors.surfaceContainerHighest
    val symbol = if (pressed) colors.surface else button.faceColor
    Box(
        modifier = modifier
            .size(FACE_BUTTON_DP.dp)
            .clip(CircleShape)
            .background(fill)
            .border(1.dp, if (pressed) button.faceColor else colors.outlineVariant, CircleShape)
            .semantics {
                contentDescription = button.description
                role = Role.Button
                stateDescription = if (pressed) "Pressed" else "Released"
                onClick {
                    onAccessibilityClick()
                    true
                }
            }
            .testTag("gamepad_face_${button.description}"),
        contentAlignment = Alignment.Center,
    ) {
        if (button.glyph == FaceGlyph.LETTER) {
            Text(
                button.label,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
                color = symbol,
                textAlign = TextAlign.Center,
            )
        } else {
            Canvas(Modifier.size(22.dp)) { drawFaceShape(button.glyph, symbol) }
        }
    }
}

private fun DrawScope.drawFaceShape(glyph: FaceGlyph, color: Color) {
    val stroke = Stroke(width = 2.5.dp.toPx())
    val c = center
    val r = size.minDimension / 2f - stroke.width / 2f
    when (glyph) {
        FaceGlyph.TRIANGLE -> drawPath(
            Path().apply {
                moveTo(c.x, c.y - r)
                lineTo(c.x + r, c.y + r * 0.7f)
                lineTo(c.x - r, c.y + r * 0.7f)
                close()
            },
            color,
            style = stroke,
        )
        FaceGlyph.CIRCLE -> drawCircle(color, radius = r, center = c, style = stroke)
        FaceGlyph.CROSS -> {
            val d = r * 0.85f
            drawLine(color, Offset(c.x - d, c.y - d), Offset(c.x + d, c.y + d), strokeWidth = stroke.width)
            drawLine(color, Offset(c.x + d, c.y - d), Offset(c.x - d, c.y + d), strokeWidth = stroke.width)
        }
        FaceGlyph.SQUARE -> {
            val side = r * 1.6f
            drawRect(color, topLeft = Offset(c.x - side / 2f, c.y - side / 2f), size = Size(side, side), style = stroke)
        }
        FaceGlyph.LETTER -> Unit
    }
}

private const val STICK_CAP_DP = 72f
/** How far the thumb must travel from the centre for full deflection. */
private const val STICK_INPUT_RADIUS_DP = 32f
/** How far the cap travels on screen. */
private const val STICK_VISUAL_RADIUS_DP = 20f

/**
 * An analog stick with its L3/R3 hold badge beside it on the outer side, clear of the well so the cap
 * never covers it. A quick tap clicks the stick; the badge holds the click down until tapped again.
 * The cap takes the pressed colours while its click is down, [pressed], whether clicked or [held].
 */
@Composable
private fun AnalogStick(
    description: String,
    holdLabel: String,
    badgeOnStart: Boolean,
    held: Boolean,
    pressed: Boolean,
    onMove: (Float, Float) -> Unit,
    onStickClick: () -> Unit,
    onToggleHold: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var stickOffsetX by remember { mutableFloatStateOf(0f) }
    var stickOffsetY by remember { mutableFloatStateOf(0f) }
    val currentIsHeld by rememberUpdatedState(held)
    val currentOnStickClick by rememberUpdatedState(onStickClick)
    val currentOnMove by rememberUpdatedState(onMove)

    val target = LocalGamepadTarget.current
    Box(Modifier.size(width = (STICK_WELL_DP + 8f + target).dp, height = STICK_PART_HEIGHT_DP.dp)) {
        Box(
            modifier = Modifier
                .align(if (badgeOnStart) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(horizontal = 4.dp)
                .size(STICK_WELL_DP.dp)
                .semantics { contentDescription = description }
                .pointerInput(Unit) {
                    val centerPx = size.width / 2f
                    val maxInputRadius = STICK_INPUT_RADIUS_DP.dp.toPx()
                    val maxVisualRadius = STICK_VISUAL_RADIUS_DP.dp.toPx()
                    val tapSlopPx = 8f

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val pointerId = down.id
                        val downTime = System.currentTimeMillis()

                        val updateStickOffset = { pos: Offset ->
                            val dx = pos.x - centerPx
                            val dy = pos.y - centerPx
                            val dist = sqrt(dx * dx + dy * dy)

                            if (dist == 0f) {
                                stickOffsetX = 0f
                                stickOffsetY = 0f
                                currentOnMove(0f, 0f)
                            } else {
                                val normDist = (dist / maxInputRadius).coerceIn(0f, 1f)
                                val normalizedX = (dx / dist) * normDist
                                val normalizedY = (dy / dist) * normDist

                                val visualDist = dist.coerceAtMost(maxVisualRadius)
                                stickOffsetX = (dx / dist) * visualDist
                                stickOffsetY = (dy / dist) * visualDist

                                currentOnMove(normalizedX, normalizedY)
                            }
                        }

                        updateStickOffset(down.position)

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == pointerId } ?: break

                            if (!change.pressed) {
                                val dragDistance = (change.position - down.position).getDistance()
                                if (dragDistance < tapSlopPx && System.currentTimeMillis() - downTime < 250) {
                                    if (!currentIsHeld) {
                                        currentOnStickClick()
                                    }
                                }
                                stickOffsetX = 0f
                                stickOffsetY = 0f
                                currentOnMove(0f, 0f)
                                break
                            }

                            change.consume()
                            updateStickOffset(change.position)
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            // The well: a recess with an outline that reads at 3:1 on the surface.
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(colors.surfaceContainerLowest)
                drawCircle(colors.outline, radius = size.minDimension / 2f - 1.dp.toPx(), style = Stroke(2.dp.toPx()))
            }
            Box(
                Modifier
                    .offset { IntOffset(stickOffsetX.roundToInt(), stickOffsetY.roundToInt()) }
                    .size(STICK_CAP_DP.dp)
            ) {
                val (cap, _) = capColors(pressed)
                Canvas(Modifier.fillMaxSize()) {
                    val r = size.minDimension / 2f
                    drawCircle(cap, radius = r)
                    drawCircle(colors.outlineVariant, radius = r - 0.5.dp.toPx(), style = Stroke(1.dp.toPx()))
                    // The thumb dish: recessed at rest; pressed, the whole cap is one pressed fill and the
                    // dish keeps only its outline.
                    drawCircle(if (pressed) cap else colors.surfaceContainerHigh, radius = r * 0.6f)
                    drawCircle(colors.outlineVariant, radius = r * 0.6f, style = Stroke(1.dp.toPx()))
                }
            }
        }

        StickHoldBadge(
            label = holdLabel,
            held = held,
            onToggle = onToggleHold,
            modifier = Modifier.align(if (badgeOnStart) Alignment.BottomStart else Alignment.BottomEnd),
        )
    }
}

@Composable
private fun StickHoldBadge(label: String, held: Boolean, onToggle: (Boolean) -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val currentHeld by rememberUpdatedState(held)
    val currentOnToggle by rememberUpdatedState(onToggle)
    Box(
        modifier = modifier
            .size(LocalGamepadTarget.current.dp)
            .semantics {
                contentDescription = "Hold $label"
                role = Role.Switch
                stateDescription = if (held) "Held" else "Released"
                onClick {
                    currentOnToggle(!currentHeld)
                    true
                }
            }
            // Toggles on touch-down, like every other button here.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    currentOnToggle(!currentHeld)
                }
            }
            .testTag("gamepad_hold_$label"),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(if (held) colors.primary else colors.surfaceContainerHigh)
                .border(1.dp, if (held) colors.primary else colors.outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = if (held) colors.onPrimary else colors.onSurfaceVariant)
        }
    }
}

private const val DPAD_UP = 1
private const val DPAD_DOWN = 2
private const val DPAD_LEFT = 4
private const val DPAD_RIGHT = 8

private fun plusPath(width: Float, bar: Float, inset: Float, corner: Float): Path {
    val start = (width - bar) / 2f
    val horizontal = Path().apply {
        addRoundRect(RoundRect(inset, start, width - inset, start + bar, CornerRadius(corner)))
    }
    val vertical = Path().apply {
        addRoundRect(RoundRect(start, inset, start + bar, width - inset, CornerRadius(corner)))
    }
    return Path.combine(PathOperation.Union, horizontal, vertical)
}

/** The D-pad. A pressed arm fills on touch-down; there is no tilt or other motion on repeated input. */
@Composable
private fun Dpad(onDpadChange: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    var activeDirection by remember { mutableIntStateOf(0) }
    val currentOnDpadChange by rememberUpdatedState(onDpadChange)
    val directions = listOf(DPAD_UP to "Up", DPAD_DOWN to "Down", DPAD_LEFT to "Left", DPAD_RIGHT to "Right")

    Box(
        modifier = Modifier
            .size(DPAD_DP.dp)
            .semantics {
                contentDescription = "D-pad"
                customActions = directions.map { (bit, name) ->
                    CustomAccessibilityAction(name) {
                        currentOnDpadChange(bit)
                        currentOnDpadChange(0)
                        true
                    }
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val targetPointerId = down.id
                    var currentBit = determineDpadBit(
                        down.position.x,
                        down.position.y,
                        size.width.toFloat(),
                        size.height.toFloat(),
                    )
                    activeDirection = currentBit
                    currentOnDpadChange(currentBit)

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == targetPointerId } ?: break
                        if (!change.pressed) {
                            activeDirection = 0
                            currentOnDpadChange(0)
                            break
                        }
                        change.consume()
                        val newBit = determineDpadBit(
                            change.position.x,
                            change.position.y,
                            size.width.toFloat(),
                            size.height.toFloat(),
                        )
                        if (newBit != currentBit) {
                            currentBit = newBit
                            activeDirection = currentBit
                            currentOnDpadChange(currentBit)
                        }
                    }
                }
            }
            .testTag("gamepad_dpad"),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val c = center
            val well = plusPath(w, bar = w * 0.38f, inset = 1.dp.toPx(), corner = 10.dp.toPx())
            drawPath(well, colors.surfaceContainerLowest)
            drawPath(well, colors.outline, style = Stroke(1.5.dp.toPx()))

            val bar = w * 0.30f
            val start = (w - bar) / 2f
            val cross = plusPath(w, bar = bar, inset = w * 0.05f, corner = 6.dp.toPx())
            drawPath(cross, colors.surfaceContainerHighest)
            clipPath(cross) {
                val arms = mapOf(
                    DPAD_UP to Rect(start, 0f, start + bar, c.y),
                    DPAD_DOWN to Rect(start, c.y, start + bar, w),
                    DPAD_LEFT to Rect(0f, start, c.x, start + bar),
                    DPAD_RIGHT to Rect(c.x, start, w, start + bar),
                )
                arms.forEach { (bit, rect) ->
                    if (activeDirection and bit != 0) drawRect(capColors(colors, pressed = true).first, rect.topLeft, rect.size)
                }
            }
            drawPath(cross, colors.outlineVariant, style = Stroke(1.dp.toPx()))
            drawCircle(colors.surfaceContainerHigh, radius = bar * 0.36f, center = c)

            val arrowDist = w * 0.36f
            val arrow = w * 0.055f
            fun arrowColor(bit: Int) = if (activeDirection and bit != 0) capColors(colors, pressed = true).second else colors.onSurfaceVariant
            fun triangle(tip: Offset, back: Offset, side: Offset) = Path().apply {
                moveTo(tip.x, tip.y)
                lineTo(back.x + side.x, back.y + side.y)
                lineTo(back.x - side.x, back.y - side.y)
                close()
            }
            drawPath(triangle(Offset(c.x, c.y - arrowDist), Offset(c.x, c.y - arrowDist + arrow * 1.2f), Offset(arrow, 0f)), arrowColor(DPAD_UP))
            drawPath(triangle(Offset(c.x, c.y + arrowDist), Offset(c.x, c.y + arrowDist - arrow * 1.2f), Offset(arrow, 0f)), arrowColor(DPAD_DOWN))
            drawPath(triangle(Offset(c.x - arrowDist, c.y), Offset(c.x - arrowDist + arrow * 1.2f, c.y), Offset(0f, arrow)), arrowColor(DPAD_LEFT))
            drawPath(triangle(Offset(c.x + arrowDist, c.y), Offset(c.x + arrowDist - arrow * 1.2f, c.y), Offset(0f, arrow)), arrowColor(DPAD_RIGHT))
        }
    }
}
