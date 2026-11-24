package dev.arnv.bluke.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowRight
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.arnv.bluke.mirror.MirrorPhase
import dev.arnv.bluke.mirror.MirrorTouchMode
import dev.arnv.bluke.mirror.MirrorUiState
import dev.arnv.bluke.mirror.readout

/**
 * The hotkey buttons as the surface shows them: the list, whether Bluetooth can send them now
 * ([enabled]: connected to a host), and that host's name for the copy when it can't.
 */
@Immutable
data class MirrorHotkeysUi(
    val list: List<MirrorHotkey> = emptyList(),
    val enabled: Boolean = false,
    val hostName: String? = null,
)

/**
 * Where the overlay's controls sit in the window. The picture's touch layer tests each finger's
 * landing point against these, so a thumb on a control never reads as a finger on the picture.
 * Written by layout and read by touch, both on the main thread.
 */
@Stable
internal class MirrorControlBounds {
    private val rects = mutableMapOf<Any, Rect>()

    fun place(key: Any, rect: Rect) {
        rects[key] = rect
    }

    fun remove(key: Any) {
        rects -= key
    }

    fun hits(window: Offset): Boolean = rects.values.any { it.contains(window) }
}

/** Records this control's bounds in [bounds] while it's on screen. */
@Composable
private fun Modifier.controlBounds(bounds: MirrorControlBounds, key: Any): Modifier {
    DisposableEffect(bounds, key) { onDispose { bounds.remove(key) } }
    return onGloballyPositioned { bounds.place(key, it.boundsInWindow()) }
}

/**
 * The overlay's colours, from the scheme's roles. Every control floats over the computer's picture,
 * light or dark, so each sits on a translucent [scrim] dark enough for its content to read over
 * white (onSurface on it stays above 4.5:1) and carries an [outline] that shows against a dark one.
 * An active control (the key held, a hotkey down) turns solid primary.
 */
internal object MirrorControlColors {
    val scrim: Color @Composable get() = MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA)
    val outline: Color @Composable get() = MaterialTheme.colorScheme.outline
    val content: Color @Composable get() = MaterialTheme.colorScheme.onSurface
    val active: Color @Composable get() = MaterialTheme.colorScheme.primary
    val onActive: Color @Composable get() = MaterialTheme.colorScheme.onPrimary
    /** M3's disabled content and outline opacities. */
    val disabledContent: Color @Composable get() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    val disabledOutline: Color @Composable get() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)

    private const val SCRIM_ALPHA = 0.64f
}

/** The overlay's width: two columns of targets and the gap between them. */
internal val MIRROR_CONTROLS_WIDTH: Dp get() = CELL * 2 + GAP

/**
 * The insets the overlay keeps clear of on the left: the Back gesture's zone and any cutout,
 * so a thumb resting on a control never starts Back.
 */
internal val MirrorControlsInsets: WindowInsets
    @Composable get() = WindowInsets.systemGestures.union(WindowInsets.displayCutout).only(WindowInsetsSides.Start)

/**
 * The left-hand controls, floating over the picture's left edge in two columns: Close and the next
 * input mode, then the touch mode and the menu, then (Hover only) the left-button key, then the
 * hotkey buttons, which scroll when a short screen can't fit them all. Space between controls is
 * the picture's: only a control's own bounds ([bounds]) take a finger from it.
 */
