package dev.arnv.bluke.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import dev.arnv.bluke.ui.LocalHapticPerformer
import dev.arnv.bluke.ui.SurfaceHaptics
import dev.arnv.bluke.ui.ViewHapticPerformer
import dev.arnv.bluke.ui.glideLabel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.arnv.bluke.ui.GamepadTuneContent
import dev.arnv.bluke.ui.InputMode
import dev.arnv.bluke.ui.MultimediaTuneContent
import dev.arnv.bluke.ui.TouchpadModifierPosition
import dev.arnv.bluke.ui.TouchpadTuneContent
import dev.arnv.bluke.ui.speedLabel
import dev.arnv.bluke.ui.SettingsCheckRow
import dev.arnv.bluke.ui.SettingsDisclosure
import dev.arnv.bluke.ui.SettingsNavigationRow
import dev.arnv.bluke.ui.SettingsSectionCard
import dev.arnv.bluke.ui.SettingsSegmentedRow
import dev.arnv.bluke.ui.SettingsSwitchRow
import dev.arnv.bluke.ui.SettingsTuneHost
import dev.arnv.bluke.ui.theme.AccentColors
import dev.arnv.bluke.ui.theme.AppearanceOption
import dev.arnv.bluke.ui.theme.PaletteStyle
import dev.arnv.bluke.ui.theme.ThemeMode
import dev.arnv.bluke.ui.theme.appearanceOptions

/**
 * The one Settings screen, stateless: [state] and [keyboard] say what every control shows, [actions]
 * and [keyboardActions] what each does, and [expanded] which sections are open. The Settings activity
 * feeds it from preferences; screenshot tests feed it directly. [startCollapsed] starts the large top
 * bar collapsed, for an open routed to a section, so the card scrolled to isn't half under it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsContent(
    state: SettingsState,
    actions: SettingsActions,
    keyboard: KeyboardSettingsState,
    keyboardActions: KeyboardSettingsActions,
    expanded: Set<SettingsSection>,
    onToggleSection: (SettingsSection) -> Unit,
    connectionAdvancedExpanded: Boolean,
    onToggleConnectionAdvanced: () -> Unit,
    onBack: () -> Unit,
    listState: LazyListState = rememberLazyListState(),
    startCollapsed: Boolean = false,
) {
    // The bar only learns how far it collapses once something scrolls it, so a collapsed start seeds that
    // distance from the large bar's own heights. The state is saved, so a rotation keeps the user's scroll.
    val collapseLimit = with(LocalDensity.current) {
        (TopAppBarDefaults.LargeAppBarCollapsedHeight - TopAppBarDefaults.LargeAppBarExpandedHeight).toPx()
    }
    val topBarState = rememberTopAppBarState(
        initialHeightOffsetLimit = collapseLimit,
        initialHeightOffset = if (startCollapsed) collapseLimit else 0f,
    )
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(topBarState)
    val haptics = rememberSettingsHaptics(state.hapticsEnabled)
    // Settings draws in the app's theme, so the appearance rule asks about the scheme on screen now.
    val systemDark = isSystemInDarkTheme()
    val appearance = state.appearanceOptions(systemDark)
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        val layoutDirection = LocalLayoutDirection.current
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = padding.calculateStartPadding(layoutDirection) + 16.dp,
                end = padding.calculateEndPadding(layoutDirection) + 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(SettingsListItems, key = { _, item -> item.key }) { index, item ->
                val section = when (item) {
                    is SettingsListItem.Label -> {
                        GroupLabel(item.text, first = index == 0)
                        return@itemsIndexed
                    }
                    is SettingsListItem.Section -> item.section
                }
                SettingsSectionCard(
                    title = section.title,
                    summary = when (section) {
                        SettingsSection.KEYBOARD -> keyboard.summary()
                        else -> state.summary(section, systemDark)
                    },
                    icon = section.icon,
                    expanded = section in expanded,
                    onToggle = { onToggleSection(section) },
                ) {
                    when (section) {
                        SettingsSection.APPEARANCE -> AppearanceSection(state, appearance, actions)
                        SettingsSection.FEEDBACK -> FeedbackSection(state, actions)
                        SettingsSection.KEYBOARD ->
                            KeyboardSettingsSection(keyboard, keyboardActions, haptics)
                        SettingsSection.TOUCHPAD -> TouchpadSection(state, actions, haptics)
                        SettingsSection.CONTROLLER -> ControllerSection(state, actions)
                        SettingsSection.MULTIMEDIA -> MultimediaSection(state, actions, haptics)
                        SettingsSection.INPUT_MODES -> InputModesSection(state, actions)
                        SettingsSection.CONNECTION ->
                            ConnectionSection(state, actions, connectionAdvancedExpanded, onToggleConnectionAdvanced)
                        SettingsSection.HELP_AND_ABOUT -> HelpSection(state, actions)
                    }
                }
            }
        }
    }
}

private val SettingsListItem.key: String
    get() = when (this) {
        is SettingsListItem.Label -> "label:$text"
        is SettingsListItem.Section -> section.name
    }

/** A group's small heading above its section cards. */
@Composable
private fun GroupLabel(text: String, first: Boolean) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = if (first) 4.dp else 16.dp, bottom = 4.dp)
            .semantics { heading() },
    )
}

