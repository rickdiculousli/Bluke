package dev.arnv.bluke.ui

import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowRight
import androidx.compose.material.icons.automirrored.filled.ScreenShare
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.pointerInteropFilter
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
import androidx.compose.ui.text.rememberTextMeasurer
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
import dev.arnv.bluke.mirror.MirrorTouchEvent
import dev.arnv.bluke.mirror.MirrorTouchFrame
import dev.arnv.bluke.mirror.MirrorTouchMode
import dev.arnv.bluke.mirror.MirrorTouchPoint
import dev.arnv.bluke.mirror.MirrorTouchUpdate
import dev.arnv.bluke.mirror.MirrorUiState
import dev.arnv.bluke.mirror.fitMirror
import dev.arnv.bluke.mirror.isLive
import dev.arnv.bluke.mirror.pairingRequest
import dev.arnv.bluke.mirror.pictureStale
import dev.arnv.bluke.mirror.readout
import kotlinx.coroutines.delay

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
        // The notice with the Undo already said Mirror forgot it; don't say it twice.
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
        onDeclinePairing = controller::declinePairing,
        onImagePlaced = { controller.imageBoundsInWindow = it },
        hapticsEnabled = hapticsEnabled,
        forgetPending = forgetPending,
        onUndoForget = { forgetPending = false },
    )
}

