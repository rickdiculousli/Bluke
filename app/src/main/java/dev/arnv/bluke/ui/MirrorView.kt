package dev.arnv.bluke.ui

import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.arnv.bluke.BlukeApplication
import dev.arnv.bluke.QuickCycleActivity
import dev.arnv.bluke.bluetooth.BluetoothKeyboardManager
import dev.arnv.bluke.mirror.MirrorFit
import dev.arnv.bluke.mirror.MirrorNotice
import dev.arnv.bluke.mirror.MirrorPhase
import dev.arnv.bluke.mirror.MirrorRotation
import dev.arnv.bluke.mirror.MirrorTouchFrame
import dev.arnv.bluke.mirror.MirrorTouchMode
import dev.arnv.bluke.mirror.MirrorTouchPoint
import dev.arnv.bluke.mirror.MirrorUiState
import dev.arnv.bluke.mirror.fitMirror
import dev.arnv.bluke.mirror.isLive
import dev.arnv.bluke.mirror.toNormalized
import kotlinx.coroutines.delay
import kotlin.math.hypot

/**
 * Where the surface gets its picture. [frameSeq] changes once per decoded frame and is read only
 * while drawing, so a new frame redraws the image without recomposing the surface.
 */
interface MirrorFrameSource {
    val frameSeq: State<Long>
    /** The newest decoded frame, or null before the first. */
    fun currentFrame(): ImageBitmap?
    fun onFrameDrawn(seq: Long) = Unit
}

/** Mirror: the host's screen at low resolution, where a finger on the image acts on the host. */
@Composable
fun MirrorView(
    btManager: BluetoothKeyboardManager,
    onClose: () -> Unit,
    launchMode: Int,
    onModeChange: (Int) -> Unit,
    sharedPrefs: SharedPreferences,
) {
    val context = LocalContext.current
    val controller = remember(context) { (context.applicationContext as BlukeApplication).mirrorController }
    val state by controller.state.collectAsState()
    val hapticsEnabled = remember(sharedPrefs) { sharedPrefs.getBoolean("haptics_enabled", true) }
    val lifecycleOwner = LocalLifecycleOwner.current
    // Mirror lives exactly while its surface is on screen: listening, the Wi-Fi lock, everything.
    DisposableEffect(lifecycleOwner, controller) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> controller.start()
                Lifecycle.Event.ON_STOP -> controller.stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.start()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.stop()
        }
    }

    // Forget waits out the notice's undo window; leaving the surface inside the window still forgets.
    var forgetPending by remember { mutableStateOf(false) }
    val currentForgetPending by rememberUpdatedState(forgetPending)
    val undoWindowMillis = noticeMillis(withAction = true)
    LaunchedEffect(forgetPending) {
        if (!forgetPending) return@LaunchedEffect
        delay(undoWindowMillis)
        forgetPending = false
        controller.forgetCompanion()
        // The notice with the Undo already said "Computer forgotten"; don't say it twice.
        controller.clearNotice()
    }
    DisposableEffect(controller) {
        onDispose { if (currentForgetPending) controller.forgetCompanion() }
    }

    MirrorSurface(
        state = state,
        frames = controller.frames,
        onClose = onClose,
        onNextMode = {
            val enabled = sharedPrefs.enabledInputModes().map(InputMode::id)
            val index = enabled.indexOf(launchMode)
            onModeChange(enabled[(index + 1) % enabled.size])
        },
        onConfigureModes = { context.startActivity(Intent(context, QuickCycleActivity::class.java)) },
        onTouchModeChange = controller::setTouchMode,
        onForgetCompanion = { forgetPending = true },
        onNoticeShown = controller::clearNotice,
        onTouch = { frame ->
            // A finger landing wakes the Bluetooth link too, so a keystroke after Mirror play isn't late.
            if (frame.points.any { it.down && !it.wasDown }) btManager.holdLinkAwake()
            controller.onTouch(frame)
        },
        onImagePlaced = { controller.imageBoundsInWindow = it },
        hapticsEnabled = hapticsEnabled,
        forgetPending = forgetPending,
        onUndoForget = { forgetPending = false },
    )
}