internal val SettingsSection.title: String
    get() = when (this) {
        SettingsSection.APPEARANCE -> "Appearance"
        SettingsSection.FEEDBACK -> "Feedback"
        SettingsSection.KEYBOARD -> "Keyboard"
        SettingsSection.TOUCHPAD -> "Touchpad"
        SettingsSection.CONTROLLER -> "Gamepad"
        SettingsSection.MULTIMEDIA -> "Multimedia"
        SettingsSection.INPUT_MODES -> "Input modes"
        SettingsSection.CONNECTION -> "Connection"
        SettingsSection.HELP_AND_ABOUT -> "Help & about"
    }

private val SettingsSection.icon: ImageVector
    get() = when (this) {
        SettingsSection.APPEARANCE -> Icons.Default.Palette
        SettingsSection.FEEDBACK -> Icons.Default.Vibration
        SettingsSection.KEYBOARD -> Icons.Default.Keyboard
        SettingsSection.TOUCHPAD -> Icons.Default.TouchApp
        SettingsSection.CONTROLLER -> Icons.Default.SportsEsports
        SettingsSection.MULTIMEDIA -> Icons.Default.PlayCircle
        SettingsSection.INPUT_MODES -> Icons.Default.SwapHoriz
        SettingsSection.CONNECTION -> Icons.Default.Bluetooth
        SettingsSection.HELP_AND_ABOUT -> Icons.AutoMirrored.Filled.HelpOutline
    }

/** Names for [AccentColors], in its order, for the header summary and TalkBack. */
private val AccentColorNames = listOf(
    "Bluke blue", "Red", "Pink", "Purple", "Deep purple", "Indigo", "Blue", "Light blue", "Cyan", "Teal",
    "Green", "Light green", "Lime", "Yellow", "Amber", "Orange", "Deep orange", "Brown", "Grey", "Blue grey",
)

private fun accentName(index: Int): String = AccentColorNames.getOrElse(index) { "Color ${index + 1}" }

/** The appearance controls that change something now, by the theme's own rule, with the system dark or not. */
internal fun SettingsState.appearanceOptions(systemDark: Boolean): List<AppearanceOption> =
    appearanceOptions(appearance.dynamicColor, appearance.themeMode.isDark(systemDark), dynamicColorAvailable)

/** Appearance's header line: the theme mode, then the value of each control that applies now. */
internal fun SettingsState.appearanceSummary(options: List<AppearanceOption>): String = buildList {
    add(
        when (appearance.themeMode) {
            ThemeMode.SYSTEM -> "System theme"
            ThemeMode.LIGHT -> "Light"
            ThemeMode.DARK -> "Dark"
        },
    )
    options.forEach { option ->
        when (option) {
            AppearanceOption.THEME_MODE -> Unit
            AppearanceOption.DYNAMIC_COLOR -> if (appearance.dynamicColor) add("dynamic colors")
            AppearanceOption.ACCENT -> add("${accentName(appearance.accentIndex)} accent")
            AppearanceOption.PALETTE_STYLE ->
                if (appearance.paletteStyle != PaletteStyle.DEFAULT) add("${appearance.paletteStyle.label} palette")
            AppearanceOption.BLACK_BACKGROUND -> if (appearance.blackBackground) add("pure black")
        }
    }
}.joinToString(" · ")

/** Connection › Advanced's line: what its switches do now, only those that change anything. */
internal fun SettingsState.connectionAdvancedSummary(): String = connectionAdvancedParts(onlyChanged = false).joinToString(" · ")

/**
 * The parts of [connectionAdvancedSummary]. With [onlyChanged], the device filter is left out while
 * it is at its default, so the Connection header names only what someone changed.
 */
private fun SettingsState.connectionAdvancedParts(onlyChanged: Boolean): List<String> = buildList {
    val defaults = SettingsState()
    if (!onlyChanged || hideUnknown != defaults.hideUnknown || hideUnsupported != defaults.hideUnsupported) {
        add(
            when {
                hideUnknown && hideUnsupported -> "Hiding unnamed and unlikely devices"
                hideUnknown -> "Hiding unnamed devices"
                hideUnsupported -> "Hiding unlikely devices"
                else -> "Listing every nearby device"
            },
        )
    }
    if (showMac) add("MAC shown")
    if (keepAudio) add("audio kept on phone")
}