/**
 * The whole surface, drawn from [state] alone. [hapticsEnabled] is the app's haptics preference;
 * [forgetPending] shows "Mirror forgot …" with an Undo while the forget waits out its window.
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
    onTouch: (MirrorTouchFrame) -> MirrorTouchUpdate,
    modifier: Modifier = Modifier,
    onImagePlaced: (FloatArray) -> Unit = {},
    hapticsEnabled: Boolean = false,
    forgetPending: Boolean = false,
    onUndoForget: () -> Unit = {},
    onDeclinePairing: () -> Unit = {},
) {
    val haptics = rememberMirrorHaptics(hapticsEnabled)
    LaunchedEffect(state.notice) {
        when (state.notice) {
            is MirrorNotice.WrongCode -> haptics.reject()
            is MirrorNotice.Paired -> haptics.confirm()
            else -> Unit
        }
    }
    // The status card that spoke for each phase leaves when the picture arrives; say so once.
    val view = LocalView.current
    val liveFrom = (state.phase as? MirrorPhase.Connected)?.takeIf { it.hasFrame }?.companionName
    LaunchedEffect(liveFrom) {
        @Suppress("DEPRECATION") // No live region fits a one-off message about a card that just left.
        if (liveFrom != null) view.announceForAccessibility("Streaming from $liveFrom")
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
                stale = state.pictureStale,
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
                    pairedName = state.pairedName,
                    onShown = onNoticeShown,
                    onUndoForget = onUndoForget,
                )
                QuietLinkChip(state)
                MirrorOverlay(
                    state = state,
                    hasStaleFrame = frames.currentFrame() != null,
                    onDeclinePairing = onDeclinePairing,
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

    /** Pairing succeeded. */
    fun confirm() = perform(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
        else HapticFeedbackConstants.VIRTUAL_KEY,
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

/** Which Direct/Hover labels the rail has room for. The semantics carry both names either way. */
private enum class ModeLabels { BOTH, SELECTED, NONE }

/**
 * How the rail fits the height it has: the richest of these that fits, so the current mode's word
 * and the streaming computer's name stay on screen at font scales 1.0 and 1.3 on a 360dp-high phone.
 * [largeFps] sets the frame rate in labelLarge when there's room for it.
 */
private data class RailLayout(val modeLabels: ModeLabels, val showName: Boolean, val largeFps: Boolean)

@Composable
private fun railLayout(available: Dp): RailLayout {
    val type = MaterialTheme.typography
    return with(LocalDensity.current) {
        val label = type.labelMedium.lineHeight.toDp()
        val large = type.labelLarge.lineHeight.toDp()
        val name = type.labelSmall.lineHeight.toDp()
        val labelledItem = RAIL_ITEM_PILL + 8.dp + label
        // Close, next mode and the menu, three gaps in the top group, one each side of the readout.
        val fixed = RAIL_TARGET * 3 + RAIL_GAP * 5
        listOf(
            RailLayout(ModeLabels.BOTH, showName = true, largeFps = true) to
                fixed + labelledItem * 2 + large + label + name,
            RailLayout(ModeLabels.SELECTED, showName = true, largeFps = false) to
                fixed + labelledItem + RAIL_TARGET + label * 2 + name,
            RailLayout(ModeLabels.SELECTED, showName = false, largeFps = false) to
                fixed + labelledItem + RAIL_TARGET + label * 2,
        ).firstOrNull { (_, height) -> height <= available }?.first
            ?: RailLayout(ModeLabels.NONE, showName = false, largeFps = false)
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
    // 88dp, wider only when large text needs it for a three-digit latency.
    val measurer = rememberTextMeasurer()
    val readoutStyle = MaterialTheme.typography.labelMedium
    val density = LocalDensity.current
    val railWidth = remember(measurer, readoutStyle, density) {
        val widest = with(density) { measurer.measure(WIDEST_LATENCY, readoutStyle).size.width.toDp() }
        maxOf(RAIL_WIDTH, widest + 16.dp)
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxHeight().width(railWidth),
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
                    .heightIn(min = maxHeight),
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
                        railWidth = railWidth,
                        onClick = {
                            haptics.tick()
                            onNextMode()
                        },
                        onLongClick = onConfigureModes,
                    )
                    Column(
                        modifier = Modifier.selectableGroup(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(RAIL_GAP),
                    ) {
                        val onSelect: (MirrorTouchMode) -> Unit = { mode ->
                            if (mode != state.touchMode) haptics.tick()
                            onTouchModeChange(mode)
                        }
                        listOf(
                            Triple(MirrorTouchMode.DIRECT, Icons.Filled.TouchApp, "Direct" to "Direct: touch presses, drag drags"),
                            Triple(MirrorTouchMode.HOVER, Icons.Filled.Mouse, "Hover" to "Hover: touch moves the pointer, tap clicks"),
                        ).forEach { (mode, icon, words) ->
                            val selected = state.touchMode == mode
                            TouchModeOption(
                                mode = mode,
                                icon = icon,
                                label = words.first,
                                description = words.second,
                                selected = selected,
                                labelled = when (layout.modeLabels) {
                                    ModeLabels.BOTH -> true
                                    ModeLabels.SELECTED -> selected
                                    ModeLabels.NONE -> false
                                },
                                onSelect = onSelect,
                            )
                        }
                    }
                }
                Column(
                    modifier = Modifier.padding(top = RAIL_GAP),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(RAIL_GAP),
                ) {
                    LinkReadout(state, largeFps = layout.largeFps, showName = layout.showName)
                    MirrorMenu(state, onForgetCompanion)
                }
            }
        }
    }
}

