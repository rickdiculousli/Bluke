package dev.arnv.bluke.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * The settings kit. Every settings screen builds from these, so spacing, shape and colour roles stay
 * in one place: rows are 16dp from the card edge and at least 56dp tall, cards use the theme's
 * `shapes.large` on `surfaceContainer`, and every row's whole width is its touch target.
 */

private val RowHorizontalPadding = 16.dp
private val RowMinHeight = 56.dp
private val RadioSize = 24.dp

/**
 * Where every single-choice row's label starts, measured from the card edge: its radio sits at the
 * rows' 16dp inset and the label 16dp after it. Settings' radio rows, the library lists and the Tune
 * sheets' fallback radios share it, so their radios and labels line up in one column.
 */
internal val ChoiceLabelInset = RowHorizontalPadding + RadioSize + 16.dp

/** A choice row's radio and the gap after it; the row starts at the rows' inset and its whole width selects. */
@Composable
internal fun RowScope.ChoiceRadio(selected: Boolean) {
    RadioButton(selected = selected, onClick = null, modifier = Modifier.size(RadioSize))
    Spacer(Modifier.width(ChoiceLabelInset - RowHorizontalPadding - RadioSize))
}

/** The height of every single-choice row. */
internal val ChoiceRowMinHeight = RowMinHeight

private val ExpandSpring = spring<androidx.compose.ui.unit.IntSize>(stiffness = Spring.StiffnessMedium)

/**
 * One collapsible section of the Settings screen. The header names the section and, in [summary],
 * what it holds now, so a collapsed section still shows its current values.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SettingsSectionCard(
    title: String,
    summary: String,
    icon: ImageVector,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    // A section the user opens near the bottom grows below the fold; once its expansion settles,
    // scroll it into view. Only for the user's own tap: a section opened by routing is scrolled to already.
    val bringIntoView = remember { BringIntoViewRequester() }
    var openedByTap by remember { mutableStateOf(false) }
    val visibility = remember { MutableTransitionState(expanded) }
    visibility.targetState = expanded
    val settledOpen = visibility.isIdle && visibility.currentState
    LaunchedEffect(settledOpen, openedByTap) {
        if (settledOpen && openedByTap) {
            bringIntoView.bringIntoView()
            openedByTap = false
        }
    }
    Surface(
        modifier = modifier.fillMaxWidth().bringIntoViewRequester(bringIntoView),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column {
            val chevronRotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp)
                    .clickable(onClickLabel = if (expanded) "Collapse" else "Expand") {
                        openedByTap = !expanded
                        onToggle()
                    }
                    .semantics {
                        heading()
                        stateDescription = if (expanded) "Expanded" else "Collapsed"
                    }
                    .padding(horizontal = RowHorizontalPadding, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    // The summary wraps rather than truncating: it is where a collapsed section shows its values.
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.rotate(chevronRotation),
                )
            }
            AnimatedVisibility(
                visibleState = visibility,
                enter = expandVertically(ExpandSpring) + fadeIn(),
                exit = shrinkVertically(ExpandSpring) + fadeOut(),
            ) {
                Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    content()
                }
            }
        }
    }
}

/** A row's title and optional supporting line, taking the row's free width. */
@Composable
private fun RowScopeText(title: String, subtitle: String?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Column(modifier) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        )
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Hosts a surface's `<Surface>TuneContent` inside a section card: its groups sit at the rows' 16dp
 * inset and 24dp apart, as in the Tune sheet, so Settings and the sheet show the same controls.
 */
@Composable
fun SettingsTuneHost(content: @Composable ColumnScope.() -> Unit) {
    Column(
        // 8dp below, so whatever follows (a subheader's 16dp, a row's own padding) keeps the 8dp rhythm.
        Modifier.fillMaxWidth().padding(start = RowHorizontalPadding, end = RowHorizontalPadding, top = 16.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        content = content,
    )
}

/** An on/off setting; the whole row toggles it. */
@Composable
fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = RowMinHeight)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = RowHorizontalPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowScopeText(title, subtitle, Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * A row that opens another screen; [value] shows what is chosen there now. [startInset] moves its
 * text in, onto [ChoiceLabelInset] when it follows a list of choices.
 */
@Composable
fun SettingsNavigationRow(
    title: String,
    onClick: () -> Unit,
    value: String? = null,
    modifier: Modifier = Modifier,
    startInset: Dp = RowHorizontalPadding,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = RowMinHeight)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = startInset, end = RowHorizontalPadding, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowScopeText(title, value, Modifier.weight(1f))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One choice of a single-choice list; the whole row selects it. [trailing] sits after the text (a play button). */
@Composable
fun SettingsRadioRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ChoiceRowMinHeight)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(start = RowHorizontalPadding, end = if (trailing == null) RowHorizontalPadding else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChoiceRadio(selected)
        RowScopeText(title, subtitle, Modifier.weight(1f).padding(vertical = 8.dp))
        if (trailing != null) trailing()
    }
}

