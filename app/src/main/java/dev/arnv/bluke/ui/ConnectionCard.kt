package dev.arnv.bluke.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The status dot's "connected" hue. Material has no success role, so this is the one colour on home
 * outside the scheme, with a light and a dark tone that each hold 3:1 on the card.
 */
private object StatusDotColors {
    val goodOnLight = Color(0xFF1B7F3B)
    val goodOnDark = Color(0xFF7BDA97)
}

private data class CardColors(val container: Color, val content: Color, val supporting: Color)

@Composable
private fun cardColors(tone: StatusTone): CardColors {
    val scheme = MaterialTheme.colorScheme
    return when (tone) {
        StatusTone.GOOD -> CardColors(scheme.secondaryContainer, scheme.onSecondaryContainer, scheme.onSecondaryContainer)
        StatusTone.PROBLEM -> CardColors(scheme.errorContainer, scheme.onErrorContainer, scheme.onErrorContainer)
        StatusTone.NEUTRAL, StatusTone.WORKING -> CardColors(scheme.surfaceContainerHigh, scheme.onSurface, scheme.onSurfaceVariant)
    }
}

@Composable
private fun dotColor(tone: StatusTone): Color {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    return when (tone) {
        StatusTone.GOOD -> if (dark) StatusDotColors.goodOnDark else StatusDotColors.goodOnLight
        StatusTone.WORKING -> scheme.tertiary
        StatusTone.PROBLEM -> scheme.error
        StatusTone.NEUTRAL -> scheme.outline
    }
}

private fun ConnectionUi.icon(): ImageVector = when (this) {
    is ConnectionUi.Connected -> Icons.Default.BluetoothConnected
    is ConnectionUi.Blocked -> when (reason) {
        BlockReason.PERMISSION_MISSING -> Icons.Default.Lock
        else -> Icons.Default.BluetoothDisabled
    }
    is ConnectionUi.Failed -> if (reason == FailReason.STALLED) Icons.AutoMirrored.Filled.BluetoothSearching else Icons.Default.ErrorOutline
    is ConnectionUi.Ready -> if (scanning) Icons.AutoMirrored.Filled.BluetoothSearching else Icons.Default.Bluetooth
    is ConnectionUi.Disconnecting -> Icons.Default.LinkOff
    else -> Icons.AutoMirrored.Filled.BluetoothSearching
}

private val StatusTone.spoken: String
    get() = when (this) {
        StatusTone.GOOD -> "Connected"
        StatusTone.WORKING -> "In progress"
        StatusTone.PROBLEM -> "Needs attention"
        StatusTone.NEUTRAL -> "Not connected"
    }

/**
 * The home screen's one status: what the connection is doing, in words, with the one next step.
 * Details hold the manager's own status line, the attempt, the host's address and Restart HID.
 */
@Composable
internal fun ConnectionCard(
    connection: ConnectionUi,
    statusLine: String,
    onAction: (ConnectionAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val card = connection.card()
    val colors = cardColors(card.tone)
    val container by animateColorAsState(colors.container, spring(stiffness = Spring.StiffnessMedium), label = "card-container")
    val content by animateColorAsState(colors.content, spring(stiffness = Spring.StiffnessMedium), label = "card-content")
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("connection_card"),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Column(
            modifier = Modifier
                .animateContentSize(spring(stiffness = Spring.StiffnessMedium))
                .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                StatusBadge(connection.icon(), card.tone, card.busy, container)
                Spacer(Modifier.width(16.dp))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .semantics(mergeDescendants = true) {
                            liveRegion = LiveRegionMode.Polite
                            stateDescription = card.tone.spoken
                        },
                ) {
                    Text(
                        text = card.headline,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .semantics { heading() }
                            .testTag("connection_headline"),
                    )
                    card.supporting?.let {
                        Spacer(Modifier.size(4.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.supporting)
                    }
                }
            }
            if (card.primary != null || card.secondary != null) {
                Spacer(Modifier.size(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    card.primary?.let { PrimaryActionButton(it, card.tone, onAction, Modifier.weight(1f)) }
                    card.secondary?.let { action ->
                        TextButton(onClick = { onAction(action) }, colors = ButtonDefaults.textButtonColors(contentColor = content)) {
                            Text(action.label)
                        }
                    }
                }
            }
            CardDetails(connection, statusLine, content, onAction)
        }
    }
}

