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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowRight
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AdsClick
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LinkOff
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.arnv.bluke.mirror.MirrorPhase
import dev.arnv.bluke.mirror.MirrorTouchMode
import dev.arnv.bluke.mirror.MirrorUiState
import dev.arnv.bluke.mirror.isLive
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

/**
 * Records this control's bounds in [bounds] while it's on screen, widened by half the gap on each
 * side, so a thumb that misses a control by a little lands on the overlay, never clicks the picture.
 */
@Composable
private fun Modifier.controlBounds(bounds: MirrorControlBounds, key: Any): Modifier {
    DisposableEffect(bounds, key) { onDispose { bounds.remove(key) } }
    val slack = with(LocalDensity.current) { (GAP / 2).toPx() }
    return onGloballyPositioned { bounds.place(key, it.boundsInWindow().inflate(slack)) }
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
    val activeEdge: Color @Composable get() = MaterialTheme.colorScheme.scrim
    /**
     * Disabled content a step above M3's 0.38, since it sits on a translucent scrim over a picture
     * that may be light, and M3's disabled outline opacity.
     */
    val disabledContent: Color @Composable get() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    val disabledOutline: Color @Composable get() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)

    private const val SCRIM_ALPHA = 0.64f
}

/** The overlay's width: two columns of targets and the gap between them. */
internal val MIRROR_CONTROLS_WIDTH: Dp get() = CELL * 2 + GAP

/**
 * The insets the overlay keeps clear of: on the left the Back gesture's zone and any cutout, so a
 * thumb resting on a control never starts Back, and at the bottom the Home gesture's zone, so the
 * last row of hotkeys never sits on the gesture bar.
 */
internal val MirrorControlsInsets: WindowInsets
    @Composable get() = WindowInsets.systemGestures.union(WindowInsets.displayCutout).only(WindowInsetsSides.Start)
        .union(WindowInsets.systemGestures.only(WindowInsetsSides.Bottom))

/**
 * The left-hand controls, floating over the picture's left edge in two columns: Close and the next
 * input mode, then the touch mode and the menu, then the left-button key's slot (the key in Hover,
 * the picture in Direct, so the hotkeys sit in the same place in both), then the hotkey buttons,
 * in pages when the height left can't hold them all. A finger that lands on a control, or within
 * half a gap of one ([bounds]), is the overlay's; everywhere else is the picture's.
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
            .padding(start = EDGE, top = EDGE_VERTICAL, bottom = EDGE_VERTICAL),
    ) {
        // Rows of hotkeys that fit under the fixed controls: three on a 360dp-high phone.
        val hotkeyRows = ((maxHeight - FIXED_HEIGHT + GAP) / (CELL + GAP)).toInt().coerceAtLeast(0)
        val live = state.phase.isLive
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
                MenuCell(state, hotkeys, bounds, onEditHotkeys, onForgetCompanion)
            }
            if (state.touchMode == MirrorTouchMode.HOVER) {
                LeftButtonKey(state.keyHeld, enabled = live, bounds, onKey)
            } else if (hotkeys.list.isNotEmpty()) {
                // Held for the key, so no hotkey moves when the mode changes.
                Spacer(Modifier.height(KEY_HEIGHT))
            }
            HotkeyGrid(hotkeys, hotkeyRows, bounds, haptics, onHotkey, onHotkeysUnavailable, onEditHotkeys)
        }
    }
}

/**
 * One control's look: translucent, outlined, solid primary while [active]. [modifier] sizes and
 * places it; [input] (its clicks, semantics and tag) goes inside the clip, so the press ripple
 * takes the control's shape.
 */
@Composable
private fun ControlFace(
    modifier: Modifier,
    input: Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
    shape: Shape = MaterialTheme.shapes.medium,
    content: @Composable () -> Unit,
) {
    val container = if (active) MirrorControlColors.active else MirrorControlColors.scrim
    val outline = when {
        // A dark edge round the solid fill, so a held control keeps its shape over a light picture.
        active -> MirrorControlColors.activeEdge
        enabled -> MirrorControlColors.outline
        else -> MirrorControlColors.disabledOutline
    }
    val contentColor = when {
        active -> MirrorControlColors.onActive
        enabled -> MirrorControlColors.content
        else -> MirrorControlColors.disabledContent
    }
    Box(
        modifier = modifier.clip(shape).then(input).background(container).border(1.dp, outline, shape),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor, content = content)
    }
}