/** A checkbox row, for "include in the mode button's cycle". A disabled row keeps the last item in. */
@Composable
fun SettingsCheckRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = RowMinHeight)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange)
            .padding(start = 4.dp, end = RowHorizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        }
        Spacer(Modifier.width(4.dp))
        RowScopeText(title, subtitle, Modifier.weight(1f).padding(vertical = 8.dp), enabled = enabled)
    }
}

/**
 * A short single choice under its title, laid out by [TuneChoice]'s rule (segments while they fit,
 * radio rows when they don't), at the rows' inset.
 */
@Composable
fun <T> SettingsSegmentedRow(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    TuneChoice(
        label = title,
        supporting = subtitle,
        options = options,
        selected = selected,
        optionLabel = label,
        onSelect = onSelect,
        modifier = modifier.fillMaxWidth().padding(horizontal = RowHorizontalPadding, vertical = 12.dp),
    )
}

/** A small heading inside a section: 16dp above it, and the next row's own padding below. */
@Composable
fun SettingsSubheader(
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = RowHorizontalPadding, end = RowHorizontalPadding, top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
    }
}

/**
 * A nested collapsible group inside a section ("Advanced"), collapsed until asked for. Its content is
 * indented under the header unless [indentContent] is false (a list of choices, which keeps the
 * choice column).
 */
@Composable
fun SettingsDisclosure(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    summary: String? = null,
    indentContent: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        val chevronRotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = RowMinHeight)
                .clickable(onClickLabel = if (expanded) "Collapse" else "Expand", onClick = onToggle)
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                .padding(horizontal = RowHorizontalPadding, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RowScopeText(title, summary, Modifier.weight(1f))
            Icon(
                Icons.Default.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(chevronRotation),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(ExpandSpring) + fadeIn(),
            exit = shrinkVertically(ExpandSpring) + fadeOut(),
        ) {
            // Indented under its header, so what's inside reads as part of it.
            Column(Modifier.fillMaxWidth().padding(start = if (indentContent) RowHorizontalPadding else 0.dp), content = content)
        }
    }
}

// The kept full screens (About, Developer options, Themes, Sound packs) group rows in cards.

data class SettingsItemData(
    val title: String,
    val subtitle: String? = null,
    val icon: @Composable (() -> Unit)? = null,
    val action: @Composable (() -> Unit)? = null,
    val onClick: (() -> Unit)? = null
)

/** A titled card of rows for the kept full screens. */
@Composable
fun SettingsCardGroup(
    title: String? = null,
    items: List<SettingsItemData>
) {
    SettingsGroup(title = title) {
        items.forEach { item ->
            SettingsItem(
                title = item.title,
                subtitle = item.subtitle,
                icon = item.icon,
                action = item.action,
                onClick = item.onClick,
            )
        }
    }
}

/** A titled card holding arbitrary content, for the kept full screens. */
@Composable
fun SettingsGroup(
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(start = RowHorizontalPadding, bottom = 8.dp, top = 16.dp)
                    .semantics { heading() },
            )
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                content = content
            )
        }
    }
}

@Composable
fun SettingsItem(
    title: String,
    subtitle: String? = null,
    icon: @Composable (() -> Unit)? = null,
    action: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RowMinHeight)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = RowHorizontalPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier.padding(end = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                icon()
            }
        }
        RowScopeText(title, subtitle, Modifier.weight(1f))
        if (action != null) {
            Box(modifier = Modifier.padding(start = 16.dp)) {
                action()
            }
        }
    }
}
