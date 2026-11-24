package dev.arnv.bluke.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.arnv.bluke.sound.SwitchType
import dev.arnv.bluke.sound.builtInSoundProfileId
import dev.arnv.bluke.sound.customSoundProfileId
import dev.arnv.bluke.ui.KeyboardThemeCatalog
import dev.arnv.bluke.ui.KeyboardThemeDefinition
import dev.arnv.bluke.ui.KeyboardTuneContent
import dev.arnv.bluke.ui.KeyboardTuneOptions
import dev.arnv.bluke.ui.KeyboardTuneState
import dev.arnv.bluke.ui.KeyboardView
import dev.arnv.bluke.ui.ChoiceLabelInset
import dev.arnv.bluke.ui.ChoiceRadio
import dev.arnv.bluke.ui.ChoiceRowMinHeight
import dev.arnv.bluke.ui.SettingsDisclosure
import dev.arnv.bluke.ui.SettingsNavigationRow
import dev.arnv.bluke.ui.SettingsRadioRow
import dev.arnv.bluke.ui.SettingsSubheader
import dev.arnv.bluke.ui.SettingsTuneHost
import dev.arnv.bluke.ui.SurfaceHaptics
import dev.arnv.bluke.ui.ThemeChoice
import dev.arnv.bluke.ui.TuneSlider
import dev.arnv.bluke.ui.HapticEvent
import dev.arnv.bluke.ui.profileId
import kotlin.math.roundToInt

/** One key sound the Keyboard section lists: a built-in switch or an imported pack. */
data class KeySoundChoice(val id: String, val name: String)

/**
 * Everything Settings › Keyboard shows, read from preferences by [KeyboardSettingsController]. [tune]
 * is the keyboard's Tune state, which the section hosts through [KeyboardTuneContent].
 */
data class KeyboardSettingsState(
    val tune: KeyboardTuneState,
    val options: KeyboardTuneOptions,
    val keySensitivity: Float,
    val keySoundsOn: Boolean,
    /** The chosen keyboard theme, which the layout preview is drawn in. */
    val theme: KeyboardThemeDefinition = KeyboardThemeCatalog.defaultTheme,
) {
    val selectedSoundId: String get() = tune.sound.profileId

    /** Every key sound, built-in switches first, then imported packs. */
    val sounds: List<KeySoundChoice>
        get() = SwitchType.entries.map { KeySoundChoice(builtInSoundProfileId(it), it.displayName) } +
            options.customSoundPacks.map { (id, name) -> KeySoundChoice(customSoundProfileId(id), name) }
}

/** What the Keyboard section's controls do; [KeyboardSettingsController] writes preferences. */
interface KeyboardSettingsActions {
    fun setTune(next: KeyboardTuneState)
    fun setKeySensitivity(value: Float)
    fun openThemes()
    fun selectSound(id: String)
    fun turnSoundsOff()
    fun playSound(id: String)
    fun openSoundPacks()
}

/** The Keyboard section header's one-line summary of what's chosen now. */
fun KeyboardSettingsState.summary(): String {
    val sound = if (keySoundsOn) sounds.firstOrNull { it.id == selectedSoundId }?.name else "key sounds off"
    val touchArea = if (keySensitivity.roundToInt() != DEFAULT_KEY_SENSITIVITY.roundToInt()) "touch area ${keyTouchAreaLabel(keySensitivity)}" else null
    return listOfNotNull(tune.geometry.displayName, tune.characterLayout.displayName, tune.modifierStyle.displayName, theme.name, sound, touchArea)
        .joinToString(" · ")
}

/** The key touch area a fresh install starts at. */
internal const val DEFAULT_KEY_SENSITIVITY = 6f

/** The key sound in use, or "Off", for the Key sound disclosure's summary. */
internal fun KeyboardSettingsState.soundName(): String =
    sounds.firstOrNull { keySoundsOn && it.id == selectedSoundId }?.name ?: "Off"

/** The key touch area's word and step for [value] (0..10), so every snap of its slider changes the label. */
internal fun keyTouchAreaLabel(value: Float): String {
    val word = when {
        value <= 3f -> "Precise"
        value >= 8f -> "Broad"
        else -> "Balanced"
    }
    return "$word\u00A0(${value.roundToInt()})"
}

/** The Keyboard section's body, shown inside its [dev.arnv.bluke.ui.SettingsSectionCard]. [haptics] ticks the touch area's steps. */
@Composable
fun KeyboardSettingsSection(state: KeyboardSettingsState, actions: KeyboardSettingsActions, haptics: SurfaceHaptics?) {
    KeyboardSettingsBody(state, actions, haptics)
}

/**
 * [KeyboardSettingsSection] with its disclosures' starting positions, so a screenshot can show them
 * open. The keyboard's Tune content holds geometry, typing layout and modifier keys; around it
 * Settings adds the preview, the theme (folded, with its editor a row away), the key sound list (Off, then
 * each sound with a play button) and, folded last, Advanced with the key touch area.
 */
