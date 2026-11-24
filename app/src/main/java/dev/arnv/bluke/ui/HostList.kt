package dev.arnv.bluke.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal val DeviceType.icon: ImageVector
    get() = when (this) {
        DeviceType.COMPUTER -> Icons.Default.Laptop
        DeviceType.PHONE -> Icons.Default.PhoneAndroid
        DeviceType.TV -> Icons.Default.Tv
        DeviceType.AUDIO -> Icons.Default.Headphones
        DeviceType.MOUSE -> Icons.Default.Mouse
        DeviceType.GAMEPAD -> Icons.Default.SportsEsports
        DeviceType.WATCH -> Icons.Default.Watch
        DeviceType.DEFAULT -> Icons.Default.Bluetooth
    }

/** A section title, optionally a disclosure: the whole row toggles, and its state is spoken. */
@Composable
internal fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
    expanded: Boolean? = null,
    onToggle: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val rotation by animateFloatAsState(
        if (expanded == true) 180f else 0f, spring(stiffness = Spring.StiffnessMedium), label = "section-chevron",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(
                if (onToggle != null) {
                    Modifier
                        .clip(MaterialTheme.shapes.medium)
                        .clickable(role = Role.Button, onClickLabel = if (expanded == true) "Collapse" else "Expand", onClick = onToggle)
                        .semantics { stateDescription = if (expanded == true) "Expanded" else "Collapsed" }
                } else Modifier,
            )
            .padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (count != null) "$title · $count" else title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        trailing?.invoke()
        if (onToggle != null) {
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp).rotate(rotation),
            )
        }
    }
}

/** Rows in one group share a container: the theme's large corners at the ends, extra-small between. */
@Composable
private fun groupShape(index: Int, last: Int): CornerBasedShape {
    val outer = MaterialTheme.shapes.large
    val inner = MaterialTheme.shapes.extraSmall
    return outer.copy(
        topStart = if (index == 0) outer.topStart else inner.topStart,
        topEnd = if (index == 0) outer.topEnd else inner.topEnd,
        bottomStart = if (index == last) outer.bottomStart else inner.bottomStart,
        bottomEnd = if (index == last) outer.bottomEnd else inner.bottomEnd,
    )
}

/** A group of host rows; each row's state comes from the connection, never from status text. */
@Composable
internal fun HostGroup(
    hosts: List<HostRef>,
    connection: ConnectionUi,
    showAddress: Boolean,
    onConnect: (HostRef) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        hosts.forEachIndexed { index, host ->
            HostRow(
                host = host,
                state = connection.rowState(host.address),
                showAddress = showAddress,
                shape = groupShape(index, hosts.lastIndex),
                onConnect = { onConnect(host) },
            )
        }
    }
}

@Composable
private fun HostRow(
    host: HostRef,
    state: HostRowState,
    showAddress: Boolean,
    shape: CornerBasedShape,
    onConnect: () -> Unit,
) {
    val likely = isLikelyHost(host)
    val clickable = likely && (state == HostRowState.IDLE || state == HostRowState.FAILED)
    val scheme = MaterialTheme.colorScheme
    val supporting = when (state) {
        HostRowState.CONNECTED -> "Connected"
        HostRowState.WORKING -> if (host.bonded) "Connecting…" else "Pairing…"
        HostRowState.FAILED -> "Couldn't connect"
        HostRowState.IDLE -> if (!likely) "Unlikely to accept a keyboard" else host.type.displayName
    }
    Surface(
        shape = shape,
        color = if (state == HostRowState.CONNECTED) scheme.secondaryContainer else scheme.surfaceContainer,
        contentColor = if (state == HostRowState.CONNECTED) scheme.onSecondaryContainer else scheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("host_row_${host.address}"),
    ) {
        Row(
            modifier = Modifier
                .then(
                    if (clickable) {
                        Modifier.clickable(role = Role.Button, onClickLabel = "Connect", onClick = onConnect)
                    } else Modifier,
                )
                .heightIn(min = 64.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        if (state == HostRowState.CONNECTED) scheme.surfaceContainerLowest else scheme.surfaceContainerHighest,
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    host.type.icon,
                    contentDescription = null,
                    tint = if (likely) scheme.onSurfaceVariant else scheme.outline,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    host.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (likely) Color.Unspecified else scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodyMedium,
                    color = when (state) {
                        HostRowState.FAILED -> scheme.error
                        HostRowState.CONNECTED -> scheme.onSecondaryContainer
                        else -> scheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (showAddress) {
                    Text(
                        host.address,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (state == HostRowState.CONNECTED) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            when (state) {
                HostRowState.CONNECTED -> Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(24.dp))
                HostRowState.WORKING -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                HostRowState.FAILED -> Text("Try again", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                HostRowState.IDLE -> if (likely) {
                    Text("Connect", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** With nothing paired yet, home shows how to connect in three steps. */
@Composable
internal fun FirstHostSteps(modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth().testTag("first_host_steps"),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Connect your first host", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            listOf(
                "Open Bluetooth settings on your computer, TV or tablet.",
                "Tap Scan for hosts, then pick it from Nearby.",
                "Accept the pairing prompt on both devices.",
            ).forEachIndexed { index, step ->
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "${index + 1}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        step,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/** The Nearby header's scan control. */
@Composable
internal fun ScanButton(scanning: Boolean, onToggle: () -> Unit) {
    TextButton(onClick = onToggle, modifier = Modifier.heightIn(min = 48.dp).testTag("scan_toggle")) {
        if (scanning) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Stop")
        } else {
            Text("Scan")
        }
    }
}
