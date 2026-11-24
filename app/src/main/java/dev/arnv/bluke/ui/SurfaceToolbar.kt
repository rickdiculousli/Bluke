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
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
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
    /** Long-press on the mode button. Null until a Settings route to its input-modes section exists. */
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surfaceContainerLow)
            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
            .height(SurfaceToolbarHeight)
            .padding(horizontal = 4.dp)
            .testTag("surface_toolbar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(onClick = chrome.onClose, modifier = Modifier.testTag("surface_close")) {
            Icon(Icons.Default.Close, contentDescription = "Close ${chrome.mode.displayName.lowercase()}", tint = colors.onSurfaceVariant)
        }
        ModeButton(chrome, haptics, compact)
        // The host chip is the one element that gives way on a narrow phone: its name ellipsizes.
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            HostChip(chrome.host, haptics, Modifier.weight(1f, fill = false))
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            content = controls,
        )
        IconButton(onClick = onTune, modifier = Modifier.testTag("surface_tune")) {
            Icon(Icons.Default.Tune, contentDescription = "Tune ${chrome.mode.displayName.lowercase()}", tint = colors.onSurfaceVariant)
        }
    }
}

/** The icon each mode shows on its mode button. */
fun InputMode.icon(): ImageVector = when (this) {
    InputMode.KEYBOARD -> Icons.Default.Keyboard
    InputMode.TOUCHPAD -> Icons.Default.Mouse
    InputMode.GAMEPAD -> Icons.Default.Gamepad
    InputMode.MEDIA_PRESENTATION -> Icons.Default.PlayCircle
    else -> Icons.Default.Devices
}

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
        Icon(chrome.mode.icon(), contentDescription = null, tint = colors.onSecondaryContainer, modifier = Modifier.size(18.dp))
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
internal data class HostChipContent(val status: String?, val name: String?, val action: String?, val description: String) {
    val label: String get() = listOfNotNull(status, name).joinToString(" ")
}

internal fun hostChipContent(host: SurfaceHost): HostChipContent {
    val name = host.name?.takeIf { it.isNotBlank() }
    return when (host.status) {
        SurfaceHostStatus.Connected -> HostChipContent(
            status = if (name == null) "Connected" else null,
            name = name,
            action = null,
            description = "Connected to ${name ?: "host"}",
        )
        SurfaceHostStatus.Connecting -> HostChipContent(
            status = if (name == null) "Connecting…" else "Connecting to",
            name = name,
            action = null,
            description = "Connecting to ${name ?: "host"}",
        )
        SurfaceHostStatus.Offline -> HostChipContent(
            status = if (name == null) "No host" else null,
            name = name,
            action = if (host.onReconnect != null) "Reconnect" else null,
            description = if (name != null) "Offline. Last host $name" else "Offline. No host paired",
        )
        SurfaceHostStatus.Blocked -> HostChipContent(
            status = host.reason ?: "Bluetooth unavailable",
            name = null,
            action = null,
            description = host.reason ?: "Bluetooth unavailable",
        )
    }
}

@Composable
private fun HostChip(host: SurfaceHost, haptics: SurfaceHaptics, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val content = hostChipContent(host)
    val reconnect = host.onReconnect.takeIf { host.status == SurfaceHostStatus.Offline }
    val shape = CircleShape
    var chip = modifier
        .semantics { liveRegion = LiveRegionMode.Polite }
        .minimumInteractiveComponentSize()
        .heightIn(min = 40.dp)
        .clip(shape)
        .border(1.dp, colors.outlineVariant, shape)
    chip = if (reconnect != null) {
        chip.semantics { stateDescription = "Offline" }.clickable(
            onClickLabel = "Reconnect",
            role = Role.Button,
            onClick = {
                haptics.perform(HapticEvent.PRESS)
                reconnect()
            },
        )
    } else {
        chip.clearAndSetSemantics { contentDescription = content.description }
    }
    Row(
        modifier = chip.padding(horizontal = 12.dp).testTag("surface_host"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HostDot(host.status)
        val textColor = if (host.status == SurfaceHostStatus.Connected) colors.onSurface else colors.onSurfaceVariant
        // What gives way, in order: the host's name, then status words; the Reconnect action never does.
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
                    modifier = if (content.name == null) Modifier.weight(1f, fill = false) else Modifier,
                )
            }
            if (content.name != null) {
                Text(
                    text = content.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).widthIn(max = 180.dp),
                )
            }
        }
        if (content.action != null) {
            Icon(Icons.Default.Refresh, contentDescription = null, tint = colors.primary, modifier = Modifier.size(16.dp))
            Text(content.action, style = MaterialTheme.typography.labelLarge, color = colors.primary, maxLines = 1)
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
        target.toggleable(value = on, role = Role.Switch, onValueChange = { onClick() })
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
 * numpad). Each LED is its own 48dp-wide toggle; [onToggle] gets the index tapped.
 */
@Composable
fun SurfaceToolbarLeds(
    leds: List<Pair<String, Boolean>>,
    onToggle: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    // The pill draws at 40dp like the other toolbar pills; each LED's 48dp target reaches past it.
    Row(
        modifier = modifier
            .height(SurfaceToolbarHeight)
            .drawBehind {
                val inset = 4.dp.toPx()
                drawRoundRect(
                    color = colors.surfaceContainerHigh,
                    topLeft = Offset(0f, inset),
                    size = Size(size.width, size.height - 2 * inset),
                    cornerRadius = CornerRadius(size.height / 2 - inset),
                )
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leds.forEachIndexed { index, (label, lit) ->
            Row(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .widthIn(min = 48.dp)
                    .toggleable(value = lit, role = Role.Switch, onValueChange = { onToggle(index) })
                    .semantics { stateDescription = if (lit) "On" else "Off" }
                    .padding(horizontal = 8.dp),
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
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (lit) colors.onSurface else colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}