@Composable
private fun CloseCell(bounds: MirrorControlBounds, onClose: () -> Unit) {
    ControlFace(
        Modifier.size(CELL).controlBounds(bounds, "close"),
        input = Modifier
            .clickable(role = Role.Button, onClick = onClose)
            .semantics(mergeDescendants = true) { contentDescription = "Close Mirror" }
            .testTag("exit_mirror_btn"),
    ) {
        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(ICON))
    }
}

/**
 * The current mode, Mirror, as a button that moves to the next input mode: Mirror's screen-share
 * glyph with an arrow, so it reads as a step forward, and its name when it fits. A long press
 * chooses the modes.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NextModeCell(bounds: MirrorControlBounds, haptics: MirrorHaptics, onNextMode: () -> Unit, onConfigureModes: () -> Unit) {
    // The long press's haptic is Mirror's own, so it honours the haptics setting: the system's is muted here.
    CompositionLocalProvider(LocalHapticFeedback provides NoSystemHaptics) {
        ControlFace(
            Modifier.size(CELL).controlBounds(bounds, "next_mode"),
            input = Modifier
                .combinedClickable(
                    role = Role.Button,
                    onClickLabel = "Next input mode",
                    onLongClickLabel = "Choose input modes",
                    onClick = {
                        haptics.tick()
                        onNextMode()
                    },
                    onLongClick = {
                        haptics.tick()
                        onConfigureModes()
                    },
                )
                .testTag("mirror_mode_cycle_btn")
                .clearAndSetSemantics {
                    contentDescription = "Mirror"
                    role = Role.Button
                    onClick("Next input mode") {
                        onNextMode()
                        true
                    }
                    onLongClick("Choose input modes") {
                        onConfigureModes()
                        true
                    }
                },
        ) {
            GlyphAndLabel("Mirror") { size ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.ScreenShare, contentDescription = null, modifier = Modifier.size(size))
                    Icon(Icons.AutoMirrored.Filled.ArrowRight, contentDescription = null, modifier = Modifier.size(size - 4.dp))
                }
            }
        }
    }
}

/** Haptics that do nothing, for a control whose feedback goes through [MirrorHaptics] instead. */
private object NoSystemHaptics : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) = Unit
}

/**
 * Direct or Hover, one button that switches between them. It shows the current mode by icon and
 * name; TalkBack reads the mode and what it does.
 */
@Composable
private fun TouchModeCell(mode: MirrorTouchMode, bounds: MirrorControlBounds, onToggle: () -> Unit) {
    val (icon, label, meaning) = when (mode) {
        MirrorTouchMode.DIRECT -> Triple(Icons.Filled.TouchApp, "Direct", "touch presses, drag drags")
        MirrorTouchMode.HOVER -> Triple(Icons.Filled.Mouse, "Hover", "touch moves the pointer, tap clicks")
    }
    val other = if (mode == MirrorTouchMode.DIRECT) "Hover" else "Direct"
    ControlFace(
        Modifier.size(CELL).controlBounds(bounds, "touch_mode"),
        input = Modifier
            .clickable(role = Role.Button, onClick = onToggle)
            .testTag("mirror_touch_mode_btn")
            .clearAndSetSemantics {
                contentDescription = "Touch mode"
                stateDescription = "$label, $meaning"
                role = Role.Button
                onClick("Switch to $other") {
                    onToggle()
                    true
                }
            },
    ) {
        GlyphAndLabel(label) { size -> Icon(icon, contentDescription = null, modifier = Modifier.size(size)) }
    }
}

/**
 * A cell's glyph (drawn at the size it's given) with its one-word name under it, set tight and held
 * at its font-scale-1.3 size so the name still fits at any scale; where it can't, [short] if there
 * is one, else the glyph alone, at full size.
 */
@Composable
private fun GlyphAndLabel(label: String, short: String? = null, glyph: @Composable (size: Dp) -> Unit) {
    val style = CellLabelStyle
    val shown = listOfNotNull(label, short).firstOrNull { fitsInCell(it, style, maxLines = 1, width = CELL - 4.dp, height = CELL - ICON_SMALL - 6.dp) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (shown != null) {
            glyph(ICON_SMALL)
            Text(shown, style = style, maxLines = 1)
        } else {
            glyph(ICON)
        }
    }
}