/**
 * The whole surface, drawn from [state] alone. [hapticsEnabled] is the app's haptics preference;
 * [forgetPending] shows "Computer forgotten" with an Undo while the forget waits out its window.
 */
@Composable
fun MirrorSurface(
    state: MirrorUiState,
    frames: MirrorFrameSource,
    onClose: () -> Unit,
    onNextMode: () -> Unit,
    onConfigureModes: () -> Unit,
    onTouchModeChange: (MirrorTouchMode) -> Unit,
    onForgetCompanion: () -> Unit,
    onNoticeShown: () -> Unit,
    onTouch: (MirrorTouchFrame) -> Unit,
    modifier: Modifier = Modifier,
    onImagePlaced: (FloatArray) -> Unit = {},
    hapticsEnabled: Boolean = false,
    forgetPending: Boolean = false,
    onUndoForget: () -> Unit = {},
) {
    val haptics = rememberMirrorHaptics(hapticsEnabled)
    LaunchedEffect(state.notice) {
        if (state.notice is MirrorNotice.WrongCode) haptics.reject()
    }
    Row(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .testTag("mirror_view_root"),
    ) {
        MirrorRail(
            state = state,
            haptics = haptics,
            onClose = onClose,
            onNextMode = onNextMode,
            onConfigureModes = onConfigureModes,
            onTouchModeChange = onTouchModeChange,
            onForgetCompanion = onForgetCompanion,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.End)),
        ) {
            MirrorImage(
                frames = frames,
                live = state.phase.isLive,
                touchMode = state.touchMode,
                haptics = haptics,
                onTouch = onTouch,
                onImagePlaced = onImagePlaced,
            )
            // The notice stacks above the status card, so neither covers the other; the column has
            // no pointer input, so touches outside them reach the picture.
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                MirrorNoticePill(
                    notice = state.notice,
                    forgetPending = forgetPending,
                    onShown = onNoticeShown,
                    onUndoForget = onUndoForget,
                )
                MirrorOverlay(
                    state = state,
                    hasStaleFrame = frames.currentFrame() != null,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
        }
    }
}

/** The surface's haptics, each tied to the event that causes it; silent when the app's preference is off. */
private class MirrorHaptics(private val view: View, private val enabled: Boolean) {
    /** A press reaches the computer: Direct touch-down, Hover tap. */
    fun press() = perform(HapticFeedbackConstants.KEYBOARD_TAP)

    /** A segmented choice changed (Direct/Hover) or the input mode moved on. */
    fun tick() = perform(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) HapticFeedbackConstants.SEGMENT_TICK
        else HapticFeedbackConstants.CLOCK_TICK,
    )

    /** The computer sent a wrong pairing code. */
    fun reject() = perform(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT
        else HapticFeedbackConstants.LONG_PRESS,
    )

    private fun perform(constant: Int) {
        if (enabled) view.performHapticFeedback(constant)
    }
}

@Composable
private fun rememberMirrorHaptics(enabled: Boolean): MirrorHaptics {
    val view = LocalView.current
    return remember(view, enabled) { MirrorHaptics(view, enabled) }
}

/**
 * How the rail fits the height it has. Labelled shows the touch-mode labels; Compact drops them
 * (their semantics still carry the names) so every 48dp target fits a short landscape phone at
 * large font scales. [showName] adds the streaming computer's name under the readout when there's room.
 */
private data class RailLayout(val labelled: Boolean, val showName: Boolean, val edge: Dp)