@Composable
internal fun MirrorControls(
    state: MirrorUiState,
    hotkeys: MirrorHotkeysUi,
    bounds: MirrorControlBounds,
    haptics: MirrorHaptics,
    onClose: () -> Unit,
    onNextMode: () -> Unit,
    onConfigureModes: () -> Unit,
    onTouchModeChange: (MirrorTouchMode) -> Unit,
    onKey: (down: Boolean) -> Unit,
    onHotkey: (MirrorHotkey, down: Boolean) -> Unit,
    onHotkeysUnavailable: () -> Unit,
    onEditHotkeys: () -> Unit,
    onForgetCompanion: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier
            .fillMaxHeight()
            .windowInsetsPadding(MirrorControlsInsets)
            .padding(start = EDGE, top = EDGE, bottom = EDGE),
    ) {
        Column(
            modifier = Modifier.width(MIRROR_CONTROLS_WIDTH).heightIn(max = maxHeight),
            verticalArrangement = Arrangement.spacedBy(GAP),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
                CloseCell(bounds, onClose)
                NextModeCell(bounds, haptics, onNextMode, onConfigureModes)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
                TouchModeCell(state.touchMode, bounds) {
                    haptics.tick()
                    onTouchModeChange(if (state.touchMode == MirrorTouchMode.DIRECT) MirrorTouchMode.HOVER else MirrorTouchMode.DIRECT)
                }
                MenuCell(state, bounds, onEditHotkeys, onForgetCompanion)
            }
            if (state.touchMode == MirrorTouchMode.HOVER) LeftButtonKey(state.keyHeld, bounds, onKey)
            HotkeyGrid(hotkeys, bounds, onHotkey, onHotkeysUnavailable, onEditHotkeys, Modifier.weight(1f, fill = false))
        }
    }
}

/** One control's look: translucent, outlined, solid primary while [active]. */
@Composable
private fun ControlFace(
    modifier: Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
    shape: Shape = MaterialTheme.shapes.medium,
    content: @Composable () -> Unit,
) {
    val container = if (active) MirrorControlColors.active else MirrorControlColors.scrim
    val outline = when {
        active -> MirrorControlColors.active
        enabled -> MirrorControlColors.outline
        else -> MirrorControlColors.disabledOutline
    }
    val contentColor = when {
        active -> MirrorControlColors.onActive
        enabled -> MirrorControlColors.content
        else -> MirrorControlColors.disabledContent
    }
    Box(
        modifier = modifier.clip(shape).background(container).border(1.dp, outline, shape),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor, content = content)
    }
}

@Composable
private fun CloseCell(bounds: MirrorControlBounds, onClose: () -> Unit) {
    ControlFace(
        Modifier
            .size(CELL)
            .controlBounds(bounds, "close")
            .clickable(role = Role.Button, onClick = onClose)
            .semantics(mergeDescendants = true) { contentDescription = "Close Mirror" }
            .testTag("exit_mirror_btn"),
    ) {
        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(ICON))
    }
}

/**
 * The current mode, Mirror, as a button that moves to the next input mode: Mirror's screen-share
 * glyph with an arrow, so it reads as a step forward. A long press chooses the modes.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NextModeCell(bounds: MirrorControlBounds, haptics: MirrorHaptics, onNextMode: () -> Unit, onConfigureModes: () -> Unit) {
    ControlFace(
        Modifier
            .size(CELL)
            .controlBounds(bounds, "next_mode")
            .combinedClickable(
                role = Role.Button,
                onClickLabel = "Next input mode",
                onLongClickLabel = "Choose input modes",
                onClick = {
                    haptics.tick()
                    onNextMode()
                },
                onLongClick = onConfigureModes,
            )
            .testTag("mirror_mode_cycle_btn")
            .clearAndSetSemantics {
                contentDescription = "Mirror"
                role = Role.Button
                onClick("Next input mode") {
                    onNextMode()
                    true
                }
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.ScreenShare, contentDescription = null, modifier = Modifier.size(18.dp))
            Icon(Icons.AutoMirrored.Filled.ArrowRight, contentDescription = null, modifier = Modifier.size(16.dp))
        }
    }
}

/**
 * Direct or Hover, one button that switches between them. It shows the current mode by icon and,
 * when the label fits the cell at this font scale, by name; TalkBack reads the mode and what it does.
 */
@Composable
private fun TouchModeCell(mode: MirrorTouchMode, bounds: MirrorControlBounds, onToggle: () -> Unit) {
    val (icon, label, meaning) = when (mode) {
        MirrorTouchMode.DIRECT -> Triple(Icons.Filled.TouchApp, "Direct", "touch presses, drag drags")
        MirrorTouchMode.HOVER -> Triple(Icons.Filled.Mouse, "Hover", "touch moves the pointer, tap clicks")
    }
    val other = if (mode == MirrorTouchMode.DIRECT) "Hover" else "Direct"
    val style = MaterialTheme.typography.labelSmall
    val labelFits = fitsInCell(label, style, maxLines = 1, height = CELL - ICON_SMALL - 8.dp)
    ControlFace(
        Modifier
            .size(CELL)
            .controlBounds(bounds, "touch_mode")
            .clickable(role = Role.Button, onClick = onToggle)
            .testTag("mirror_touch_mode_btn")
            .clearAndSetSemantics {
                contentDescription = "Touch mode: $label, $meaning"
                role = Role.Button
                onClick("Switch to $other") {
                    onToggle()
                    true
                }
            },
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(if (labelFits) ICON_SMALL else ICON))
            if (labelFits) Text(label, style = style, maxLines = 1)
        }
    }
}