@Composable
internal fun KeyboardSettingsBody(
    state: KeyboardSettingsState,
    actions: KeyboardSettingsActions,
    haptics: SurfaceHaptics?,
    initiallySoundOpen: Boolean = false,
    initiallyThemeOpen: Boolean = false,
) {
    var themeOpen by rememberSaveable { mutableStateOf(initiallyThemeOpen) }
    var soundOpen by rememberSaveable { mutableStateOf(initiallySoundOpen) }
    var advancedOpen by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        GeometryPreview(state)
        SettingsTuneHost {
            // Theme and key sound are listed below, folded and with their editors, so the Tune content leaves them out.
            KeyboardTuneContent(
                state = state.tune,
                onChange = actions::setTune,
                options = state.options,
                showSound = false,
                showTheme = false,
            )
        }
        SettingsSubheader("Look & sound")
        SettingsDisclosure(
            title = "Theme",
            summary = state.theme.name,
            expanded = themeOpen,
            onToggle = { themeOpen = !themeOpen },
            // Not indented, so the chips and "Edit themes" start where the other chip groups do.
            indentContent = false,
        ) {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                ThemeChoice(state.options.themes, state.tune.themeId) { actions.setTune(state.tune.copy(themeId = it)) }
            }
            SettingsNavigationRow(title = "Edit themes", onClick = actions::openThemes)
        }
        SettingsDisclosure(
            title = "Key sound",
            summary = state.soundName(),
            expanded = soundOpen,
            onToggle = { soundOpen = !soundOpen },
            // Not indented, so its radios and labels share a column with the hosted Tune radios above.
            indentContent = false,
        ) {
            Column(Modifier.selectableGroup()) {
                SettingsRadioRow(
                    title = "Off",
                    selected = !state.keySoundsOn,
                    onClick = actions::turnSoundsOff,
                )
                state.sounds.forEach { sound ->
                    SettingsRadioRow(
                        title = sound.name,
                        selected = state.keySoundsOn && sound.id == state.selectedSoundId,
                        onClick = { actions.selectSound(sound.id) },
                        trailing = { PlaySoundButton(sound.name) { actions.playSound(sound.id) } },
                    )
                }
            }
            SettingsNavigationRow(title = "Import or manage packs…", onClick = actions::openSoundPacks, startInset = ChoiceLabelInset)
        }
        // A divider ends "Look & sound", so Advanced (typing accuracy) doesn't read as part of it.
        HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
        SettingsDisclosure(
            title = "Advanced",
            summary = "Key touch area: ${keyTouchAreaLabel(state.keySensitivity)}",
            expanded = advancedOpen,
            onToggle = { advancedOpen = !advancedOpen },
            indentContent = false,
        ) {
            TuneSlider(
                label = "Key touch area",
                valueLabel = keyTouchAreaLabel(state.keySensitivity),
                supporting = "Broader keys are easier to hit but catch the key beside more often.",
                value = state.keySensitivity,
                onValueChange = { value ->
                    // The slider snaps to whole steps but reports every drag frame; write and tick only when the step changes.
                    if (value.roundToInt() != state.keySensitivity.roundToInt()) {
                        haptics?.perform(HapticEvent.TICK)
                        actions.setKeySensitivity(value)
                    }
                },
                valueRange = 0f..10f,
                steps = 9,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            )
        }
    }
}

/**
 * A small, still picture of the chosen geometry in the chosen theme and typing layout. An empty
 * pointer handler on top keeps the keys from reacting to touch; the screen still scrolls.
 */
@Composable
private fun GeometryPreview(state: KeyboardSettingsState) {
    Box(
        Modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .fillMaxWidth()
            .aspectRatio(2.3f)
            // Small corners, so the preview's rounding sits concentric inside the card's 16dp inset.
            .clip(MaterialTheme.shapes.small)
            .clearAndSetSemantics {
                contentDescription = "Preview of ${state.tune.geometry.displayName}, ${state.tune.characterLayout.displayName}"
            },
    ) {
        // Drawn at font scale 1: the caps are sized to the preview, not the text, so larger text would spill out.
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1f)) {
            KeyboardView(
                geometry = state.tune.geometry,
                theme = state.theme,
                characterLayout = state.tune.characterLayout,
                modifierStyle = state.tune.modifierStyle,
                activePressedKeys = emptyList(),
                isCapsLockActive = false,
                isNumLockActive = false,
                isScrollLockActive = false,
                onKeyPressChange = { _, _ -> },
            )
        }
        Box(Modifier.matchParentSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } })
    }
}

/** Said on the one row of a cycle that can't be unchecked because it is the last. */
internal const val LAST_IN_CYCLE_NOTE = "The toolbar needs at least one"

/**
 * One choice of a library list (a sound pack, a keyboard theme): the whole row selects it behind a
 * leading radio. [leading] (a theme's swatches) sits before the title; [trailing] controls (play,
 * more) sit 8dp apart so a near miss lands on nothing.
 */
@Composable
internal fun LibraryChoiceRow(
    title: String,
    selected: Boolean,
    onSelect: () -> Unit,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ChoiceRowMinHeight)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(start = 16.dp, end = if (trailing == null) 16.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChoiceRadio(selected)
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                trailing()
            }
        }
    }
}

/** A 48dp play button that sounds a key sound once. */
@Composable
internal fun PlaySoundButton(name: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(Icons.Default.PlayArrow, contentDescription = "Play $name", tint = MaterialTheme.colorScheme.primary)
    }
}
