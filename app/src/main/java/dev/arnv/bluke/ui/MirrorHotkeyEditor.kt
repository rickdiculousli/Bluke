package dev.arnv.bluke.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The modifiers a hotkey can carry, as the editor names them: both platforms' names where they differ. */
private val MODIFIER_CHOICES = listOf(
    HotkeyModifiers.CTRL to "Ctrl",
    HotkeyModifiers.SHIFT to "Shift",
    HotkeyModifiers.ALT to "Alt/Option",
    HotkeyModifiers.GUI to "Cmd/Win",
)

private enum class AddBy(val label: String) { KEY("Choose a key"), CHARACTER("Type a character") }

/**
 * Mirror's hotkey buttons, edited over the dimmed picture: the buttons on the left (rename, move,
 * remove), a new one on the right, made by choosing a key or typing the character it types under
 * [typingLayout]. Every change goes to [onChange] at once; Done or Back closes it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MirrorHotkeyEditor(
    hotkeys: List<MirrorHotkey>,
    typingLayout: KeyboardCharacterLayout,
    onChange: (List<MirrorHotkey>) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onDone)
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
            modifier = Modifier.widthIn(max = 760.dp).fillMaxHeight(),
        ) {
            Column(Modifier.padding(start = 24.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Hotkey buttons",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    Text(
                        "${hotkeys.size} of $MAX_MIRROR_HOTKEYS",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onDone, modifier = Modifier.padding(start = 8.dp).testTag("mirror_hotkey_editor_done")) {
                        Text("Done")
                    }
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    ButtonList(hotkeys, onChange, Modifier.weight(1f))
                    AddButton(
                        full = hotkeys.size >= MAX_MIRROR_HOTKEYS,
                        typingLayout = typingLayout,
                        onAdd = { onChange(hotkeys + it) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** The buttons as they sit on the surface, top to bottom: each one's look, its key, its name, and where it goes. */
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
        SectionLabel("On the surface")
        if (hotkeys.isEmpty()) {
            Text(
                "No buttons yet. Choose a key on the right to add one.",
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
                KeyFace(hotkey.copy(label = null).displayLabel)
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
 * A new button: by key or by character, its modifiers on one line, then the key list (or the
 * character field) in all the height left, then Add, which names what it will add. With eight
 * buttons, it says to remove one first instead.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddButton(full: Boolean, typingLayout: KeyboardCharacterLayout, onAdd: (MirrorHotkey) -> Unit, modifier: Modifier) {
    var addBy by remember { mutableStateOf(AddBy.KEY) }
    var modifiers by remember { mutableIntStateOf(0) }
    var chosenKey by remember { mutableStateOf<Int?>(null) }
    var typed by remember { mutableStateOf("") }
    val fromCharacter = typed.takeIf { it.isNotEmpty() }?.let { hotkeyForCharacter(it, typingLayout) }
    val adding = when (addBy) {
        AddBy.KEY -> chosenKey?.let { MirrorHotkey(it, modifiers) }
        AddBy.CHARACTER -> fromCharacter?.let { it.copy(modifiers = it.modifiers or modifiers) }
    }
    if (full) {
        Text(
            "$MAX_MIRROR_HOTKEYS of $MAX_MIRROR_HOTKEYS buttons. Remove one to add another.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(top = 8.dp),
        )
        return
    }
    val keysScroll = rememberScrollState()
    Column(modifier.fillMaxHeight()) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            AddBy.entries.forEachIndexed { i, option ->
                SegmentedButton(
                    selected = addBy == option,
                    onClick = { addBy = option },
                    shape = SegmentedButtonDefaults.itemShape(i, AddBy.entries.size),
                    label = { Text(option.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.testTag("mirror_add_by_${option.name.lowercase()}"),
                )
            }
        }
        // One line of modifiers, sliding sideways when large text can't fit them.
        Row(
            Modifier.padding(top = 4.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MODIFIER_CHOICES.forEach { (bit, name) ->
                val on = modifiers and bit != 0
                FilterChip(
                    selected = on,
                    onClick = { modifiers = modifiers xor bit },
                    label = { Text(name, maxLines = 1) },
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
            when (addBy) {
                AddBy.KEY -> MirrorKeyGroup.entries.forEach { group ->
                    val keys = MIRROR_HOTKEY_KEYS.filter { it.group == group }
                    if (keys.isEmpty()) return@forEach
                    Text(
                        group.title,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        keys.forEach { choice ->
                            KeyChoice(choice, selected = chosenKey == choice.keyCode) {
                                chosenKey = if (chosenKey == choice.keyCode) null else choice.keyCode
                            }
                        }
                    }
                }
                AddBy.CHARACTER -> {
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it.takeLast(1) },
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
        Button(
            onClick = {
                adding?.let(onAdd)
                chosenKey = null
                typed = ""
                modifiers = 0
            },
            enabled = adding != null,
            modifier = Modifier.padding(top = 8.dp).align(Alignment.End).testTag("mirror_hotkey_add"),
        ) {
            Text(adding?.let { "Add ${it.displayLabel}" } ?: "Add")
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

/** A hotkey as the surface draws its face, at the surface's size. */
@Composable
private fun KeyFace(label: String) {
    val shape = MaterialTheme.shapes.medium
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
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
