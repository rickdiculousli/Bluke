package dev.arnv.bluke.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.arnv.bluke.ui.theme.SurfaceTokens

/** What the toolbar's host chip says about the computer Bluke is sending to. */
enum class SurfaceHostStatus { Connected, Connecting, Offline, Blocked }

/**
 * The host as an input surface shows it. The call site builds it from the connection state; the
 * surface only renders it. [onReconnect] is set when tapping the chip can reconnect to [name].
 */
@Immutable
data class SurfaceHost(
    val status: SurfaceHostStatus,
    val name: String?,
    val onReconnect: (() -> Unit)? = null,
    /** For [SurfaceHostStatus.Blocked]: the cause in a few words ("Bluetooth off"), shown instead of a generic line. */
    val reason: String? = null,
)

/** What every surface's toolbar needs from the host screen: its mode, its host, and how to leave or switch. */
@Immutable
class SurfaceChrome(
    val mode: InputMode,
    val host: SurfaceHost,
    val onClose: () -> Unit,
    val onNextMode: () -> Unit,
    /** Long-press on the mode button: Settings at its input-modes section. Null offers no long-press. */
    val onModeLongPress: (() -> Unit)? = null,
)

/** The toolbar's height. Every control in it is a 48dp target. */
val SurfaceToolbarHeight = 48.dp

/**
 * The toolbar every input surface shares: Close, the mode button, the host chip, the surface's own
 * two or three most-used [controls], then Tune. It is quiet chrome on the surface's container role, so the
 * surface below stays the one primary area.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SurfaceToolbar(
    chrome: SurfaceChrome,
    haptics: SurfaceHaptics,
    onTune: () -> Unit,
    modifier: Modifier = Modifier,
    controls: @Composable RowScope.() -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    // On a narrow phone the mode button keeps only its icon, so the host chip keeps its words.
    val compact = LocalConfiguration.current.screenWidthDp < 720
    SubcomposeLayout(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surfaceContainerLow)
            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
            .height(SurfaceToolbarHeight)
            // No outer padding: Close and Tune draw at 40dp inside their 48dp targets, so they already
            // sit 4dp in from the edges, and the room goes to the 8dp gaps between targets.
            .testTag("surface_toolbar"),
    ) { constraints ->
        val height = constraints.maxHeight
        fun measure(slot: Any, maxWidth: Int = constraints.maxWidth, content: @Composable () -> Unit): Placeable =
            subcompose(slot, content).single().measure(Constraints(maxWidth = maxWidth, maxHeight = height))
        // Material's 8dp between 48dp targets, so a thumb aimed at one never lands on its neighbour.
        val gap = SurfaceToolbarTargetGap.roundToPx()
        val close = measure("close") {
            IconButton(onClick = chrome.onClose, modifier = Modifier.testTag("surface_close")) {
                Icon(Icons.Default.Close, contentDescription = "Close ${chrome.mode.displayName.lowercase()}", tint = colors.onSurfaceVariant)
            }
        }
        val mode = measure("mode") { ModeButton(chrome, haptics, compact) }
        val tune = measure("tune") {
            IconButton(onClick = onTune, modifier = Modifier.testTag("surface_tune")) {
                Icon(Icons.Default.Tune, contentDescription = "Tune ${chrome.mode.displayName.lowercase()}", tint = colors.onSurfaceVariant)
            }
        }
        // Room the host chip and the surface's controls share.
        val shared = constraints.maxWidth - close.width - mode.width - tune.width - 3 * gap

        // The chip's floors: its dot and status words plus the first few characters of the name.
        // Probes are measured, never placed, and carry no semantics.
        fun floor(nameChars: Int) = measure("floor$nameChars") {
            HostChip(chrome.host, haptics, compact, probeNameChars = nameChars)
        }.width
        val measuredControls = HashMap<SurfaceToolbarRoom, Placeable>()
        fun controlsAt(room: SurfaceToolbarRoom) = measuredControls.getOrPut(room) {
            measure(room) {
                CompositionLocalProvider(LocalSurfaceToolbarRoom provides room, LocalSurfaceToolbarHaptics provides haptics) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(SurfaceToolbarTargetGap),
                        content = controls,
                    )
                }
            }
        }
        fun hostRoom(controls: Placeable?) =
            if (controls == null || controls.width == 0) shared else shared - controls.width - gap

        // What gives way, in order: the controls step down (all LEDs, unlit ones as initials, lit LEDs
        // only, no LEDs) before
        // the host's name drops under eight characters; they step down again, then leave the toolbar,
        // before the chip's status words cut. With no room even for those, the chip keeps its dot.
        val nameFloor = floor(HostNameFloorChars)
        val statusFloor = floor(0)
        val rooms = SurfaceToolbarRoom.entries
        val room: SurfaceToolbarRoom? = rooms.firstOrNull { hostRoom(controlsAt(it)) >= nameFloor }
            ?: rooms.firstOrNull { hostRoom(controlsAt(it)) >= statusFloor }
            ?: rooms.last().takeIf { shared < statusFloor && hostRoom(controlsAt(it)) >= 48.dp.roundToPx() }
        val controls = room?.let(::controlsAt)
        // A name cut to a bare ellipsis says nothing: under two characters' room the chip shows its status alone.
        val withName = hostRoom(controls) >= floor(2)
        val host = measure("host", maxWidth = hostRoom(controls).coerceAtLeast(0)) {
            HostChip(chrome.host, haptics, compact, withName = withName)
        }

        layout(constraints.maxWidth, height) {
            fun Placeable.placeAt(x: Int) = place(x, (height - this.height) / 2)
            close.placeAt(0)
            mode.placeAt(close.width + gap)
            host.placeAt(close.width + mode.width + 2 * gap)
            val tuneX = constraints.maxWidth - tune.width
            tune.placeAt(tuneX)
            controls?.placeAt(tuneX - gap - controls.width)
        }
    }
}

/**
 * How much room the toolbar gives the surface's own controls, the widest that still leaves the host
 * chip its floor. [SurfaceToolbarLeds] and [SurfaceToolbarToggles] read it; other controls keep their size.
 */