/** ⋮: which computer this is and how its link is doing, Edit hotkey buttons, then Forget. */
@Composable
private fun MenuCell(
    state: MirrorUiState,
    bounds: MirrorControlBounds,
    onEditHotkeys: () -> Unit,
    onForgetCompanion: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        ControlFace(
            Modifier
                .size(CELL)
                .controlBounds(bounds, "menu")
                .clickable(role = Role.Button, onClick = { menuOpen = true })
                .semantics(mergeDescendants = true) { contentDescription = "More Mirror options" }
                .testTag("mirror_menu_btn"),
        ) {
            Icon(Icons.Default.MoreVert, contentDescription = null, modifier = Modifier.size(ICON))
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            val paired = state.pairedName
            val connected = state.phase as? MirrorPhase.Connected
            val status = when {
                connected?.displayName != null ->
                    "Mirror is connected to ${connected.companionName}, showing ${connected.displayName}"
                connected != null -> "Mirror is connected to ${connected.companionName}"
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
            }
            if (connected != null) {
                val readout = state.readout()
                Text(
                    text = "${readout.rate} · ${readout.latency}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 8.dp)
                        .clearAndSetSemantics { contentDescription = readout.description }
                        .testTag("mirror_link_readout"),
                )
            }
            if (status != null || connected != null) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DropdownMenuItem(
                text = { Text("Edit hotkey buttons") },
                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onEditHotkeys()
                },
                modifier = Modifier.testTag("mirror_edit_hotkeys"),
            )
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

/**
 * Press and hold: the computer's left button goes down at the pointer and stays down until the
 * thumb lifts. [held] is the controller's word, not the thumb's: a press before any point was sent
 * (or after the link dropped) does nothing and never reads as held. TalkBack's double tap toggles it.
 */
@Composable
private fun LeftButtonKey(held: Boolean, bounds: MirrorControlBounds, onKey: (down: Boolean) -> Unit) {
    val currentOnKey by rememberUpdatedState(onKey)
    ControlFace(
        Modifier
            .size(width = MIRROR_CONTROLS_WIDTH, height = KEY_HEIGHT)
            .controlBounds(bounds, "left_button")
            .holdGesture { down -> currentOnKey(down) }
            .testTag("mirror_left_button")
            .clearAndSetSemantics {
                contentDescription = "Left mouse button"
                stateDescription = if (held) "Held" else "Released"
                role = Role.Button
                onClick(if (held) "Release" else "Press and hold") {
                    currentOnKey(!held)
                    true
                }
            },
        active = held,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Mouse, contentDescription = null, modifier = Modifier.size(ICON))
            val label = if (held) "Held" else "Hold to click"
            val style = MaterialTheme.typography.labelMedium
            if (fitsInCell(label, style, maxLines = 1, width = MIRROR_CONTROLS_WIDTH - 8.dp, height = KEY_HEIGHT - ICON - 12.dp)) {
                Text(label, style = style, maxLines = 1)
            }
        }
    }
}

/**
 * The hotkey buttons, two to a row, scrolling when there are more than the height left holds. With
 * none yet, one + button opens the editor. While Bluetooth can't send them they read disabled, and a
 * touch says which host they need.
 */