@Composable
private fun railLayout(available: Dp): RailLayout {
    val type = MaterialTheme.typography
    return with(LocalDensity.current) {
        val label = type.labelMedium.lineHeight.toDp()
        val readoutTop = type.labelLarge.lineHeight.toDp()
        val name = type.labelSmall.lineHeight.toDp()
        val targets = RAIL_TARGET * 3 // close, next mode, menu
        // Labelled: 8dp edges, a divider, two labelled items, a two-line readout, 6 gaps.
        val labelled = RAIL_EDGE * 2 + targets + 1.dp + (RAIL_ITEM_PILL + 8.dp + label) * 2 + readoutTop + label + RAIL_GAP * 6
        // Compact: 4dp edges, no divider, two 48dp items, a smaller readout, 5 gaps.
        val compact = RAIL_EDGE + targets + RAIL_TARGET * 2 + label * 2 + RAIL_GAP * 5
        when {
            available >= labelled -> RailLayout(labelled = true, showName = available >= labelled + name, edge = RAIL_EDGE)
            else -> RailLayout(labelled = false, showName = available >= compact + name, edge = RAIL_EDGE / 2)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MirrorRail(
    state: MirrorUiState,
    haptics: MirrorHaptics,
    onClose: () -> Unit,
    onNextMode: () -> Unit,
    onConfigureModes: () -> Unit,
    onTouchModeChange: (MirrorTouchMode) -> Unit,
    onForgetCompanion: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxHeight().width(88.dp),
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxHeight()
                .windowInsetsPadding(
                    WindowInsets.displayCutout.union(WindowInsets.systemBars)
                        .only(WindowInsetsSides.Start + WindowInsetsSides.Vertical),
                ),
        ) {
            val layout = railLayout(maxHeight)
            // The fit above keeps every target on screen; scrolling is the backstop for a font
            // or display size it didn't foresee.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .padding(vertical = layout.edge),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(RAIL_GAP),
                ) {
                    IconButton(onClick = onClose, modifier = Modifier.testTag("exit_mirror_btn")) {
                        Icon(Icons.Default.Close, contentDescription = "Close Mirror")
                    }
                    NextModeButton(
                        onClick = {
                            haptics.tick()
                            onNextMode()
                        },
                        onLongClick = onConfigureModes,
                    )
                    if (layout.labelled) {
                        HorizontalDivider(
                            modifier = Modifier.width(48.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                    Column(
                        modifier = Modifier.selectableGroup(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(RAIL_GAP),
                    ) {
                        val onSelect: (MirrorTouchMode) -> Unit = { mode ->
                            if (mode != state.touchMode) haptics.tick()
                            onTouchModeChange(mode)
                        }
                        TouchModeOption(
                            mode = MirrorTouchMode.DIRECT,
                            icon = Icons.Filled.TouchApp,
                            label = "Direct",
                            description = "Direct: touch presses, drag drags",
                            selected = state.touchMode == MirrorTouchMode.DIRECT,
                            labelled = layout.labelled,
                            onSelect = onSelect,
                        )
                        TouchModeOption(
                            mode = MirrorTouchMode.HOVER,
                            icon = Icons.Filled.Mouse,
                            label = "Hover",
                            description = "Hover: touch moves the pointer, tap clicks",
                            selected = state.touchMode == MirrorTouchMode.HOVER,
                            labelled = layout.labelled,
                            onSelect = onSelect,
                        )
                    }
                }
                Column(
                    modifier = Modifier.padding(top = RAIL_GAP),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(RAIL_GAP),
                ) {
                    LinkReadout(state, compact = !layout.labelled, showName = layout.showName)
                    MirrorMenu(state, onForgetCompanion)
                }
            }
        }
    }
}

/**
 * The current mode, Mirror, as a button that moves to the next one: outlined with a "▸" so it
 * reads as a step forward, not as a third option beside Direct and Hover.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NextModeButton(onClick: () -> Unit, onLongClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(width = RAIL_ITEM_WIDTH, height = RAIL_TARGET)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClickLabel = "Next input mode",
                onLongClickLabel = "Choose input modes",
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .semantics { contentDescription = "Mirror" }
            .testTag("mirror_mode_cycle_btn"),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .size(width = 56.dp, height = RAIL_ITEM_PILL)
                .clip(MaterialTheme.shapes.large)
                .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.large)
                .indication(interaction, ripple()),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.DesktopWindows,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Icon(
                Icons.AutoMirrored.Filled.ArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * Direct or Hover, as a navigation-rail item: the ripple is clipped to the pill indicator, and the
 * label sits under it only when [labelled].
 */
@Composable
private fun TouchModeOption(
    mode: MirrorTouchMode,
    icon: ImageVector,
    label: String,
    description: String,
    selected: Boolean,
    labelled: Boolean,
    onSelect: (MirrorTouchMode) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val container = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer
    val content = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier
            .width(RAIL_ITEM_WIDTH)
            .heightIn(min = RAIL_TARGET)
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = null,
                role = Role.RadioButton,
                onClick = { onSelect(mode) },
            )
            .semantics { contentDescription = description }
            .testTag("mirror_mode_${mode.name.lowercase()}"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 56.dp, height = RAIL_ITEM_PILL)
                .clip(MaterialTheme.shapes.large)
                .background(container)
                .indication(interaction, ripple()),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(24.dp))
        }
        if (labelled) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
            )
        }
    }
}

