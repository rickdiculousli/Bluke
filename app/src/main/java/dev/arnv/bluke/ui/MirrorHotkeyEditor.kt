package dev.arnv.bluke.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The modifiers a hotkey can carry: the glyph its button face shows, and the modifier key whose
 * name the key list uses too, so one modifier reads the same everywhere.
 */
private val MODIFIER_CHOICES = listOf(
    Triple(HotkeyModifiers.CTRL, "⌃", KeyboardLayouts.MOD_LCTRL),
    Triple(HotkeyModifiers.SHIFT, "⇧", KeyboardLayouts.MOD_LSHIFT),
    Triple(HotkeyModifiers.ALT, "⌥", KeyboardLayouts.MOD_LALT),
    Triple(HotkeyModifiers.GUI, "⌘", KeyboardLayouts.MOD_LWIN),
)

private enum class AddBy(val label: String) { KEY("Choose a key"), CHARACTER("Type a character") }

/** The button being made: how, its modifiers, and the key or character so far. */
@Stable
private class NewHotkey {
    var addBy by mutableStateOf(AddBy.KEY)
    var modifiers by mutableIntStateOf(0)
    var chosenKey by mutableStateOf<Int?>(null)
    var typed by mutableStateOf("")

    fun fromCharacter(layout: KeyboardCharacterLayout): MirrorHotkey? =
        typed.takeIf { it.isNotEmpty() }?.let { hotkeyForCharacter(it, layout) }

    /** The button Add would add now, or null while it has no key. */
    fun adding(layout: KeyboardCharacterLayout): MirrorHotkey? = when (addBy) {
        AddBy.KEY -> chosenKey?.let { MirrorHotkey(it, modifiers) }
        AddBy.CHARACTER -> fromCharacter(layout)?.let { it.copy(modifiers = it.modifiers or modifiers) }
    }

    fun clear() {
        chosenKey = null
        typed = ""
        modifiers = 0
    }
}

/**
 * Mirror's hotkey buttons, edited over the dimmed picture: the buttons on the left (rename, move,
 * remove), a new one on the right, made by choosing a key or typing the character it types under
 * [typingLayout], and added from the header beside Done, so the key list keeps the height. Every
 * change goes to [onChange] at once; Done or Back closes it.
 */