@Composable
private fun HotkeyGrid(
    hotkeys: MirrorHotkeysUi,
    bounds: MirrorControlBounds,
    onHotkey: (MirrorHotkey, Boolean) -> Unit,
    onUnavailable: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier,
) {
    if (hotkeys.list.isEmpty()) {
        ControlFace(
            Modifier
                .size(CELL)
                .controlBounds(bounds, "add_hotkey")
                .clickable(role = Role.Button, onClick = onEdit)
                .semantics(mergeDescendants = true) { contentDescription = "Add a hotkey button" }
                .testTag("mirror_add_hotkey"),
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(ICON))
        }
        return
    }
    val scroll = rememberScrollState()
    Column(
        modifier = modifier
            .scrollCue(scroll, MaterialTheme.colorScheme.onSurfaceVariant)
            .verticalScroll(scroll)
            .testTag("mirror_hotkeys"),
        verticalArrangement = Arrangement.spacedBy(GAP),
    ) {
        hotkeys.list.chunked(2).forEachIndexed { row, pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
                pair.forEachIndexed { column, hotkey ->
                    HotkeyCell(hotkey, row * 2 + column, hotkeys.enabled, bounds, onHotkey, onUnavailable)
                }
            }
        }
    }
}

@Composable
private fun HotkeyCell(
    hotkey: MirrorHotkey,
    index: Int,
    enabled: Boolean,
    bounds: MirrorControlBounds,
    onHotkey: (MirrorHotkey, Boolean) -> Unit,
    onUnavailable: () -> Unit,
) {
    var down by remember { mutableStateOf(false) }
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnHotkey by rememberUpdatedState(onHotkey)
    val currentOnUnavailable by rememberUpdatedState(onUnavailable)
    val label = hotkey.displayLabel
    val style = hotkeyLabelStyle(label)
    ControlFace(
        Modifier
            .size(CELL)
            // The index too: two buttons may send the same key.
            .controlBounds(bounds, "hotkey_$index")
            .holdGesture { pressed ->
                when {
                    !currentEnabled -> if (pressed) currentOnUnavailable()
                    pressed -> {
                        down = true
                        currentOnHotkey(hotkey, true)
                    }
                    down -> {
                        down = false
                        currentOnHotkey(hotkey, false)
                    }
                }
            }
            .testTag("mirror_hotkey_$index")
            .clearAndSetSemantics {
                contentDescription = hotkey.spokenLabel
                role = Role.Button
                if (!enabled) disabled()
                onClick {
                    if (currentEnabled) {
                        currentOnHotkey(hotkey, true)
                        currentOnHotkey(hotkey, false)
                    } else {
                        currentOnUnavailable()
                    }
                    true
                }
            },
        active = down && enabled,
        enabled = enabled,
    ) {
        Text(
            label,
            style = style.first,
            maxLines = style.second,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

/** The biggest type a hotkey's label fits a cell in: a key name large, a word smaller, two lines at most. */
@Composable
private fun hotkeyLabelStyle(label: String): Pair<TextStyle, Int> {
    val type = MaterialTheme.typography
    return listOf(type.titleMedium to 1, type.labelLarge to 1, type.labelSmall to 2)
        .firstOrNull { (style, lines) -> fitsInCell(label, style, lines) }
        ?: (type.labelSmall to 2)
}

/** Whether [text] fits a cell's inner area in [style] and [maxLines] at the current font scale. */
@Composable
private fun fitsInCell(text: String, style: TextStyle, maxLines: Int, width: Dp = CELL - 6.dp, height: Dp = CELL - 6.dp): Boolean {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(text, style, maxLines, width, height, density) {
        with(density) {
            val result = measurer.measure(text, style, maxLines = maxLines, constraints = Constraints(maxWidth = width.roundToPx()))
            !result.hasVisualOverflow && result.size.height <= height.roundToPx()
        }
    }
}

/**
 * Reports a press as it lands ([onPress] true) and its end (false) once every finger on it has
 * lifted or the system took the touch, so holding it holds. A touch that leaves the screen ends too.
 */
private fun Modifier.holdGesture(onPress: (Boolean) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false).consume()
        onPress(true)
        try {
            do {
                val event = awaitPointerEvent()
                event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        } finally {
            onPress(false)
        }
    }
}

private val CELL = 48.dp
private val GAP = 8.dp
private val EDGE = 8.dp
private val KEY_HEIGHT = 72.dp
private val ICON = 24.dp
private val ICON_SMALL = 20.dp