internal enum class SurfaceToolbarRoom { Full, UnlitInitials, LitOnly, NoLeds }

internal val LocalSurfaceToolbarRoom = staticCompositionLocalOf { SurfaceToolbarRoom.Full }

/** The toolbar's haptics, so every toggle in its controls ticks the same way without each call site asking. */
internal val LocalSurfaceToolbarHaptics = staticCompositionLocalOf<SurfaceHaptics?> { null }

/** A toolbar toggle's tap: a switch for TalkBack, and one [HapticEvent.TICK] each time it flips. */
@Composable
private fun Modifier.toolbarToggle(on: Boolean, onToggle: () -> Unit): Modifier {
    val haptics = LocalSurfaceToolbarHaptics.current
    return toggleable(value = on, role = Role.Switch, onValueChange = {
        haptics?.perform(HapticEvent.TICK)
        onToggle()
    })
}

/** The space between two of the toolbar's 48dp targets. */
internal val SurfaceToolbarTargetGap = 8.dp

/** The host's name keeps at least this many characters before the controls stop giving way. */
internal const val HostNameFloorChars = 8

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModeButton(chrome: SurfaceChrome, haptics: SurfaceHaptics, compact: Boolean) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .heightIn(min = 40.dp)
            .clip(CircleShape)
            .background(colors.secondaryContainer)
            .combinedClickable(
                role = Role.Button,
                onClickLabel = "Next input mode",
                onLongClickLabel = chrome.onModeLongPress?.let { "Choose input modes" },
                onClick = {
                    haptics.perform(HapticEvent.MODE_SWITCH)
                    chrome.onNextMode()
                },
                onLongClick = chrome.onModeLongPress,
            )
            .padding(start = 12.dp, end = 8.dp)
            .testTag("surface_mode"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(chrome.mode.icon, contentDescription = null, tint = colors.onSecondaryContainer, modifier = Modifier.size(18.dp))
        if (compact) {
            // The label still reaches TalkBack.
            Box(Modifier.semantics { contentDescription = chrome.mode.displayName })
        } else {
            Text(chrome.mode.displayName, style = MaterialTheme.typography.labelLarge, color = colors.onSecondaryContainer, maxLines = 1)
        }
        Icon(Icons.AutoMirrored.Filled.ArrowRight, contentDescription = null, tint = colors.onSecondaryContainer, modifier = Modifier.size(18.dp))
    }
}