/**
 * Frames per second and link latency, the two numbers that say whether the picture is live, and
 * (with [showName]) which computer it comes from. TalkBack reads it once, as one sentence.
 */
@Composable
private fun LinkReadout(state: MirrorUiState, compact: Boolean, showName: Boolean) {
    val phase = state.phase
    val connected = phase as? MirrorPhase.Connected
    val fps = if (connected != null) "${state.fps} fps" else "– fps"
    val latency = state.latencyMs?.takeIf { connected != null }?.let { "$it ms" } ?: "– ms"
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(80.dp)
            .clearAndSetSemantics {
                contentDescription = if (connected != null) {
                    "Streaming from ${connected.companionName}, $fps, link latency $latency"
                } else {
                    "Not streaming"
                }
            }
            .testTag("mirror_link_readout"),
    ) {
        Text(
            fps,
            style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            latency,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (showName && connected != null) {
            Text(
                connected.companionName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("mirror_readout_name"),
            )
        }
    }
}

/** ⋮: which computer this is, then Forget. */
@Composable
private fun MirrorMenu(state: MirrorUiState, onForgetCompanion: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("mirror_menu_btn")) {
            Icon(Icons.Default.MoreVert, contentDescription = "More Mirror options")
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            val paired = state.pairedName
            val connectedTo = (state.phase as? MirrorPhase.Connected)?.companionName
            val status = when {
                connectedTo != null -> "Connected to $connectedTo"
                paired != null -> "Paired with $paired, not connected"
                else -> null
            }
            if (status != null) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .widthIn(max = 280.dp)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .testTag("mirror_menu_status"),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            DropdownMenuItem(
                text = { Text(if (paired != null) "Forget $paired" else "No computer paired") },
                enabled = paired != null,
                onClick = {
                    menuOpen = false
                    onForgetCompanion()
                },
            )
        }
    }
}

/** Where a finger is on the picture, and whether it holds the computer's button down. */
private data class TouchMark(val x: Float, val y: Float, val held: Boolean)

/**
 * The mirrored picture and the touch layer over it; both use the same [MirrorFit]. A ring marks
 * each finger in the same frame it lands, before the computer's next picture can: filled while
 * Direct holds the button, hollow in Hover and while two fingers scroll.
 */