/** labelSmall without its letter spacing and with its line as tall as its type, held like a key cap's print. */
private val CellLabelStyle: TextStyle
    @Composable get() = MaterialTheme.typography.labelSmall.let { it.copy(letterSpacing = 0.sp, lineHeight = it.fontSize) }.heldAtFaceScale()

/**
 * A control's print is part of the control, like its glyph: it grows with the font scale up to
 * [FACE_MAX_FONT_SCALE], then holds, so a 48dp cell still names itself at 2.0.
 */
@Composable
private fun TextStyle.heldAtFaceScale(): TextStyle {
    val fontScale = LocalDensity.current.fontScale
    if (fontScale <= FACE_MAX_FONT_SCALE) return this
    val hold = FACE_MAX_FONT_SCALE / fontScale
    return copy(fontSize = fontSize * hold, lineHeight = lineHeight * hold)
}

/**
 * ⋮: which computer this is and how its link is doing, which Bluetooth host the hotkeys go to,
 * Edit hotkey buttons, then Forget.
 */
@Composable
private fun MenuCell(
    state: MirrorUiState,
    hotkeys: MirrorHotkeysUi,
    bounds: MirrorControlBounds,
    onEditHotkeys: () -> Unit,
    onForgetCompanion: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        // While a picture is live, the link's latency stays in view on the menu's own cell.
        val live = (state.phase as? MirrorPhase.Connected)?.hasFrame == true && state.latencyMs != null
        val readout = state.readout()
        ControlFace(
            Modifier.size(CELL).controlBounds(bounds, "menu"),
            input = Modifier
                .clickable(role = Role.Button, onClick = { menuOpen = true })
                .semantics(mergeDescendants = true) {
                    contentDescription = "More Mirror options"
                    if (live) stateDescription = readout.description
                }
                .testTag("mirror_menu_btn"),
        ) {
            if (live) {
                // "128 ms" where it fits, else the figure alone; TalkBack reads it in full.
                GlyphAndLabel(readout.latency, short = state.latencyMs?.toString()) { size ->
                    Icon(Icons.Default.MoreVert, contentDescription = null, modifier = Modifier.size(size))
                }
            } else {
                Icon(Icons.Default.MoreVert, contentDescription = null, modifier = Modifier.size(ICON))
            }
        }
        // Opens beside the column, level with its cell, so it covers the picture rather than the controls.
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            offset = DpOffset(CELL + GAP, -CELL),
        ) {
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
            Text(
                text = hotkeys.hostName?.takeIf { hotkeys.enabled }?.let { "Hotkeys go to $it over Bluetooth" }
                    ?: "Connect Bluetooth to ${hotkeys.hostName ?: "a host"} to use hotkeys",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 8.dp)
                    .testTag("mirror_menu_hotkeys_host"),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
                leadingIcon = { Icon(Icons.Default.LinkOff, contentDescription = null) },
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
 * (or after the link dropped) does nothing and never reads as held. While Mirror has no live link
 * it reads disabled. TalkBack's double tap toggles it.
 */
@Composable
private fun LeftButtonKey(held: Boolean, enabled: Boolean, bounds: MirrorControlBounds, onKey: (down: Boolean) -> Unit) {
    val currentOnKey by rememberUpdatedState(onKey)
    ControlFace(
        Modifier.size(width = MIRROR_CONTROLS_WIDTH, height = KEY_HEIGHT).controlBounds(bounds, "left_button"),
        input = Modifier
            .holdGesture { down -> currentOnKey(down) }
            .testTag("mirror_left_button")
            .clearAndSetSemantics {
                contentDescription = "Left mouse button"
                stateDescription = if (held) "Held" else "Released"
                role = Role.Button
                if (!enabled) disabled()
                onClick(if (held) "Release" else "Press and hold") {
                    currentOnKey(!held)
                    true
                }
            },
        active = held,
        enabled = enabled,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.AdsClick, contentDescription = null, modifier = Modifier.size(ICON))
            val label = if (held) "Held" else "Hold to click"
            val style = MaterialTheme.typography.labelMedium.heldAtFaceScale()
            if (fitsInCell(label, style, maxLines = 1, width = MIRROR_CONTROLS_WIDTH - 8.dp, height = KEY_HEIGHT - ICON - 12.dp)) {
                Text(label, style = style, maxLines = 1)
            }
        }
    }
}