/**
 * The current mode, Mirror, as a button that moves to the next one: an outlined "Mirror ▸" so it
 * reads as a step forward, not as a third option beside Direct and Hover. When the word doesn't fit
 * the rail (large font scales), Mirror's own screen-share glyph stands in for it (not the desktop
 * glyph that heads "Pair a computer").
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NextModeButton(railWidth: Dp, onClick: () -> Unit, onLongClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val style = MaterialTheme.typography.labelMedium
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val wordFits = remember(measurer, style, density, railWidth) {
        val word = with(density) { measurer.measure(MIRROR_WORD, style).size.width.toDp() }
        NEXT_PILL_START + word + NEXT_ARROW + NEXT_PILL_END <= railWidth - 16.dp
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RAIL_TARGET)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClickLabel = "Next input mode",
                onLongClickLabel = "Choose input modes",
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .semantics { contentDescription = MIRROR_WORD }
            .testTag("mirror_mode_cycle_btn"),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = RAIL_ITEM_PILL)
                .widthIn(min = 56.dp)
                .clip(MaterialTheme.shapes.large)
                .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.large)
                .indication(interaction, ripple())
                .padding(start = if (wordFits) NEXT_PILL_START else 12.dp, end = NEXT_PILL_END),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (wordFits) {
                Text(MIRROR_WORD, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            } else {
                Icon(
                    Icons.AutoMirrored.Filled.ScreenShare,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.ArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(NEXT_ARROW),
            )
        }
    }
}

/**
 * Direct or Hover, as a navigation-rail item: the ripple is clipped to the pill indicator, and the
 * label sits under it while [labelled], growing in when the selection brings it.
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
            .fillMaxWidth()
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
        AnimatedVisibility(
            visible = labelled,
            enter = fadeIn(EffectsSpring) + expandVertically(SpatialSizeSpring),
            exit = fadeOut(EffectsSpring) + shrinkVertically(SpatialSizeSpring),
        ) {
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
private fun LinkReadout(state: MirrorUiState, largeFps: Boolean, showName: Boolean) {
    val connected = state.phase as? MirrorPhase.Connected
    val readout = state.readout()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp)
            .clearAndSetSemantics { contentDescription = readout.description }
            .testTag("mirror_link_readout"),
    ) {
        Text(
            readout.rate,
            style = if (largeFps) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            readout.latency,
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
                modifier = Modifier.padding(horizontal = 4.dp).testTag("mirror_readout_name"),
            )
        }
    }
}

/** ⋮: which computer this is, then Forget it, which touches only Mirror's pairing. */
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
                connectedTo != null -> "Mirror is connected to $connectedTo"
                paired != null -> "Mirror is paired with $paired, not connected"
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
                text = {
                    if (paired != null) {
                        Column(Modifier.widthIn(max = 256.dp)) {
                            Text("Forget $paired", maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                "Bluetooth pairing isn't touched",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Text("No computer paired")
                    }
                },
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
 * each finger the touch policy is acting on, in the same frame it lands, before the computer's next
 * picture can: filled while Direct holds the button, hollow in Hover and while two fingers scroll.
 * It's wider than a fingertip so it shows around a resting finger, and fades briefly after the lift
 * so a quick tap still shows. A [stale] picture (the link went quiet) dims lightly.
 *
 * Touches arrive as raw MotionEvents: a down asks Android for unbuffered dispatch, so moves reach
 * the computer as they happen instead of batched to the next frame, and a system cancel is told
 * apart from a lift.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun MirrorImage(
    frames: MirrorFrameSource,
    live: Boolean,
    stale: Boolean,
    haptics: MirrorHaptics,
    onTouch: (MirrorTouchFrame) -> MirrorTouchUpdate,
    onImagePlaced: (FloatArray) -> Unit,
) {
    val view = LocalView.current
    var surfaceSize by remember { mutableStateOf(IntSize.Zero) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    // Frame size changes rarely (a new display), so reading it here recomposes only then.
    val frameSize = remember { mutableStateOf(IntSize.Zero) }
    val fit: MirrorFit? = frameSize.value.takeIf { it.width > 0 && surfaceSize.width > 0 }?.let {
        fitMirror(it.width, it.height, surfaceSize.width.toFloat(), surfaceSize.height.toFloat(), allowRotation = true)
    }
    val currentFit by rememberUpdatedState(fit)
    val currentLive by rememberUpdatedState(live)
    val currentHaptics by rememberUpdatedState(haptics)
    val currentOnTouch by rememberUpdatedState(onTouch)
    // Read only while drawing, so a moving finger redraws the image without recomposing it.
    val marks = remember { mutableStateOf(emptyList<TouchMark>()) }
    // The marks a lift left behind, fading; a new touch replaces them at once.
    val lifted = remember { mutableStateOf(emptyList<TouchMark>()) }
    val liftAlpha = remember { Animatable(0f) }
    val lifts = remember { mutableIntStateOf(0) }
    LaunchedEffect(lifts.intValue) {
        if (lifts.intValue == 0) return@LaunchedEffect
        liftAlpha.snapTo(1f)
        liftAlpha.animateTo(0f, FastEffectsSpring)
    }
    // The stale picture dims as the status card fades in over it, and clears as it fades out; a
    // live picture whose link went quiet dims lightly, since it may be frozen.
    val dimAlpha = animateFloatAsState(
        when {
            !live -> DIM_ALPHA
            stale -> STALE_DIM_ALPHA
            else -> 0f
        },
        EffectsSpring,
        label = "mirrorDim",
    )
    LaunchedEffect(fit, origin) {
        fit?.let { onImagePlaced(floatArrayOf(origin.x + it.left, origin.y + it.top, origin.x + it.left + it.width, origin.y + it.top + it.height)) }
    }
    val scrim = MaterialTheme.colorScheme.scrim
    val ring = MaterialTheme.colorScheme.onSurface
    val ringFill = ring.copy(alpha = 0.35f)
    val halo = MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { surfaceSize = it }
            .onGloballyPositioned { origin = it.positionInWindow() }
            // A drag that reaches the screen's edge moves the picture's pointer, not Back.
            .then(if (live) Modifier.systemGestureExclusion() else Modifier)
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
                dimAlpha.value.takeIf { it > 0f }?.let { drawRect(scrim.copy(alpha = it)) }
                if (currentLive) {
                    val radius = TOUCH_RING_RADIUS.toPx()
                    val (shown, alpha) = marks.value.takeIf { it.isNotEmpty() }?.let { it to 1f }
                        ?: (lifted.value to liftAlpha.value)
                    if (alpha > 0f) {
                        shown.forEach { mark ->
                            val center = Offset(mark.x, mark.y)
                            // The halo keeps the ring readable on a light picture as well as a dark one.
                            drawCircle(halo, radius, center, alpha = alpha, style = Stroke(4.dp.toPx()))
                            if (mark.held) drawCircle(ringFill, radius, center, alpha = alpha)
                            drawCircle(ring, radius, center, alpha = alpha, style = Stroke(2.dp.toPx()))
                        }
                    }
                }
                frames.onFrameDrawn(seq)
            }
            .pointerInteropFilter { event ->
                val action = event.actionMasked
                // Moves as they arrive, not batched to the next frame: the computer sees them sooner.
                if (action == MotionEvent.ACTION_DOWN) view.requestUnbufferedDispatch(event)
                val fitNow = currentFit
                if (fitNow == null || !currentLive) {
                    marks.value = emptyList()
                    return@pointerInteropFilter true
                }
                // What the computer gets, the ring and the haptics all come from the touch policy's one record.
                val update = currentOnTouch(event.toMirrorTouchFrame(fitNow))
                update.events.forEach {
                    if (it == MirrorTouchEvent.PRESSED || it == MirrorTouchEvent.TAPPED) currentHaptics.press()
                }
                val next = update.rings.map { TouchMark(it.x, it.y, update.held) }
                if (next.isEmpty() && marks.value.isNotEmpty()) {
                    lifted.value = marks.value
                    lifts.intValue++
                }
                marks.value = next
                true
            }
            .semantics { contentDescription = "Mirrored computer screen" }
            .testTag("mirror_image"),
    )
}

/** Every pointer of [this] event, in the image layer's pixels; a lifting pointer reads as up. */
private fun MotionEvent.toMirrorTouchFrame(fit: MirrorFit): MirrorTouchFrame {
    val action = actionMasked
    val changing = actionIndex
    val lifting = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP
    val landing = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN
    val cancelled = action == MotionEvent.ACTION_CANCEL
    return MirrorTouchFrame(
        points = (0 until pointerCount).map { i ->
            MirrorTouchPoint(
                id = getPointerId(i).toLong(),
                x = getX(i),
                y = getY(i),
                down = !cancelled && !(lifting && i == changing),
                wasDown = !(landing && i == changing),
            )
        },
        uptimeMillis = eventTime,
        fit = fit,
        cancelled = cancelled,
        eventNanos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) eventTimeNanos else eventTime * 1_000_000,
    )
}