@Composable
private fun MirrorImage(
    frames: MirrorFrameSource,
    live: Boolean,
    touchMode: MirrorTouchMode,
    haptics: MirrorHaptics,
    onTouch: (MirrorTouchFrame) -> Unit,
    onImagePlaced: (FloatArray) -> Unit,
) {
    var surfaceSize by remember { mutableStateOf(IntSize.Zero) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    // Frame size changes rarely (a new display), so reading it here recomposes only then.
    val frameSize = remember { mutableStateOf(IntSize.Zero) }
    val fit: MirrorFit? = frameSize.value.takeIf { it.width > 0 && surfaceSize.width > 0 }?.let {
        fitMirror(it.width, it.height, surfaceSize.width.toFloat(), surfaceSize.height.toFloat(), allowRotation = true)
    }
    val currentFit by rememberUpdatedState(fit)
    val currentLive by rememberUpdatedState(live)
    val currentMode by rememberUpdatedState(touchMode)
    val currentHaptics by rememberUpdatedState(haptics)
    val currentOnTouch by rememberUpdatedState(onTouch)
    // Read only while drawing, so a moving finger redraws the image without recomposing it.
    val marks = remember { mutableStateOf(emptyList<TouchMark>()) }
    LaunchedEffect(fit, origin) {
        fit?.let { onImagePlaced(floatArrayOf(origin.x + it.left, origin.y + it.top, origin.x + it.left + it.width, origin.y + it.top + it.height)) }
    }
    val dim = MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f)
    val ring = MaterialTheme.colorScheme.onSurface
    val ringFill = ring.copy(alpha = 0.35f)
    val halo = MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { surfaceSize = it }
            .onGloballyPositioned { origin = it.positionInWindow() }
            .drawBehind {
                val seq = frames.frameSeq.value
                val image = frames.currentFrame() ?: return@drawBehind
                if (frameSize.value.width != image.width || frameSize.value.height != image.height) {
                    frameSize.value = IntSize(image.width, image.height)
                }
                val drawFit = fitMirror(image.width, image.height, size.width, size.height, allowRotation = true)
                val boxCenter = Offset(drawFit.left + drawFit.width / 2f, drawFit.top + drawFit.height / 2f)
                val imageW = if (drawFit.rotation == MirrorRotation.CLOCKWISE) drawFit.height else drawFit.width
                val imageH = if (drawFit.rotation == MirrorRotation.CLOCKWISE) drawFit.width else drawFit.height
                translate(boxCenter.x, boxCenter.y) {
                    rotate(if (drawFit.rotation == MirrorRotation.CLOCKWISE) 90f else 0f, pivot = Offset.Zero) {
                        drawImage(
                            image = image,
                            dstOffset = IntOffset((-imageW / 2f).toInt(), (-imageH / 2f).toInt()),
                            dstSize = IntSize(imageW.toInt(), imageH.toInt()),
                            filterQuality = FilterQuality.Low,
                        )
                    }
                }
                if (!currentLive) {
                    drawRect(dim)
                } else {
                    val radius = TOUCH_RING_RADIUS.toPx()
                    marks.value.forEach { mark ->
                        val center = Offset(mark.x, mark.y)
                        // The halo keeps the ring readable on a light picture as well as a dark one.
                        drawCircle(halo, radius, center, style = Stroke(4.dp.toPx()))
                        if (mark.held) drawCircle(ringFill, radius, center)
                        drawCircle(ring, radius, center, style = Stroke(2.dp.toPx()))
                    }
                }
                frames.onFrameDrawn(seq)
            }
            .pointerInput(Unit) {
                // The phone's own record of the gesture, for the ring and haptics only; what reaches
                // the computer is MirrorTouchPolicy's call, and the tap rule here matches its defaults.
                val tapSlop = 12.dp.toPx()
                var active = false
                var startedInside = false
                var multi = false
                var downAt = 0L
                var downPosition = Offset.Zero
                var leftSlop = false
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val fitNow = currentFit
                        if (fitNow == null || !currentLive) {
                            active = false
                            marks.value = emptyList()
                            continue
                        }
                        currentOnTouch(
                            MirrorTouchFrame(
                                points = event.changes.map {
                                    MirrorTouchPoint(it.id.value, it.position.x, it.position.y, it.pressed, it.previousPressed)
                                },
                                uptimeMillis = event.changes.firstOrNull()?.uptimeMillis ?: SystemClock.uptimeMillis(),
                                fit = fitNow,
                            ),
                        )
                        event.changes.forEach { it.consume() }

                        val pressed = event.changes.filter { it.pressed }
                        if (!active && pressed.isNotEmpty()) {
                            val first = pressed.first()
                            active = true
                            multi = pressed.size > 1
                            // Two fingers at once scroll wherever they land; one finger counts only inside the image.
                            startedInside = multi || fitNow.toNormalized(first.position.x, first.position.y) != null
                            downAt = first.uptimeMillis
                            downPosition = first.position
                            leftSlop = false
                            if (startedInside && !multi && currentMode == MirrorTouchMode.DIRECT) currentHaptics.press()
                        } else if (active && pressed.isNotEmpty()) {
                            if (pressed.size > 1) multi = true
                            if (hypot(pressed.first().position.x - downPosition.x, pressed.first().position.y - downPosition.y) >= tapSlop) {
                                leftSlop = true
                            }
                        } else if (active) {
                            active = false
                            val upAt = event.changes.firstOrNull()?.uptimeMillis ?: downAt
                            val tapped = startedInside && !multi && !leftSlop && upAt - downAt < HOVER_TAP_TIMEOUT_MILLIS
                            if (tapped && currentMode == MirrorTouchMode.HOVER) currentHaptics.press()
                        }
                        marks.value = if (active && startedInside) {
                            pressed.map {
                                TouchMark(it.position.x, it.position.y, held = !multi && currentMode == MirrorTouchMode.DIRECT)
                            }
                        } else {
                            emptyList()
                        }
                    }
                }
            }
            .semantics { contentDescription = "Mirrored computer screen" }
            .testTag("mirror_image"),
    )
}