/** The Connection header's line: reconnecting, then each advanced value that isn't its default. */
internal fun SettingsState.connectionSummary(): String =
    (listOf(if (autoConnect) "Reconnects on launch" else "Doesn't reconnect on launch") + connectionAdvancedParts(onlyChanged = true))
        .joinToString(" · ")

/** A section header's line: what the section holds now, in plain words. */
internal fun SettingsState.summary(section: SettingsSection, systemDark: Boolean = false): String = when (section) {
    SettingsSection.APPEARANCE -> appearanceSummary(appearanceOptions(systemDark))
    SettingsSection.FEEDBACK -> if (hapticsEnabled) "Haptics on" else "Haptics off"
    SettingsSection.KEYBOARD -> ""
    SettingsSection.TOUCHPAD -> buildList {
        add("Pointer ${speedLabel(touchpad.speeds.pointerSpeed)}")
        add("scroll ${speedLabel(touchpad.speeds.scrollSpeed)}")
        if (touchpad.invertScroll) add("inverted")
        add(if (touchpad.momentum) "momentum · ${glideLabel(touchpad.glide).lowercase()} glide" else "no momentum")
        if (touchpad.modifierPosition != TouchpadModifierPosition.OFF) add("modifier key strip ${touchpad.modifierPosition.name.lowercase()}")
    }.joinToString(" · ")
    SettingsSection.CONTROLLER -> "${gamepad.profile.label} profile"
    SettingsSection.MULTIMEDIA ->
        if (multimedia.hardwareVolumeButtons) "Volume buttons control the host" else "Volume buttons stay on this phone"
    SettingsSection.INPUT_MODES -> {
        val modes = InputMode.entries.filter { it.preferenceKey in cycleModes }
        if (modes.size == InputMode.entries.size) "Toolbar visits all modes"
        else "Toolbar visits " + modes.joinToString(", ") { it.displayName }
    }
    SettingsSection.CONNECTION -> connectionSummary()
    SettingsSection.HELP_AND_ABOUT -> "Version $versionName"
}

/** Settings' haptics: the Tune controls' ticks, played while the haptics setting shown is on. */
@Composable
private fun rememberSettingsHaptics(enabled: Boolean): SurfaceHaptics {
    val view = LocalView.current
    val performer = LocalHapticPerformer.current ?: remember(view) { ViewHapticPerformer(view) }
    val currentEnabled by rememberUpdatedState(enabled)
    return remember(performer) { SurfaceHaptics({ currentEnabled }, performer) }
}

@Composable
private fun SectionNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

private val RevealSpring = spring<IntSize>(stiffness = Spring.StiffnessMedium)

/** A block a setting shows or hides; it grows and fades in rather than jumping into place. */
@Composable
internal fun Reveal(visible: Boolean, content: @Composable ColumnScope.() -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(RevealSpring) + fadeIn(),
        exit = shrinkVertically(RevealSpring) + fadeOut(),
    ) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}

