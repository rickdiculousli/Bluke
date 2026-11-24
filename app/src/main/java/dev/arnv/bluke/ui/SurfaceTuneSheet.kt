package dev.arnv.bluke.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.LayoutDirection
import kotlin.coroutines.cancellation.CancellationException
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * A surface's own settings, in a panel that slides in from the end edge over the surface. It holds
 * the surface's `<Surface>TuneContent` and, above it, the surface state (console, button mode) that
 * isn't a setting. Back or a tap on the dimmed surface closes it; a predictive Back gesture slides the
 * panel toward its edge as it goes, and lets it settle back if the gesture is cancelled.
 */
@Composable
fun SurfaceTuneSheet(
    visible: Boolean,
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    // How far a Back gesture in progress has gone, 0 to 1. It holds through the exit, so the panel
    // slides out from where the gesture left it, and clears when the sheet opens again.
    var backProgress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(visible) { if (visible) backProgress = 0f }
    PredictiveBackHandler(enabled = visible) { progress ->
        try {
            progress.collect { backProgress = it.progress }
            onDismiss()
        } catch (cancelled: CancellationException) {
            backProgress = 0f
            throw cancelled
        }
    }
    val towardEdge = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(spring(stiffness = Spring.StiffnessMediumLow)),
            exit = fadeOut(spring(stiffness = Spring.StiffnessMedium)),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    // One node TalkBack can name and act on, like the Close button in the panel.
                    .semantics { contentDescription = "Close $title" }
                    .testTag("surface_tune_scrim")
                    .background(colors.scrim.copy(alpha = 0.32f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = "Close $title",
                        onClick = onDismiss,
                    ),
            )
        }
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            val panelWidth = (maxWidth * 0.5f).coerceIn(320.dp, 420.dp).coerceAtMost(maxWidth)
            AnimatedVisibility(
                visible = visible,
                enter = slideInHorizontally(spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy)) { it } +
                    fadeIn(spring(stiffness = Spring.StiffnessMediumLow)),
                exit = slideOutHorizontally(spring(stiffness = Spring.StiffnessMedium, dampingRatio = Spring.DampingRatioNoBouncy)) { it } +
                    fadeOut(spring(stiffness = Spring.StiffnessMedium)),
            ) {
                Column(
                    Modifier
                        .width(panelWidth)
                        .fillMaxHeight()
                        .graphicsLayer { translationX = towardEdge * backProgress * size.width * BACK_TRAVEL }
                        .background(colors.surfaceContainerLow, MaterialTheme.shapes.extraLarge.copy(topEnd = ZeroCornerSize, bottomEnd = ZeroCornerSize))
                        .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.End))
                        .semantics { paneTitle = title; isTraversalGroup = true }
                        .testTag("surface_tune_sheet"),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleLarge,
                            color = colors.onSurface,
                            modifier = Modifier.weight(1f).semantics { heading() },
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close $title", tint = colors.onSurfaceVariant)
                        }
                    }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                        content = content,
                    )
                }
            }
        }
    }
}

/** How much of its width the panel travels toward its edge over a whole Back gesture. */
private const val BACK_TRAVEL = 0.5f

/**
 * A titled group of tune controls. A section that holds one control names it with [title] and
 * passes the control no label of its own (`label = null`), with any explanation as [supporting],
 * so one choice never carries two headings.
 */
@Composable
fun TuneSection(title: String, supporting: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { heading() },
            )
            if (supporting != null) {
                Text(supporting, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        content()
    }
}

/**
 * A labelled slider over [steps] stops, its current value shown beside the label as [valueLabel] and
 * any explanation under it as [supporting]. The one slider of the Tune sheets and Settings; the caller
 * plays the step tick, since only it knows what a step is.
 */
@Composable
fun TuneSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueLabel: String,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    supporting: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(16.dp))
            Text(valueLabel, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
        }
        if (supporting != null) {
            Text(supporting, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            // Sliders reach the screen's end edge; keep the system Back swipe from stealing the drag.
            modifier = Modifier
                .systemGestureExclusion()
                .semantics { contentDescription = label; stateDescription = valueLabel },
        )
    }
}

/** A setting that is on or off; the whole row is the target. */
@Composable
fun TuneSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp, top = 8.dp, bottom = 8.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
            if (supporting != null) {
                Text(supporting, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * One choice among [options], by the one rule the Tune sheets and Settings share: a segmented row
 * while every label fits its segment on one line at the current width and font scale; when they
 * don't, radio rows for up to [MAX_LISTED_CHOICES] options and a wrapping group of chips for more,
 * so every option stays visible and nothing truncates.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> TuneChoice(
    label: String?,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (label != null || supporting != null) {
            Column {
                if (label != null) {
                    Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                }
                if (supporting != null) {
                    Text(supporting, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val measurer = rememberTextMeasurer()
            val style = MaterialTheme.typography.labelLarge
            // Each segment holds its label inside 12dp side padding plus room for the check icon
            // (18dp + 8dp gap) that the selected one shows. Every option reserves it, so the layout
            // depends on width and font scale, never on which option is selected.
            val density = LocalDensity.current
            val segmented = options.size <= MAX_LISTED_CHOICES && with(density) {
                val segment = maxWidth / options.size
                options.all { option ->
                    val room = segment - 24.dp - 26.dp
                    measurer.measure(optionLabel(option), style, maxLines = 1).size.width <= room.roundToPx()
                }
            }
            when {
                segmented -> SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    options.forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = option == selected,
                            onClick = { onSelect(option) },
                            shape = SegmentedButtonDefaults.itemShape(index, options.size),
                        ) { Text(optionLabel(option), maxLines = 1) }
                    }
                }
                options.size <= MAX_LISTED_CHOICES -> Column(Modifier.fillMaxWidth().selectableGroup()) {
                    options.forEach { option ->
                        TuneRadioRow(optionLabel(option), option == selected) { onSelect(option) }
                    }
                }
                else -> FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { option ->
                        TuneChoiceChip(
                            selected = option == selected,
                            onClick = { onSelect(option) },
                            label = optionLabel(option),
                        )
                    }
                }
            }
        }
    }
}

/** The most options [TuneChoice] lists as segments or radio rows; more become chips. */
const val MAX_LISTED_CHOICES = 4

/**
 * One option of a [TuneChoice] that didn't fit as segments; the whole row selects it. It sits inside
 * content already at the rows' inset, so with [ChoiceRadio] its radio and label land where Settings'
 * radio rows put theirs.
 */
@Composable
private fun TuneRadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = ChoiceRowMinHeight)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChoiceRadio(selected)
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * A chip that picks one of several (a [TuneChoice] option, a keyboard theme): it says so to TalkBack
 * as a radio button rather than a chip's checkbox, and leads with a check while selected.
 */
@Composable
internal fun TuneChoiceChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
        } else null,
        trailingIcon = trailingIcon,
        modifier = Modifier.semantics { role = Role.RadioButton },
    )
}

/** A tune action that opens or does something (Edit layout, Manage themes…). */
@Composable
fun TuneAction(label: String, icon: ImageVector?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(onClick = onClick, modifier = modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(label)
    }
}

/** A speed shown the way the toolbar used to: one decimal and a times sign. */
fun speedLabel(speed: Float): String {
    val tenths = (speed * 10).roundToInt()
    return "${tenths / 10}.${tenths % 10}×"
}

/** Snaps [value] to the nearest of [stops]. */
fun snapTo(value: Float, stops: List<Float>): Float = stops.minBy { kotlin.math.abs(it - value) }