@Composable
private fun StatusBadge(icon: ImageVector, tone: StatusTone, busy: Boolean, cardColor: Color) {
    Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerLowest, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
        }
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                trackColor = Color.Transparent,
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(14.dp)
                .border(2.dp, cardColor, CircleShape)
                .padding(2.dp)
                .background(dotColor(tone), CircleShape)
                .testTag("status_dot"),
        )
    }
}

@Composable
private fun PrimaryActionButton(
    action: ConnectionAction,
    tone: StatusTone,
    onAction: (ConnectionAction) -> Unit,
    modifier: Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val buttonModifier = modifier
        .heightIn(min = 48.dp)
        .testTag("connection_primary")
    val label: @Composable () -> Unit = {
        Text(action.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    when {
        // Steps that end or pause something stay quieter than steps that move forward.
        action == ConnectionAction.Disconnect || action == ConnectionAction.Cancel || action == ConnectionAction.StopScan ->
            OutlinedButton(
                onClick = { onAction(action) },
                modifier = buttonModifier,
                // The card's own on-colour: `outline` falls under 3:1 on the tinted cards in dark.
                border = BorderStroke(1.dp, cardColors(tone).content),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = cardColors(tone).content),
            ) { label() }
        tone == StatusTone.PROBLEM -> Button(
            onClick = { onAction(action) },
            modifier = buttonModifier,
            colors = ButtonDefaults.buttonColors(containerColor = scheme.error, contentColor = scheme.onError),
        ) { label() }
        tone == StatusTone.GOOD -> FilledTonalButton(onClick = { onAction(action) }, modifier = buttonModifier) { label() }
        else -> Button(onClick = { onAction(action) }, modifier = buttonModifier) { label() }
    }
}

@Composable
private fun CardDetails(
    connection: ConnectionUi,
    statusLine: String,
    content: Color,
    onAction: (ConnectionAction) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val rotation by androidx.compose.animation.core.animateFloatAsState(
        if (expanded) 180f else 0f, spring(stiffness = Spring.StiffnessMedium), label = "details-chevron",
    )
    TextButton(
        onClick = { expanded = !expanded },
        colors = ButtonDefaults.textButtonColors(contentColor = content),
        // Shifted so the label sits on the card's content edge, keeping the button's own padding for its pressed state.
        modifier = Modifier
            .offset(x = (-12).dp)
            .heightIn(min = 48.dp)
            .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
            .testTag("connection_details_toggle"),
    ) {
        Text("Details")
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp).rotate(rotation))
    }
    AnimatedVisibility(
        visible = expanded,
        enter = expandVertically(spring(stiffness = Spring.StiffnessMedium)) + fadeIn(spring(stiffness = Spring.StiffnessMedium)),
        exit = shrinkVertically(spring(stiffness = Spring.StiffnessMedium)) + fadeOut(spring(stiffness = Spring.StiffnessMedium)),
    ) {
        Column(modifier = Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HorizontalDivider()
            DetailLine("Status", statusLine.ifBlank { "No status yet" }, content)
            (connection as? ConnectionUi.Starting)?.let { DetailLine("Attempt", "${it.attempt}", content) }
            connection.host?.address?.takeIf { it.isNotBlank() }?.let { DetailLine("Host address", it, content) }
            if (connection.offersRestartInDetails) {
                OutlinedButton(
                    onClick = { onAction(ConnectionAction.RestartHid) },
                    border = BorderStroke(1.dp, content),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = content),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(ConnectionAction.RestartHid.label)
                }
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String, content: Color) {
    Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = content)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = content)
    }
}