/**
 * While a live picture's link is quiet (no reply for a couple of seconds), a chip over it says so:
 * the picture may be frozen, and the session ends on its own if the link doesn't come back.
 */
@Composable
private fun QuietLinkChip(state: MirrorUiState) {
    val name = (state.phase as? MirrorPhase.Connected)?.companionName
    AnimatedVisibility(
        visible = state.pictureStale,
        enter = fadeIn(EffectsSpring) + expandVertically(SpatialSizeSpring),
        exit = fadeOut(EffectsSpring) + shrinkVertically(SpatialSizeSpring),
    ) {
        Row(
            modifier = Modifier
                .padding(bottom = 8.dp)
                .clip(MaterialTheme.shapes.extraLarge)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .heightIn(min = 40.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag("mirror_quiet_link"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${name ?: "The computer"} isn't answering",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Everything the surface says when there's no live picture to touch, and a pairing code that
 * arrives while one is streaming (shown over the picture, so the request is never hidden).
 */
@Composable
private fun MirrorOverlay(
    state: MirrorUiState,
    hasStaleFrame: Boolean,
    onDeclinePairing: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val phase = state.phase
    // Wider with larger text, so a big font scale wraps less instead of scrolling.
    val maxCardWidth = 440.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
    AnimatedVisibility(
        visible = !phase.isLive || phase.pairingRequest != null,
        enter = fadeIn(EffectsSpring),
        exit = fadeOut(EffectsSpring),
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
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .testTag("mirror_status_card"),
            ) {
                // The card resizes on a spring while the old words fade out and the new ones in.
                AnimatedContent(
                    targetState = StatusShown(state, hasStaleFrame),
                    contentKey = { it.key },
                    transitionSpec = {
                        (fadeIn(EffectsSpring) togetherWith fadeOut(EffectsSpring))
                            .using(SizeTransform(clip = true) { _, _ -> SpatialSizeSpring })
                    },
                    contentAlignment = Alignment.Center,
                    label = "mirrorStatus",
                ) { shown ->
                    val scroll = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .scrollEdgeFade(scroll)
                            .verticalScroll(scroll)
                            .padding(horizontal = 24.dp, vertical = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StatusContent(shown.state, shown.hasStaleFrame, onDeclinePairing)
                    }
                }
            }
        }
    }
}

/** What the card shows; [key] changes only when the card says something different. */
private data class StatusShown(val state: MirrorUiState, val hasStaleFrame: Boolean) {
    val key: Any
        get() = when (val phase = state.phase.pairingRequest ?: state.phase) {
            is MirrorPhase.Waiting -> when {
                phase.pairedName == null -> "unpaired"
                hasStaleFrame -> "lost ${phase.pairedName}"
                else -> "waiting ${phase.pairedName}"
            }
            is MirrorPhase.Pairing -> "pairing ${phase.code}"
            is MirrorPhase.Connected -> "connected ${phase.companionName}"
            else -> phase
        }
}

@Composable
private fun StatusContent(state: MirrorUiState, hasStaleFrame: Boolean, onDeclinePairing: () -> Unit) {
    when (val phase = state.phase.pairingRequest ?: state.phase) {
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
                PhoneAddressHint(state, "bluke-mirror pair")
                Body("No bluke-mirror yet? It's in the companion folder of Bluke's source; its README says how to build it.", muted = true)
            }
            // A picture from earlier this session means the link dropped, not that it never came.
            hasStaleFrame -> {
                StatusHeader(icon = null, title = "Lost ${phase.pairedName}", busy = true)
                Body("Reconnecting. If bluke-mirror stopped, run it again.")
            }
            else -> {
                StatusHeader(icon = null, title = "Waiting for ${phase.pairedName}", busy = true)
                Body("Run bluke-mirror on ${phase.pairedName}. It finds this phone on the network.")
                PhoneAddressHint(state, "bluke-mirror")
            }
        }
        is MirrorPhase.Pairing -> {
            StatusHeader(Icons.Filled.Key, "${phase.companionName} wants to pair")
            Body("If you started this, type the code into bluke-mirror there:")
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
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (phase.attemptsLeft == 1) "Last try this session." else "${phase.attemptsLeft} tries left this session.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                // Not yours? Turning it down costs no try, so a stranger on the network can't use them up.
                TextButton(onClick = onDeclinePairing, modifier = Modifier.testTag("mirror_pairing_decline")) {
                    Text("Decline")
                }
            }
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
            StatusHeader(Icons.Filled.ErrorOutline, "Mirror couldn't start")
            Body("Close Mirror and open it again. If it keeps happening, restart the phone.")
            Body(phase.reason, muted = true)
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

/** The whole command with this phone's address, for when [command] alone doesn't find the phone. */
@Composable
private fun PhoneAddressHint(state: MirrorUiState, command: String) {
    val argument = state.phoneArgument ?: return
    Body("If it doesn't find this phone, run this instead:", muted = true)
    Command("$command --phone $argument")
}

/**
 * Fades the edges [scroll] can still move toward, so content that runs past a card's edge
 * (large text on a short screen) shows there's more.
 */
private fun Modifier.scrollEdgeFade(scroll: ScrollState): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val edge = (SCROLL_FADE.toPx() / size.height).coerceAtMost(0.5f)
        val top = if (scroll.canScrollBackward) Color.Transparent else Color.Black
        val bottom = if (scroll.canScrollForward) Color.Transparent else Color.Black
        if (top == Color.Black && bottom == Color.Black) return@drawWithContent
        drawRect(
            Brush.verticalGradient(0f to top, edge to Color.Black, 1f - edge to Color.Black, 1f to bottom),
            blendMode = BlendMode.DstIn,
        )
    }

/**
 * A one-line confirmation ("Paired with …", "Wrong code"), shown for a few seconds, or "Mirror
 * forgot [pairedName]" with an Undo while [forgetPending]. Touches pass through it to the picture, except on
 * the Undo button.
 */
@Composable
private fun MirrorNoticePill(
    notice: MirrorNotice?,
    forgetPending: Boolean,
    pairedName: String?,
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
        forgetPending -> pairedName?.let { "Mirror forgot $it" } ?: FORGOTTEN_TEXT
        notice != null -> notice.text()
        else -> null
    }
    // What fades out is what was last shown.
    val last = remember { arrayOf<Pair<String, Boolean>?>(null) }
    if (text != null) last[0] = text to forgetPending
    // Expanding as it fades lets the status card below glide to its new place instead of jumping.
    AnimatedVisibility(
        visible = text != null,
        enter = fadeIn(EffectsSpring) + expandVertically(SpatialSizeSpring),
        exit = fadeOut(EffectsSpring) + shrinkVertically(SpatialSizeSpring),
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).padding(start = 16.dp, end = if (withUndo) 4.dp else 16.dp, top = 10.dp, bottom = 10.dp),
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
    MirrorNotice.Forgotten -> FORGOTTEN_TEXT
    is MirrorNotice.Declined -> "Declined $companionName"
}

private const val FORGOTTEN_TEXT = "Mirror pairing removed"

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

// Springs from M3's standard motion scheme: effects for fades, spatial for size. Compose scales
// them by the system animator scale and snaps them when animations are off.
private val EffectsSpring: FiniteAnimationSpec<Float> = spring(dampingRatio = 1f, stiffness = 1600f)
private val FastEffectsSpring: FiniteAnimationSpec<Float> = spring(dampingRatio = 1f, stiffness = 3800f)
private val SpatialSizeSpring: FiniteAnimationSpec<IntSize> =
    spring(dampingRatio = 0.9f, stiffness = 700f, visibilityThreshold = IntSize.VisibilityThreshold)

private const val DIM_ALPHA = 0.6f
private const val STALE_DIM_ALPHA = 0.3f
private const val MIRROR_WORD = "Mirror"
private const val WIDEST_LATENCY = "999 ms"

private val RAIL_WIDTH = 88.dp
private val RAIL_TARGET = 48.dp
private val RAIL_ITEM_PILL = 32.dp
private val RAIL_GAP = 8.dp
private val NEXT_PILL_START = 12.dp
private val NEXT_PILL_END = 4.dp
private val NEXT_ARROW = 18.dp
private val TOUCH_RING_RADIUS = 35.dp
private val SCROLL_FADE = 24.dp