/**
 * The words a [SurfaceHost] shows; pure so a test can read every status. [status] words never
 * truncate; only [name], the host's own name, ellipsizes when it's long.
 */
internal data class HostChipContent(
    val status: String?,
    val name: String?,
    val action: String?,
    val description: String,
) {
    val label: String get() = listOfNotNull(status, name).joinToString(" ")
}

/** [withName] false leaves the name out of the words shown where they'd carry it beside status words; TalkBack still hears it. */
internal fun hostChipContent(host: SurfaceHost, compact: Boolean = false, withName: Boolean = true): HostChipContent {
    val name = host.name?.takeIf { it.isNotBlank() }
    val shownName = name.takeIf { withName }
    return when (host.status) {
        SurfaceHostStatus.Connected -> HostChipContent(
            status = if (name == null) "Connected" else null,
            name = name,
            action = null,
            description = "Connected to ${name ?: "host"}",
        )
        SurfaceHostStatus.Connecting -> HostChipContent(
            // On a narrow phone "to" would cost the name its floor; the ring dot already says "on the way".
            status = if (shownName == null) "Connecting…" else if (compact) "Connecting ·" else "Connecting to",
            name = shownName,
            action = null,
            description = "Connecting to ${name ?: "host"}",
        )
        SurfaceHostStatus.Offline -> HostChipContent(
            status = if (name == null) "No host" else null,
            name = name,
            action = if (host.onReconnect != null) "Reconnect" else null,
            description = if (name != null) "Offline. Last host $name" else "Offline. No host paired",
        )
        // The home card offers the fix; the surface closes once the connection is Blocked.
        SurfaceHostStatus.Blocked -> HostChipContent(
            status = host.reason ?: "Bluetooth unavailable",
            name = null,
            action = null,
            description = host.reason ?: "Bluetooth unavailable",
        )
    }
}

/**
 * Sees each host chip text's layout as it's drawn, by role ("status", "name"); a test reads it to
 * prove nothing it must show was cut.
 */
internal val LocalHostChipTextLayout = staticCompositionLocalOf<(role: String, TextLayoutResult) -> Unit> { { _, _ -> } }

/**
 * The host chip. With [probeNameChars] set it is a measuring probe the toolbar never places: the
 * name cut to that many characters (none at zero, when there are status words), and no semantics or tap.
 */