/**
 * The hotkey buttons, two to a row, in the [rows] the height left holds. When there are more than
 * that, the last cell pages through them, and it stays in that cell on every page, so no button
 * moves under the thumb. With none yet, one + button opens the editor. While Bluetooth can't send
 * them they read disabled, and a touch says which host they need.
 */
@Composable
private fun HotkeyGrid(
    hotkeys: MirrorHotkeysUi,
    rows: Int,
    bounds: MirrorControlBounds,
    haptics: MirrorHaptics,
    onHotkey: (MirrorHotkey, Boolean) -> Unit,
    onUnavailable: () -> Unit,
    onEdit: () -> Unit,
) {
    val list = hotkeys.list
    if (list.isEmpty()) {
        if (rows > 0) AddHotkeyCell(bounds, onEdit)
        return
    }
    val slots = rows * 2
    val paged = list.size > slots && slots >= 2
    val perPage = if (paged) slots - 1 else slots
    val pages = if (paged) (list.size + perPage - 1) / perPage else 1
    var page by remember { mutableIntStateOf(0) }
    val shown = page.coerceIn(0, pages - 1)
    val first = shown * perPage
    // Each slot holds a hotkey's index in the list, or null for an empty slot or the pager.
    val indices = (first until minOf(first + perPage, list.size)).toList()
    val cells: List<Int?> = if (paged) indices + List(perPage - indices.size) { null } + PAGER else indices
    Column(Modifier.testTag("mirror_hotkeys"), verticalArrangement = Arrangement.spacedBy(GAP)) {
        cells.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
                pair.forEach { index ->
                    when (index) {
                        PAGER -> PageCell(shown, pages, bounds) {
                            haptics.tick()
                            page = (shown + 1) % pages
                        }
                        null -> Spacer(Modifier.size(CELL))
                        // Keyed by the button too, so a cell never sends the key it held before an edit.
                        else -> key(index, list[index]) {
                            HotkeyCell(list[index], index, hotkeys.enabled, hotkeys.hostName, bounds, onHotkey, onUnavailable)
                        }
                    }
                }
            }
        }
    }
}

/** The pager's place in a page's cells: not an index any list has. */
private const val PAGER = -1

@Composable
private fun AddHotkeyCell(bounds: MirrorControlBounds, onEdit: () -> Unit) {
    ControlFace(
        Modifier.size(CELL).controlBounds(bounds, "add_hotkey"),
        input = Modifier
            .clickable(role = Role.Button, onClick = onEdit)
            .semantics(mergeDescendants = true) { contentDescription = "Add a hotkey button" }
            .testTag("mirror_add_hotkey"),
    ) {
        GlyphAndLabel("Add") { size -> Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(size)) }
    }
}

/** The next page of hotkeys: which page this is, and a chevron down to the next. */
@Composable
private fun PageCell(page: Int, pages: Int, bounds: MirrorControlBounds, onNext: () -> Unit) {
    ControlFace(
        Modifier.size(CELL).controlBounds(bounds, "hotkey_pager"),
        input = Modifier
            .clickable(role = Role.Button, onClickLabel = "Show the next hotkeys", onClick = onNext)
            .testTag("mirror_hotkey_pager")
            .clearAndSetSemantics {
                contentDescription = "More hotkeys"
                stateDescription = "Page ${page + 1} of $pages"
                role = Role.Button
                onClick("Show the next hotkeys") {
                    onNext()
                    true
                }
            },
    ) {
        GlyphAndLabel("${page + 1}/$pages") { size ->
            // Down to the next page; up from the last, which goes back to the first.
            Icon(if (page == pages - 1) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, modifier = Modifier.size(size))
        }
    }
}