/** Everything the surface says when there's no live picture to touch. */
@Composable
private fun MirrorOverlay(state: MirrorUiState, hasStaleFrame: Boolean, modifier: Modifier = Modifier) {
    val phase = state.phase
    // Wider with larger text, so a big font scale wraps less instead of scrolling.
    val maxCardWidth = 440.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
    AnimatedVisibility(
        visible = !phase.isLive,
        enter = fadeIn(tween(FADE_IN_MILLIS)),
        exit = fadeOut(tween(FADE_OUT_MILLIS)),
        modifier = modifier,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier
                    .widthIn(max = maxCardWidth)
                    .heightIn(max = maxHeight)
                    .animateContentSize(tween(RESIZE_MILLIS))
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .testTag("mirror_status_card"),
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatusContent(state, hasStaleFrame)
                }
            }
        }
    }
}

@Composable
private fun StatusContent(state: MirrorUiState, hasStaleFrame: Boolean) {
    when (val phase = state.phase) {
        MirrorPhase.Off -> StatusHeader(icon = null, title = "Starting Mirror", busy = true)
        MirrorPhase.NoWifi -> {
            StatusHeader(Icons.Filled.WifiOff, "Connect to Wi-Fi")
            Body("Mirror reaches your computer over the local network. Join the Wi-Fi your computer is on.")
        }
        is MirrorPhase.Waiting -> when {
            phase.pairedName == null -> {
                StatusHeader(Icons.Filled.DesktopWindows, "Pair a computer")
                Body("On the computer, in a terminal, run:")
                Command("bluke-mirror pair")
                PhoneAddressHint(state)
            }
            // A picture from earlier this session means the link dropped, not that it never came.
            hasStaleFrame -> {
                StatusHeader(icon = null, title = "Lost ${phase.pairedName}", busy = true)
                Body("Reconnecting. If bluke-mirror stopped, run it again.")
            }
            else -> {
                StatusHeader(icon = null, title = "Waiting for ${phase.pairedName}", busy = true)
                Body("Run bluke-mirror on ${phase.pairedName}. It finds this phone on the network.")
                PhoneAddressHint(state)
            }
        }
        is MirrorPhase.Pairing -> {
            StatusHeader(Icons.Filled.Key, "Pair with ${phase.companionName}")
            Body("Type this code into bluke-mirror:")
            Text(
                text = phase.code.chunked(3).joinToString(" "),
                style = MaterialTheme.typography.displayMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Pairing code ${phase.code.toList().joinToString(" ")}" }
                    .testTag("mirror_pairing_code"),
                textAlign = TextAlign.Center,
            )
            Body(
                if (phase.attemptsLeft == 1) "Last try this session." else "${phase.attemptsLeft} tries left this session.",
                muted = true,
            )
        }
        MirrorPhase.PairingLocked -> {
            StatusHeader(Icons.Filled.Lock, "Too many wrong codes")
            Body("Pairing is closed for now. Close Mirror and open it again for a new code.")
        }
        is MirrorPhase.Connected -> {
            StatusHeader(icon = null, title = "Connected to ${phase.companionName}", busy = true)
            Body("Waiting for the first picture.", muted = true)
        }
        is MirrorPhase.Failed -> {
            StatusHeader(Icons.Filled.ErrorOutline, "Mirror can't listen")
            Body(phase.reason)
        }
    }
}