/** Renders exactly the controls [options] lists, in its order; the theme owns the rule. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppearanceSection(state: SettingsState, options: List<AppearanceOption>, actions: SettingsActions) {
    val appearance = state.appearance
    // Every option the state can offer is laid out in display order; each one not in [options] folds away.
    Reveal(AppearanceOption.THEME_MODE in options) {
        SettingsSegmentedRow(
            title = "Dark theme",
            options = ThemeMode.entries,
            selected = appearance.themeMode,
            label = { it.label },
            onSelect = actions::setThemeMode,
        )
    }
    Reveal(AppearanceOption.DYNAMIC_COLOR in options) {
        SettingsSwitchRow(
            title = "Dynamic colors",
            subtitle = "Take the app's colors from your wallpaper",
            checked = appearance.dynamicColor,
            onCheckedChange = actions::setDynamicColor,
        )
    }
    Reveal(AppearanceOption.ACCENT in options) {
        // Titled like its sibling controls (body title, value under it), not as a group heading.
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column {
                Text("Accent color", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    accentName(appearance.accentIndex),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth().selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AccentColors.forEachIndexed { index, color ->
                    val selected = index == appearance.accentIndex
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(color)
                            .then(
                                // An unselected swatch keeps a hairline edge, so a pale one still shows against the card.
                                if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                else Modifier.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                            )
                            .selectable(selected = selected, role = Role.RadioButton) { actions.setAccentColor(index) }
                            .semantics { contentDescription = accentName(index) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) {
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface, modifier = Modifier.size(24.dp)) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(3.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    Reveal(AppearanceOption.PALETTE_STYLE in options) {
        SettingsSegmentedRow(
            title = "Palette style",
            options = PaletteStyle.entries,
            selected = appearance.paletteStyle,
            label = { it.label },
            onSelect = actions::setPaletteStyle,
        )
    }
    Reveal(AppearanceOption.BLACK_BACKGROUND in options) {
        SettingsSwitchRow(
            title = "Pure black",
            subtitle = "Black backgrounds while the theme is dark, for OLED screens",
            checked = appearance.blackBackground,
            onCheckedChange = actions::setBlackBackground,
        )
    }
}

@Composable
private fun FeedbackSection(state: SettingsState, actions: SettingsActions) {
    SettingsSwitchRow(
        title = "Haptics",
        subtitle = "A short vibration on key and button presses",
        checked = state.hapticsEnabled,
        onCheckedChange = actions::setHaptics,
    )
}

@Composable
private fun TouchpadSection(state: SettingsState, actions: SettingsActions, haptics: SurfaceHaptics) {
    SettingsTuneHost { TouchpadTuneContent(state.touchpad, actions::setTouchpad, haptics) }
}

@Composable
private fun ControllerSection(state: SettingsState, actions: SettingsActions) {
    SettingsTuneHost { GamepadTuneContent(state.gamepad, actions::setGamepad) }
}

@Composable
private fun MultimediaSection(state: SettingsState, actions: SettingsActions, haptics: SurfaceHaptics) {
    SettingsTuneHost { MultimediaTuneContent(state.multimedia, actions::setMultimedia, haptics, showPointer = false) }
}

@Composable
private fun InputModesSection(state: SettingsState, actions: SettingsActions) {
    SectionNote("The toolbar's mode button visits the checked modes, in this order.")
    InputMode.entries.forEach { mode ->
        val checked = mode.preferenceKey in state.cycleModes
        SettingsCheckRow(
            title = mode.displayName,
            checked = checked,
            // The cycle always keeps one mode, so the last checked one can't be unchecked.
            enabled = !checked || state.cycleModes.size > 1,
            subtitle = listOfNotNull(
                if (mode == InputMode.MIRROR) "Needs bluke-mirror on the host" else null,
                if (checked && state.cycleModes.size <= 1) LAST_IN_CYCLE_NOTE else null,
            ).joinToString(" · ").ifEmpty { null },
            onCheckedChange = { actions.toggleCycleMode(mode) },
        )
    }
}

@Composable
private fun ConnectionSection(
    state: SettingsState,
    actions: SettingsActions,
    advancedExpanded: Boolean,
    onToggleAdvanced: () -> Unit,
) {
    SettingsSwitchRow(
        title = "Reconnect on launch",
        subtitle = "Connect to the last host when Bluetooth is ready",
        checked = state.autoConnect,
        onCheckedChange = actions::setAutoConnect,
    )
    SettingsDisclosure(
        title = "Advanced",
        summary = state.connectionAdvancedSummary(),
        expanded = advancedExpanded,
        onToggle = onToggleAdvanced,
    ) {
        SettingsSwitchRow(
            title = "Hide unnamed devices",
            subtitle = "Nearby list only: skip devices without a name",
            checked = state.hideUnknown,
            onCheckedChange = actions::setHideUnknown,
        )
        SettingsSwitchRow(
            title = "Hide unlikely devices",
            subtitle = "Nearby list only: skip devices that don't look able to take input",
            checked = state.hideUnsupported,
            onCheckedChange = actions::setHideUnsupported,
        )
        SettingsSwitchRow(
            title = "Show MAC addresses",
            subtitle = "Tell apart devices with the same name",
            checked = state.showMac,
            onCheckedChange = actions::setShowMac,
        )
        SettingsSwitchRow(
            title = "Keep audio on this phone",
            subtitle = "Best effort, when a host takes over the phone's audio",
            checked = state.keepAudio,
            onCheckedChange = actions::setKeepAudio,
        )
    }
}

@Composable
private fun HelpSection(state: SettingsState, actions: SettingsActions) {
    SettingsNavigationRow(
        title = "Help",
        value = "Pairing, repair, controllers and limits",
        onClick = actions::openHelp,
    )
    SettingsNavigationRow(title = "About", value = "Developer and licenses", onClick = actions::openAbout)
    if (state.developerMode) {
        SettingsNavigationRow(title = "Developer options", value = "Testing and debugging", onClick = actions::openDeveloperOptions)
    }
}