@Composable
private fun HostChip(host: SurfaceHost, haptics: SurfaceHaptics, compact: Boolean, probeNameChars: Int? = null, withName: Boolean = true) {
    val colors = MaterialTheme.colorScheme
    val content = hostChipContent(host, compact, withName).let { full ->
        when {
            probeNameChars == null || full.name == null -> full
            // At zero the probe is the status words alone; a name that is the status keeps a hint of itself.
            probeNameChars == 0 && full.status != null -> full.copy(name = null)
            full.name.length <= maxOf(probeNameChars, 2) + 1 -> full
            else -> full.copy(name = full.name.take(maxOf(probeNameChars, 2)) + "…")
        }
    }
    val observe = LocalHostChipTextLayout.current.takeIf { probeNameChars == null } ?: { _, _ -> }
    // Offline, a tap reconnects.
    val tap = host.onReconnect?.takeIf { host.status == SurfaceHostStatus.Offline && content.action != null }
    val shape = CircleShape
    var chip = Modifier
        .minimumInteractiveComponentSize()
        .heightIn(min = 40.dp)
        .widthIn(min = 48.dp)
        .clip(shape)
        .border(1.dp, colors.outlineVariant, shape)
    chip = when {
        probeNameChars != null -> chip.clearAndSetSemantics {}
        tap != null -> chip.semantics {
            liveRegion = LiveRegionMode.Polite
            if (host.status == SurfaceHostStatus.Offline) stateDescription = "Offline"
        }.clickable(
            onClickLabel = content.action,
            role = Role.Button,
            onClick = {
                haptics.perform(HapticEvent.PRESS)
                tap()
            },
        ).testTag("surface_host")
        // The tag goes first: clearAndSetSemantics drops what comes after it.
        else -> chip.testTag("surface_host").clearAndSetSemantics {
            liveRegion = LiveRegionMode.Polite
            contentDescription = content.description
        }
    }
    Row(
        modifier = chip.padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HostDot(host.status)
        val textColor = if (host.status == SurfaceHostStatus.Connected) colors.onSurface else colors.onSurfaceVariant
        // What gives way, in order: the host's name, then status words; the action never does.
        Row(
            modifier = Modifier.weight(1f, fill = false),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (content.status != null) {
                Text(
                    content.status,
                    style = MaterialTheme.typography.labelLarge,
                    color = textColor,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { observe("status", it) },
                    modifier = if (content.name == null) Modifier.weight(1f, fill = false) else Modifier,
                )
            }
            if (content.name != null) {
                Text(
                    text = content.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = textColor,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { observe("name", it) },
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
        if (content.action != null) {
            Icon(Icons.Default.Refresh, contentDescription = null, tint = colors.primary, modifier = Modifier.size(if (compact) 20.dp else 16.dp))
            // On a narrow phone the icon alone is the action, so the name keeps the room; TalkBack still hears it.
            if (!compact) Text(content.action, style = MaterialTheme.typography.labelLarge, color = colors.primary, maxLines = 1)
        }
    }
}

@Composable
private fun HostDot(status: SurfaceHostStatus) {
    val colors = MaterialTheme.colorScheme
    val dot = Modifier.size(10.dp).clip(CircleShape)
    when (status) {
        SurfaceHostStatus.Connected -> Box(dot.background(SurfaceTokens.LedLit))
        // A ring, not a fill: on the way, not there yet.
        SurfaceHostStatus.Connecting -> Box(dot.border(2.dp, colors.tertiary, CircleShape))
        SurfaceHostStatus.Offline -> Box(dot.border(2.dp, colors.outline, CircleShape))
        SurfaceHostStatus.Blocked -> Box(dot.background(colors.error))
    }
}

/** An icon-only toolbar control: a 48dp target with a label for TalkBack. [selected] tints it as on. */
@Composable
fun SurfaceToolbarIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean? = null,
) {
    val colors = MaterialTheme.colorScheme
    val on = selected == true
    val target = modifier
        .minimumInteractiveComponentSize()
        .size(40.dp)
        .clip(CircleShape)
        .background(if (on) colors.secondaryContainer else Color.Transparent)
    val clickable = if (selected == null) {
        target.clickable(role = Role.Button, onClick = onClick)
    } else {
        target.toolbarToggle(on, onClick)
    }
    Box(
        clickable.semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (on) colors.onSecondaryContainer else colors.onSurfaceVariant, modifier = Modifier.size(22.dp))
    }
}

/**
 * Indicator LEDs with a label each, in one 40dp group (the keyboard's lock lights, the touchpad's
 * numpad). Each LED is its own 48dp-wide toggle; [onToggle] gets the index tapped. When the toolbar
 * is short of room for the host chip, unlit LEDs shrink to their label's first letter, then only the lit
 * LEDs show, then none. Every LED shown carries a visible label: each is a live toggle on the host.
 */
@Composable
fun SurfaceToolbarLeds(
    leds: List<Pair<String, Boolean>>,
    onToggle: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    // When the toolbar is tight the unlit LEDs give way to the host chip; with no room, the group goes.
    val shown = when (LocalSurfaceToolbarRoom.current) {
        SurfaceToolbarRoom.Full, SurfaceToolbarRoom.UnlitInitials -> leds.indices.toList()
        SurfaceToolbarRoom.LitOnly -> leds.indices.filter { leds[it].second }
        SurfaceToolbarRoom.NoLeds -> emptyList()
    }
    if (shown.isEmpty()) return
    // The pill draws at 40dp like the other toolbar pills; each LED's 48dp target reaches past it.
    Row(
        modifier = modifier
            .height(SurfaceToolbarHeight)
            .toolbarGroup(colors.surfaceContainerHigh),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SurfaceToolbarTargetGap),
    ) {
        shown.forEach { index ->
            val (label, lit) = leds[index]
            // A tight toolbar keeps an unlit LED as its dot and initial, so "off" still reads as off, not gone.
            val initial = !lit && LocalSurfaceToolbarRoom.current == SurfaceToolbarRoom.UnlitInitials
            Row(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .widthIn(min = 48.dp)
                    .toolbarToggle(lit) { onToggle(index) }
                    .semantics {
                        stateDescription = if (lit) "On" else "Off"
                        // TalkBack says the whole name, not the initial.
                        if (initial) contentDescription = label
                    }
                    .padding(horizontal = 4.dp)
                    .testTag("surface_led"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (lit) SurfaceTokens.LedLit else colors.outline),
                )
                Text(
                    if (initial) label.take(1) else label,
                    style = if (initial) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                    color = if (lit) colors.onSurface else colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** The 40dp pill a group of toolbar toggles draws on; each toggle's 48dp target reaches past it. */
private fun Modifier.toolbarGroup(color: Color): Modifier = drawBehind {
    val inset = 4.dp.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(0f, inset),
        size = Size(size.width, size.height - 2 * inset),
        cornerRadius = CornerRadius(size.height / 2 - inset),
    )
}

/** One of a surface's own on/off settings in the toolbar. [stateDescription] replaces "On"/"Off" for TalkBack. */
@Immutable
class SurfaceToolbarToggle(
    val label: String,
    val icon: ImageVector,
    val on: Boolean,
    val onToggle: () -> Unit,
    val stateDescription: String? = null,
    val testTag: String? = null,
)

/**
 * Labelled toggles in one 40dp group, styled like [SurfaceToolbarLeds]: a dot that lights when on and
 * the setting's name. When the toolbar is short of room for the host chip they step down to their
 * icons, filled when on, and TalkBack still hears the name.
 */
@Composable
fun SurfaceToolbarToggles(toggles: List<SurfaceToolbarToggle>, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val labelled = LocalSurfaceToolbarRoom.current == SurfaceToolbarRoom.Full
    Row(
        modifier = modifier
            .height(SurfaceToolbarHeight)
            .toolbarGroup(colors.surfaceContainerHigh),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SurfaceToolbarTargetGap),
    ) {
        toggles.forEach { toggle ->
            val target = Modifier
                .minimumInteractiveComponentSize()
                .then(if (labelled) Modifier.widthIn(min = 48.dp) else Modifier.size(40.dp).clip(CircleShape).background(if (toggle.on) colors.secondaryContainer else Color.Transparent))
                .toolbarToggle(toggle.on, toggle.onToggle)
                .semantics {
                    stateDescription = toggle.stateDescription ?: if (toggle.on) "On" else "Off"
                    if (!labelled) contentDescription = toggle.label
                }
                .then(toggle.testTag?.let { Modifier.testTag(it) } ?: Modifier)
            if (labelled) {
                Row(
                    modifier = target.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (toggle.on) SurfaceTokens.LedLit else colors.outline),
                    )
                    Text(
                        toggle.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (toggle.on) colors.onSurface else colors.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            } else {
                Box(target, contentAlignment = Alignment.Center) {
                    Icon(
                        toggle.icon,
                        contentDescription = null,
                        tint = if (toggle.on) colors.onSecondaryContainer else colors.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}