@Composable
private fun HotkeyCell(
    hotkey: MirrorHotkey,
    index: Int,
    enabled: Boolean,
    hostName: String?,
    bounds: MirrorControlBounds,
    onHotkey: (MirrorHotkey, Boolean) -> Unit,
    onUnavailable: () -> Unit,
) {
    var down by remember { mutableStateOf(false) }
    val currentHotkey by rememberUpdatedState(hotkey)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnHotkey by rememberUpdatedState(onHotkey)
    val currentOnUnavailable by rememberUpdatedState(onUnavailable)
    val label = hotkey.displayLabel
    val style = hotkeyLabelStyle(label)
    ControlFace(
        // The index too: two buttons may send the same key.
        Modifier.size(CELL).controlBounds(bounds, "hotkey_$index"),
        input = Modifier
            .holdGesture { pressed ->
                when {
                    // A lift ends what its press started, even if Bluetooth dropped in between.
                    !pressed -> if (down) {
                        down = false
                        if (currentEnabled) currentOnHotkey(currentHotkey, false)
                    }
                    !currentEnabled -> currentOnUnavailable()
                    else -> {
                        down = true
                        currentOnHotkey(currentHotkey, true)
                    }
                }
            }
            .testTag("mirror_hotkey_$index")
            .clearAndSetSemantics {
                contentDescription = hotkey.spokenLabel
                role = Role.Button
                if (!enabled) {
                    disabled()
                    // TalkBack can't activate a disabled button, so the reason rides on its state.
                    stateDescription = "Needs Bluetooth connected to ${hostName ?: "a host"}"
                }
                onClick {
                    if (currentEnabled) {
                        currentOnHotkey(currentHotkey, true)
                        currentOnHotkey(currentHotkey, false)
                    } else {
                        currentOnUnavailable()
                    }
                    true
                }
            },
        active = down && enabled,
        enabled = enabled,
    ) {
        HotkeyText(
            label,
            style = style.first,
            maxLines = style.second,
            textAlign = TextAlign.Center,
            breakable = true,
            modifier = Modifier.padding(horizontal = LABEL_INSET),
        )
    }
}

/** A hotkey's face as the surface draws it, for showing a button off the surface (the editor's list). */
@Composable
internal fun MirrorHotkeyFace(label: String) {
    val style = hotkeyLabelStyle(label)
    // A picture of the button, not a control: the row it sits in says what it is.
    ControlFace(Modifier.size(CELL), input = Modifier.clearAndSetSemantics {}) {
        HotkeyText(
            label,
            style = style.first,
            maxLines = style.second,
            textAlign = TextAlign.Center,
            breakable = true,
            modifier = Modifier.padding(horizontal = LABEL_INSET),
        )
    }
}

/** The biggest type a hotkey's label fits a cell in: a key name large, a word smaller, two lines at most. */
@Composable
private fun hotkeyLabelStyle(label: String): Pair<TextStyle, Int> {
    val type = MaterialTheme.typography
    val choices = listOf(type.titleMedium.heldAtFaceScale() to 1, type.labelLarge.heldAtFaceScale() to 1, type.labelSmall.heldAtFaceScale() to 2)
    return choices.firstOrNull { (style, lines) -> fitsInCell(label, style, lines) } ?: choices.last()
}

/** The font scale a control's print stops growing at. */
private const val FACE_MAX_FONT_SCALE = 1.3f

/** Whether [text] fits a cell's inner area in [style] and [maxLines] at the current font scale. */
@Composable
private fun fitsInCell(text: String, style: TextStyle, maxLines: Int, width: Dp = CELL - LABEL_INSET * 2 - 2.dp, height: Dp = CELL - 6.dp): Boolean {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(text, style, maxLines, width, height, density) {
        with(density) {
            // Measured as drawn: a modifier glyph takes its icon's room, not the fallback font's.
            val drawn = hotkeyLabelText(text, breakable = true)
            val result = measurer.measure(
                text = drawn,
                style = style,
                maxLines = maxLines,
                placeholders = glyphPlaceholders(drawn),
                constraints = Constraints(maxWidth = width.roundToPx()),
            )
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
/** Above and below the column: half a step, so a 360dp-high phone holds three rows of hotkeys. */
private val EDGE_VERTICAL = 4.dp
private val KEY_HEIGHT = 72.dp
/** The two rows of fixed controls and the key's slot, each with the gap under it. */
private val FIXED_HEIGHT = CELL * 2 + KEY_HEIGHT + GAP * 3
private val ICON = 24.dp
private val ICON_SMALL = 18.dp
/** A hotkey label's space from the cell's outline on each side. */
private val LABEL_INSET = 4.dp