@Composable
private fun StatusHeader(icon: ImageVector?, title: String, busy: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            busy -> CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 3.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            icon != null -> Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading() },
        )
    }
}

@Composable
private fun Body(text: String, muted: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun Command(text: String) {
    SelectionContainer {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun PhoneAddressHint(state: MirrorUiState) {
    val argument = state.phoneArgument ?: return
    Body("If it doesn't find this phone, give it the address:", muted = true)
    Command("--phone $argument")
}

/**
 * A one-line confirmation ("Paired with …", "Wrong code"), shown for a few seconds, or "Computer
 * forgotten" with an Undo while [forgetPending]. Touches pass through it to the picture, except on
 * the Undo button.
 */
@Composable
private fun MirrorNoticePill(
    notice: MirrorNotice?,
    forgetPending: Boolean,
    onShown: () -> Unit,
    onUndoForget: () -> Unit,
) {
    val currentOnShown by rememberUpdatedState(onShown)
    val showFor = noticeMillis(withAction = false)
    if (notice != null) {
        LaunchedEffect(notice) {
            delay(showFor)
            currentOnShown()
        }
    }
    val text = when {
        forgetPending -> "Computer forgotten"
        notice != null -> notice.text()
        else -> null
    }
    // What fades out is what was last shown.
    val last = remember { arrayOf<Pair<String, Boolean>?>(null) }
    if (text != null) last[0] = text to forgetPending
    // Expanding as it fades lets the status card below glide to its new place instead of jumping.
    AnimatedVisibility(
        visible = text != null,
        enter = fadeIn(tween(FADE_IN_MILLIS)) + expandVertically(tween(FADE_IN_MILLIS)),
        exit = fadeOut(tween(FADE_OUT_MILLIS)) + shrinkVertically(tween(FADE_OUT_MILLIS)),
    ) {
        val (shownText, withUndo) = last[0] ?: return@AnimatedVisibility
        Row(
            modifier = Modifier
                .padding(bottom = 8.dp)
                .clip(MaterialTheme.shapes.extraLarge)
                .background(MaterialTheme.colorScheme.inverseSurface)
                .heightIn(min = 40.dp)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag("mirror_notice"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                shownText,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.padding(start = 16.dp, end = if (withUndo) 4.dp else 16.dp, top = 10.dp, bottom = 10.dp),
            )
            if (withUndo) {
                TextButton(
                    onClick = onUndoForget,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.inversePrimary),
                    modifier = Modifier.padding(end = 4.dp).testTag("mirror_undo_forget"),
                ) {
                    Text("Undo")
                }
            }
        }
    }
}

private fun MirrorNotice.text(): String = when (this) {
    is MirrorNotice.Paired -> "Paired with $companionName"
    is MirrorNotice.WrongCode -> when (attemptsLeft) {
        0 -> "Wrong code"
        1 -> "Wrong code. 1 try left."
        else -> "Wrong code. $attemptsLeft tries left."
    }
    MirrorNotice.Forgotten -> "Computer forgotten"
}

/** How long a notice stays: 3 s, longer when accessibility services ask for more time to act. */
@Composable
private fun noticeMillis(withAction: Boolean): Long =
    LocalAccessibilityManager.current?.calculateRecommendedTimeoutMillis(
        NOTICE_MILLIS,
        containsIcons = false,
        containsText = true,
        containsControls = withAction,
    ) ?: NOTICE_MILLIS

private const val NOTICE_MILLIS = 3_000L

/** MirrorTouchPolicy's default tap timeout: a Hover tap shorter than this clicks. */
private const val HOVER_TAP_TIMEOUT_MILLIS = 400L

// Fades for the status card and the notice; Compose scales them by the system animator scale.
private const val FADE_IN_MILLIS = 180
private const val FADE_OUT_MILLIS = 150
private const val RESIZE_MILLIS = 200

private val RAIL_TARGET = 48.dp
private val RAIL_ITEM_WIDTH = 64.dp
private val RAIL_ITEM_PILL = 32.dp
private val RAIL_GAP = 8.dp
private val RAIL_EDGE = 8.dp
private val TOUCH_RING_RADIUS = 22.dp