@Composable
internal fun MirrorHotkeyEditor(
    hotkeys: List<MirrorHotkey>,
    typingLayout: KeyboardCharacterLayout,
    onChange: (List<MirrorHotkey>) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onDone)
    val draft = remember { NewHotkey() }
    val full = hotkeys.size >= MAX_MIRROR_HOTKEYS
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f))
            // The picture and controls under it take no touches while the editor is open.
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false).consume() } }
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("mirror_hotkey_editor"),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.widthIn(max = 760.dp).fillMaxHeight().semantics { paneTitle = "Hotkey buttons" },
        ) {
            Column(Modifier.padding(start = 24.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Hotkey buttons",
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    Text(
                        "${hotkeys.size} of $MAX_MIRROR_HOTKEYS",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!full) {
                        val adding = draft.adding(typingLayout)
                        Button(
                            onClick = {
                                adding?.let { onChange(hotkeys + it) }
                                draft.clear()
                            },
                            enabled = adding != null,
                            modifier = Modifier.testTag("mirror_hotkey_add"),
                        ) {
                            Text(adding?.let { "Add ${it.displayLabel}" } ?: "Add", maxLines = 1)
                        }
                    }
                    TextButton(onClick = onDone, modifier = Modifier.testTag("mirror_hotkey_editor_done")) {
                        Text("Done")
                    }
                }
                Row(Modifier.weight(1f).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    ButtonList(hotkeys, onChange, Modifier.weight(1f))
                    if (full) {
                        Text(
                            "Remove a button to add another.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f).padding(top = 4.dp),
                        )
                    } else {
                        NewButton(draft, typingLayout, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** The buttons as they sit on the surface, top to bottom: each one's face, its name, and where it goes. */
@Composable
private fun ButtonList(hotkeys: List<MirrorHotkey>, onChange: (List<MirrorHotkey>) -> Unit, modifier: Modifier) {
    val scroll = rememberScrollState()
    Column(
        modifier
            .fillMaxHeight()
            .scrollCue(scroll, MaterialTheme.colorScheme.onSurfaceVariant)
            .verticalScroll(scroll)
            // A gutter for the scroll cue's thumb, clear of the remove buttons.
            .padding(end = SCROLL_GUTTER),
    ) {
        SectionLabel("Your buttons")
        if (hotkeys.isEmpty()) {
            Text(
                "No buttons yet. Choose a key on the right, then Add.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        hotkeys.forEachIndexed { index, hotkey ->
            fun moved(to: Int) = hotkeys.toMutableList().apply { add(to, removeAt(index)) }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("mirror_hotkey_row_$index"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The button as the surface draws it, before any name: what its key alone would read.
                MirrorHotkeyFace(hotkey.copy(label = null).displayLabel)
                OutlinedTextField(
                    value = hotkey.label.orEmpty(),
                    onValueChange = { name ->
                        val label = name.take(MAX_HOTKEY_LABEL).takeIf { it.isNotBlank() }
                        onChange(hotkeys.toMutableList().apply { set(index, hotkey.copy(label = label)) })
                    },
                    placeholder = { Text("Name", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp)
                        .semantics { contentDescription = "Name for ${hotkey.copy(label = null).spokenLabel}" }
                        .testTag("mirror_hotkey_name_$index"),
                )
                IconButton(onClick = { onChange(moved(index - 1)) }, enabled = index > 0) {
                    Icon(Icons.Default.ArrowUpward, contentDescription = "Move ${hotkey.spokenLabel} up")
                }
                IconButton(onClick = { onChange(moved(index + 1)) }, enabled = index < hotkeys.lastIndex) {
                    Icon(Icons.Default.ArrowDownward, contentDescription = "Move ${hotkey.spokenLabel} down")
                }
                IconButton(
                    onClick = { onChange(hotkeys.toMutableList().apply { removeAt(index) }) },
                    modifier = Modifier.testTag("mirror_hotkey_remove_$index"),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Remove ${hotkey.spokenLabel}")
                }
            }
        }
    }
}

/**
 * A new button: by key or by character, then its modifiers (wrapping when large text can't fit
 * them on one line), then the key list or the character field in all the height left.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewButton(draft: NewHotkey, typingLayout: KeyboardCharacterLayout, modifier: Modifier) {
    val keysScroll = rememberScrollState()
    Column(modifier.fillMaxHeight()) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            AddBy.entries.forEachIndexed { i, option ->
                SegmentedButton(
                    selected = draft.addBy == option,
                    onClick = { draft.addBy = option },
                    shape = SegmentedButtonDefaults.itemShape(i, AddBy.entries.size),
                    label = { Text(option.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.testTag("mirror_add_by_${option.name.lowercase()}"),
                )
            }
        }
        FlowRow(
            Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MODIFIER_CHOICES.forEach { (bit, glyph, keyCode) ->
                val name = hotkeyKeyName(keyCode)
                FilterChip(
                    selected = draft.modifiers and bit != 0,
                    onClick = { draft.modifiers = draft.modifiers xor bit },
                    label = {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            // The glyph the button's face will show; TalkBack reads the name.
                            Text(glyph, maxLines = 1, modifier = Modifier.clearAndSetSemantics {})
                            Text(name, maxLines = 1)
                        }
                    },
                    modifier = Modifier.testTag("mirror_modifier_$name"),
                )
            }
        }
        Column(
            Modifier
                .weight(1f)
                .scrollCue(keysScroll, MaterialTheme.colorScheme.onSurfaceVariant)
                .verticalScroll(keysScroll)
                .padding(end = SCROLL_GUTTER),
        ) {
            when (draft.addBy) {
                AddBy.KEY -> MirrorKeyGroup.entries.forEach { group ->
                    val keys = MIRROR_HOTKEY_KEYS.filter { it.group == group }
                    if (keys.isEmpty()) return@forEach
                    Text(
                        group.title,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                    FlowRow(
                        Modifier.selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        keys.forEach { choice ->
                            KeyChoice(choice, selected = draft.chosenKey == choice.keyCode) {
                                draft.chosenKey = if (draft.chosenKey == choice.keyCode) null else choice.keyCode
                            }
                        }
                    }
                }
                AddBy.CHARACTER -> {
                    val fromCharacter = draft.fromCharacter(typingLayout)
                    val typed = draft.typed
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { draft.typed = it.takeLast(1) },
                        label = { Text("Character") },
                        singleLine = true,
                        isError = typed.isNotEmpty() && fromCharacter == null,
                        supportingText = {
                            Text(
                                when {
                                    typed.isEmpty() -> "The key that types it on ${typingLayout.displayName}"
                                    fromCharacter == null -> "No key types “$typed” on ${typingLayout.displayName}"
                                    else -> "Types “$typed” as ${fromCharacter.displayLabel}"
                                },
                            )
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("mirror_hotkey_character"),
                    )
                }
            }
        }
    }
}

private val MirrorKeyGroup.title: String
    get() = when (this) {
        MirrorKeyGroup.LETTERS -> "Letters"
        MirrorKeyGroup.DIGITS -> "Digits"
        MirrorKeyGroup.FUNCTION -> "Function keys"
        MirrorKeyGroup.NAVIGATION -> "Esc, Tab, Space, Enter and arrows"
        MirrorKeyGroup.MODIFIERS -> "Modifiers on their own"
    }

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp).semantics { heading() },
    )
}

@Composable
private fun KeyChoice(choice: MirrorKeyChoice, selected: Boolean, onClick: () -> Unit) {
    val shape = MaterialTheme.shapes.small
    val container = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest
    val content = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .clip(shape)
            .background(container)
            .border(1.dp, if (selected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 8.dp)
            .semantics { contentDescription = MirrorHotkey(choice.keyCode).spokenLabel }
            .testTag("mirror_key_${choice.keyCode}"),
        contentAlignment = Alignment.Center,
    ) {
        Text(choice.name, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
    }
}

/** Room at a scrolling column's end for its scroll cue's thumb, clear of the controls. */
private val SCROLL_GUTTER = 16.dp
