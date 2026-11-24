package dev.arnv.bluke.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowRight
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.arnv.bluke.mirror.MirrorPhase
import dev.arnv.bluke.mirror.MirrorTouchMode
import dev.arnv.bluke.mirror.MirrorUiState
import dev.arnv.bluke.mirror.readout

/** Which Direct/Hover labels the rail has room for. The semantics carry both names either way. */
internal enum class ModeLabels { BOTH, SELECTED, NONE }

/**
 * How the rail fits the height it has. [largeFps] sets the frame rate in labelLarge when there's
 * room for it; [wordOnNextMode] puts the word "Mirror" on the next-mode button, else its glyph.
 */
internal data class RailLayout(
    val modeLabels: ModeLabels,
    val showName: Boolean,
    val largeFps: Boolean,
    val wordOnNextMode: Boolean,
)

/** The rail's text, measured once at the current font scale: line heights and two widths. */
internal data class RailText(
    val labelLine: Dp,
    val largeLine: Dp,
    val nameLine: Dp,
    val widestLatency: Dp,
    val mirrorWord: Dp,
)

/** The rail is 88dp, wider only when large text needs it for a three-digit latency. */
internal fun railWidth(text: RailText): Dp = maxOf(RAIL_WIDTH, text.widestLatency + 16.dp)

/**
 * The rail-fit rule: of the richest arrangements first, the one whose items add up to no more than
 * [available] height, so the current mode's word and the streaming computer's name stay on screen
 * at font scales 1.0 and 1.3 on a 360dp-high phone; with no room for even that, icons alone.
 */
internal fun fitRail(available: Dp, text: RailText): RailLayout {
    val wordFits = NEXT_PILL_START + text.mirrorWord + NEXT_ARROW + NEXT_PILL_END <= railWidth(text) - 16.dp
    val labelledItem = RAIL_ITEM_PILL + 8.dp + text.labelLine
    // Close, next mode and the menu, three gaps in the top group, one each side of the readout.
    val fixed = RAIL_TARGET * 3 + RAIL_GAP * 5
    val (labels, showName, largeFps) = listOf(
        Triple(ModeLabels.BOTH, true, true) to
            fixed + labelledItem * 2 + text.largeLine + text.labelLine + text.nameLine,
        Triple(ModeLabels.SELECTED, true, false) to
            fixed + labelledItem + RAIL_TARGET + text.labelLine * 2 + text.nameLine,
        Triple(ModeLabels.SELECTED, false, false) to
            fixed + labelledItem + RAIL_TARGET + text.labelLine * 2,
    ).firstOrNull { (_, height) -> height <= available }?.first
        ?: Triple(ModeLabels.NONE, false, false)
    return RailLayout(labels, showName, largeFps, wordFits)
}

@Composable
private fun rememberRailText(): RailText {
    val measurer = rememberTextMeasurer()
    val type = MaterialTheme.typography
    val density = LocalDensity.current
    return remember(measurer, type, density) {
        with(density) {
            RailText(
                labelLine = type.labelMedium.lineHeight.toDp(),
                largeLine = type.labelLarge.lineHeight.toDp(),
                nameLine = type.labelSmall.lineHeight.toDp(),
                widestLatency = measurer.measure(WIDEST_LATENCY, type.labelMedium).size.width.toDp(),
                mirrorWord = measurer.measure(MIRROR_WORD, type.labelMedium).size.width.toDp(),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MirrorRail(
    state: MirrorUiState,
    haptics: MirrorHaptics,
    onClose: () -> Unit,
    onNextMode: () -> Unit,
    onConfigureModes: () -> Unit,
    onTouchModeChange: (MirrorTouchMode) -> Unit,
    onForgetCompanion: () -> Unit,
) {
    val text = rememberRailText()
    val railWidth = railWidth(text)
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
            val layout = fitRail(maxHeight, text)
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
                        wordFits = layout.wordOnNextMode,
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
private fun NextModeButton(wordFits: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val style = MaterialTheme.typography.labelMedium
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
            .testTag("mirror_mode_${mode.name.lowercase()}")
            // One description for TalkBack; the visible label under the pill would repeat its first word.
            .clearAndSetSemantics { contentDescription = description },
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

private const val MIRROR_WORD = "Mirror"
private const val WIDEST_LATENCY = "999 ms"

internal val RAIL_WIDTH = 88.dp
private val RAIL_TARGET = 48.dp
private val RAIL_ITEM_PILL = 32.dp
private val RAIL_GAP = 8.dp
private val NEXT_PILL_START = 12.dp
private val NEXT_PILL_END = 4.dp
private val NEXT_ARROW = 18.dp
