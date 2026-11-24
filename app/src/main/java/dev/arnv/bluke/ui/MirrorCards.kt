package dev.arnv.bluke.ui

import androidx.compose.runtime.getValue
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.arnv.bluke.mirror.MirrorNotice
import dev.arnv.bluke.mirror.MirrorPhase
import dev.arnv.bluke.mirror.MirrorUiState
import dev.arnv.bluke.mirror.isLive
import dev.arnv.bluke.mirror.PairingPrompt
import dev.arnv.bluke.mirror.pairingRequest
import dev.arnv.bluke.mirror.pictureStale
import kotlinx.coroutines.delay

/**
 * While a live picture's link is quiet (no reply for a couple of seconds), a chip over it says so:
 * the picture may be frozen, and the session ends on its own if the link doesn't come back.
 */
@Composable
internal fun QuietLinkChip(state: MirrorUiState) {
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
            Icon(
                Icons.Filled.HourglassTop,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
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
 * A pairing request that arrives while a picture is live: a chip above the picture says who asks,
 * and leaves the rest of it to touch. Show code opens the card with the code; Decline turns it down.
 */
@Composable
internal fun PairingChip(
    request: MirrorPhase.Pairing?,
    onShowCode: (String) -> Unit,
    onDecline: () -> Unit,
) {
    // What fades out is what was last shown.
    val last = remember { arrayOf<MirrorPhase.Pairing?>(null) }
    if (request != null) last[0] = request
    AnimatedVisibility(
        visible = request != null,
        enter = fadeIn(EffectsSpring) + expandVertically(SpatialSizeSpring),
        exit = fadeOut(EffectsSpring) + shrinkVertically(SpatialSizeSpring),
    ) {
        val shown = last[0] ?: return@AnimatedVisibility
        val actionColors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
        Row(
            modifier = Modifier
                .padding(bottom = 8.dp)
                .clip(MaterialTheme.shapes.extraLarge)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .heightIn(min = 48.dp)
                .padding(start = 16.dp, end = 4.dp)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag("mirror_pairing_chip"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Key,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Text(
                "${shown.companionName} wants to pair",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).padding(start = 12.dp, end = 4.dp),
            )
            TextButton(
                onClick = { onShowCode(shown.code) },
                colors = actionColors,
                modifier = Modifier.testTag("mirror_pairing_show_code"),
            ) {
                Text("Show code", fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = onDecline, colors = actionColors, modifier = Modifier.testTag("mirror_pairing_chip_decline")) {
                Text("Decline")
            }
        }
    }
}

/**
 * Everything the surface says when there's no live picture to touch, and a pairing code the
 * person asked to see while one is streaming ([prompt] is [PairingPrompt.CARD]).
 */
@Composable
internal fun MirrorOverlay(
    state: MirrorUiState,
    prompt: PairingPrompt,
    hasStaleFrame: Boolean,
    onDeclinePairing: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val phase = state.phase
    // Wider with larger text, so a big font scale wraps less instead of scrolling.
    val maxCardWidth = 480.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
    AnimatedVisibility(
        visible = !phase.isLive || prompt == PairingPrompt.CARD,
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
                            .scrollCue(scroll, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                            .verticalScroll(scroll)
                            .padding(horizontal = 24.dp, vertical = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StatusContent(shown.state, shown.hasStaleFrame, onDeclinePairing, onOpenWifiSettings)
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
            is MirrorPhase.Connected -> "connected ${phase.companionName} ${phase.pictureOverdue}"
            else -> phase
        }
}

@Composable
private fun StatusContent(
    state: MirrorUiState,
    hasStaleFrame: Boolean,
    onDeclinePairing: () -> Unit,
    onOpenWifiSettings: () -> Unit,
) {
    when (val phase = state.phase.pairingRequest ?: state.phase) {
        MirrorPhase.Off -> StatusHeader(icon = null, title = "Starting Mirror", busy = true)
        MirrorPhase.NoWifi -> {
            StatusHeader(Icons.Filled.WifiOff, "Connect to Wi-Fi")
            Body("Mirror reaches your computer over the local network. Join the Wi-Fi your computer is on.")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = onOpenWifiSettings,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                    modifier = Modifier.testTag("mirror_wifi_settings"),
                ) {
                    Text("Wi-Fi settings")
                }
            }
        }
        is MirrorPhase.Waiting -> when {
            phase.pairedName == null -> {
                StatusHeader(Icons.Filled.DesktopWindows, "Pair a computer")
                Body("On the computer, in a terminal, run:")
                Command("bluke-mirror pair")
                PhoneAddressHint(state, "bluke-mirror pair")
                Body("No bluke-mirror yet? README.md in Bluke's source, section Mirror, says how to get it.", muted = true)
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
                TextButton(
                    onClick = onDeclinePairing,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                    modifier = Modifier.testTag("mirror_pairing_decline"),
                ) {
                    Text("Decline")
                }
            }
        }
        MirrorPhase.PairingLocked -> {
            StatusHeader(Icons.Filled.Lock, "Too many wrong codes")
            Body("Pairing is closed for now. Close Mirror and open it again for a new code.")
        }
        is MirrorPhase.Connected -> if (phase.pictureOverdue) {
            // Capture failing is only ever explained on the computer; send the person there.
            StatusHeader(Icons.Filled.DesktopWindows, "Connected to ${phase.companionName}")
            Body("No picture yet. The bluke-mirror terminal on ${phase.companionName} says why.")
        } else {
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
                .clip(MaterialTheme.shapes.extraSmall)
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
 * Shows that content runs past a card's edge (large text on a short screen): the edges [scroll]
 * can still move toward fade out, and a slim thumb at the side says how much more there is, even
 * when the cut falls between two lines.
 */
private fun Modifier.scrollCue(scroll: ScrollState, thumbColor: Color): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val top = if (scroll.canScrollBackward) Color.Transparent else Color.Black
        val bottom = if (scroll.canScrollForward) Color.Transparent else Color.Black
        if (top == Color.Black && bottom == Color.Black) return@drawWithContent
        val edge = (SCROLL_FADE.toPx() / size.height).coerceAtMost(0.5f)
        drawRect(
            Brush.verticalGradient(0f to top, edge to Color.Black, 1f - edge to Color.Black, 1f to bottom),
            blendMode = BlendMode.DstIn,
        )
        val inset = SCROLL_THUMB_INSET.toPx()
        val track = size.height - inset * 2
        val content = size.height + scroll.maxValue
        val thumb = track * size.height / content
        val thumbTop = inset + track * scroll.value / content
        val width = SCROLL_THUMB_WIDTH.toPx()
        drawRoundRect(
            color = thumbColor,
            topLeft = Offset(size.width - width - SCROLL_THUMB_END.toPx(), thumbTop),
            size = androidx.compose.ui.geometry.Size(width, thumb),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(width / 2),
        )
    }

/**
 * A one-line confirmation ("Paired with …", "Wrong code"), shown for a few seconds, or "Mirror
 * forgot [pairedName]" with an Undo while [forgetPending]. Touches pass through it to the picture, except on
 * the Undo button.
 */
@Composable
internal fun MirrorNoticePill(
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
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.inverseOnSurface),
                    modifier = Modifier.padding(end = 4.dp).testTag("mirror_undo_forget"),
                ) {
                    // The host scheme sets no inverse primary, so weight, not hue, marks the action.
                    Text("Undo", fontWeight = FontWeight.Bold)
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
    is MirrorNotice.Streaming -> "Streaming from $companionName"
}

private const val FORGOTTEN_TEXT = "Mirror pairing removed"

/** How long a notice stays: 3 s, longer when accessibility services ask for more time to act. */
@Composable
internal fun noticeMillis(withAction: Boolean): Long =
    LocalAccessibilityManager.current?.calculateRecommendedTimeoutMillis(
        NOTICE_MILLIS,
        containsIcons = false,
        containsText = true,
        containsControls = withAction,
    ) ?: NOTICE_MILLIS

private const val NOTICE_MILLIS = 3_000L

private val SCROLL_FADE = 32.dp
private val SCROLL_THUMB_WIDTH = 4.dp
private val SCROLL_THUMB_END = 8.dp
private val SCROLL_THUMB_INSET = 24.dp
