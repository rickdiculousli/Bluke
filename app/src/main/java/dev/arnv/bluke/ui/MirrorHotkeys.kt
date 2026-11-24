package dev.arnv.bluke.ui

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** The modifier bits a hotkey can carry: bit n is HID modifier 0xE0 + n (left Ctrl, Shift, Alt/Option, GUI = Cmd/Win). */
object HotkeyModifiers {
    const val CTRL = 1
    const val SHIFT = 2
    const val ALT = 4
    const val GUI = 8
    const val ALL = 15
}

const val MIRROR_HOTKEYS_PREFERENCE = "mirror_hotkeys"
const val MAX_MIRROR_HOTKEYS = 8

/** Longest name a person can give a button. */
const val MAX_HOTKEY_LABEL = 8

private const val STORAGE_VERSION = 1
private const val FIRST_KEY_CODE = 0x04
private const val FIRST_MODIFIER_CODE = 0xE0
private const val LAST_MODIFIER_CODE = 0xE7

/** One hotkey button: a HID [keyCode], its [modifiers] (HotkeyModifiers bits), and an optional short [label]. */
data class MirrorHotkey(val keyCode: Int, val modifiers: Int = 0, val label: String? = null)

/** A key the editor offers, with its printed [name] (as on the button) and its [group]. */
data class MirrorKeyChoice(val keyCode: Int, val name: String, val group: MirrorKeyGroup)

enum class MirrorKeyGroup { LETTERS, DIGITS, FUNCTION, NAVIGATION, MODIFIERS }

/**
 * The keys the editor lists: A-Z, 0-9 (1..9 then 0), F1-F12, Esc, Tab, Space, Enter, Backspace, the four arrows,
 * and the left Ctrl, Shift, Alt, Cmd/Win keys as keys of their own.
 * Names: "A", "1", "F5", "Esc", "Tab", "Space", "Enter", "⌫", "↑" "↓" "←" "→", "Ctrl", "Shift", "Alt", "Cmd/Win".
 */
val MIRROR_HOTKEY_KEYS: List<MirrorKeyChoice> = buildList {
    for (i in 0 until 26) {
        add(MirrorKeyChoice(KeyboardLayouts.KEY_A + i, ('A' + i).toString(), MirrorKeyGroup.LETTERS))
    }
    for (i in 1..9) add(MirrorKeyChoice(KeyboardLayouts.KEY_1 + i - 1, i.toString(), MirrorKeyGroup.DIGITS))
    add(MirrorKeyChoice(KeyboardLayouts.KEY_0, "0", MirrorKeyGroup.DIGITS))
    for (i in 1..12) add(MirrorKeyChoice(KeyboardLayouts.KEY_F1 + i - 1, "F$i", MirrorKeyGroup.FUNCTION))
    add(MirrorKeyChoice(KeyboardLayouts.KEY_ESC, "Esc", MirrorKeyGroup.NAVIGATION))
    add(MirrorKeyChoice(KeyboardLayouts.KEY_TAB, "Tab", MirrorKeyGroup.NAVIGATION))
    add(MirrorKeyChoice(KeyboardLayouts.KEY_SPACE, "Space", MirrorKeyGroup.NAVIGATION))
    add(MirrorKeyChoice(KeyboardLayouts.KEY_ENTER, "Enter", MirrorKeyGroup.NAVIGATION))
    add(MirrorKeyChoice(KeyboardLayouts.KEY_BACKSPACE, "⌫", MirrorKeyGroup.NAVIGATION))
    add(MirrorKeyChoice(KeyboardLayouts.KEY_UP, "↑", MirrorKeyGroup.NAVIGATION))
    add(MirrorKeyChoice(KeyboardLayouts.KEY_DOWN, "↓", MirrorKeyGroup.NAVIGATION))
    add(MirrorKeyChoice(KeyboardLayouts.KEY_LEFT, "←", MirrorKeyGroup.NAVIGATION))
    add(MirrorKeyChoice(KeyboardLayouts.KEY_RIGHT, "→", MirrorKeyGroup.NAVIGATION))
    add(MirrorKeyChoice(KeyboardLayouts.MOD_LCTRL, "Ctrl", MirrorKeyGroup.MODIFIERS))
    add(MirrorKeyChoice(KeyboardLayouts.MOD_LSHIFT, "Shift", MirrorKeyGroup.MODIFIERS))
    add(MirrorKeyChoice(KeyboardLayouts.MOD_LALT, "Alt", MirrorKeyGroup.MODIFIERS))
    add(MirrorKeyChoice(KeyboardLayouts.MOD_LWIN, "Cmd/Win", MirrorKeyGroup.MODIFIERS))
}

private val keyNames: Map<Int, String> = MIRROR_HOTKEY_KEYS.associate { it.keyCode to it.name }

/** The printed name of [keyCode] from MIRROR_HOTKEY_KEYS, or for a code not listed there (a typed punctuation key) its unshifted US character, e.g. ";" ; "?" never. */
fun hotkeyKeyName(keyCode: Int): String =
    keyNames[keyCode]
        ?: KeyboardLayouts.getCharForKeycode(keyCode, shiftPressed = false).ifEmpty { "0x%02X".format(keyCode) }

private val modifierGlyphs = listOf(
    HotkeyModifiers.CTRL to "⌃",
    HotkeyModifiers.ALT to "⌥",
    HotkeyModifiers.SHIFT to "⇧",
    HotkeyModifiers.GUI to "⌘",
)

private val modifierWords = listOf(
    HotkeyModifiers.CTRL to "Control",
    HotkeyModifiers.ALT to "Alt",
    HotkeyModifiers.SHIFT to "Shift",
    HotkeyModifiers.GUI to "Command or Windows",
)

private val spokenKeyNames = mapOf(
    KeyboardLayouts.KEY_ESC to "Escape",
    KeyboardLayouts.KEY_BACKSPACE to "Backspace",
    KeyboardLayouts.KEY_UP to "Up arrow",
    KeyboardLayouts.KEY_DOWN to "Down arrow",
    KeyboardLayouts.KEY_LEFT to "Left arrow",
    KeyboardLayouts.KEY_RIGHT to "Right arrow",
    KeyboardLayouts.MOD_LCTRL to "Control",
    KeyboardLayouts.MOD_LSHIFT to "Shift",
    KeyboardLayouts.MOD_LALT to "Alt",
    KeyboardLayouts.MOD_LWIN to "Command or Windows",
)

private fun String?.cleanLabel(): String? =
    this?.trim()?.take(MAX_HOTKEY_LABEL)?.trim()?.ifEmpty { null }

/** What the button shows: the label when set (trimmed, non-blank), else the modifier glyphs in ⌃⌥⇧⌘ order then the key's printed name, e.g. "E", "1", "Esc", "⇧Tab", "⌃⇧F5". */
val MirrorHotkey.displayLabel: String
    get() = label?.trim()?.ifEmpty { null }
        ?: (modifierGlyphs.filter { modifiers and it.first != 0 }.joinToString("") { it.second } +
            hotkeyKeyName(keyCode))

/** What TalkBack reads: words, not glyphs, e.g. "Shift Tab", "Control E", or "End turn, E" when labelled. */
val MirrorHotkey.spokenLabel: String
    get() {
        val words = modifierWords.filter { modifiers and it.first != 0 }.map { it.second } +
            (spokenKeyNames[keyCode] ?: hotkeyKeyName(keyCode))
        val keys = words.joinToString(" ")
        val name = label?.trim()?.ifEmpty { null }
        return if (name == null) keys else "$name, $keys"
    }

/** The list as stored: {"version":1,"hotkeys":[{"key":8,"modifiers":0,"label":"End turn"}]}, label omitted when null. */
fun encodeMirrorHotkeys(hotkeys: List<MirrorHotkey>): String {
    val entries = JSONArray()
    hotkeys.take(MAX_MIRROR_HOTKEYS).forEach {
        val entry = JSONObject().put("key", it.keyCode).put("modifiers", it.modifiers)
        it.label?.let { label -> entry.put("label", label) }
        entries.put(entry)
    }
    return JSONObject().put("version", STORAGE_VERSION).put("hotkeys", entries).toString()
}

/**
 * Back from [encodeMirrorHotkeys]. Null, malformed JSON, or an unknown version reads as an empty list; an entry with a
 * key code outside 0x04..0xE7 or a missing key is skipped; modifiers are masked to ALL; labels are trimmed,
 * blank -> null, cut to MAX_HOTKEY_LABEL; at most MAX_MIRROR_HOTKEYS are kept.
 */
fun decodeMirrorHotkeys(json: String?): List<MirrorHotkey> {
    if (json == null) return emptyList()
    return try {
        val root = JSONObject(json)
        if (root.optInt("version", -1) != STORAGE_VERSION) return emptyList()
        val entries = root.optJSONArray("hotkeys") ?: return emptyList()
        buildList {
            for (i in 0 until entries.length()) {
                val entry = entries.optJSONObject(i) ?: continue
                if (!entry.has("key")) continue
                val key = entry.optInt("key", -1)
                if (key !in FIRST_KEY_CODE..LAST_MODIFIER_CODE) continue
                val label = if (entry.isNull("label")) null else entry.optString("label").cleanLabel()
                add(MirrorHotkey(key, entry.optInt("modifiers", 0) and HotkeyModifiers.ALL, label))
                if (size == MAX_MIRROR_HOTKEYS) break
            }
        }
    } catch (_: JSONException) {
        emptyList()
    }
}

fun SharedPreferences.mirrorHotkeys(): List<MirrorHotkey> =
    decodeMirrorHotkeys(getString(MIRROR_HOTKEYS_PREFERENCE, null))

fun SharedPreferences.saveMirrorHotkeys(hotkeys: List<MirrorHotkey>) {
    edit().putString(MIRROR_HOTKEYS_PREFERENCE, encodeMirrorHotkeys(hotkeys.take(MAX_MIRROR_HOTKEYS))).apply()
}

/** Where a hotkey's key events go: BluetoothKeyboardManager::sendKey in the app, a recorder in tests. */
fun interface MirrorKeySender {
    fun sendKey(keyCode: Int, isPress: Boolean)
}

/**
 * Presses and lifts hotkeys through [sender]. A press sends its modifiers down (each bit not already held, low bit
 * first), then the key down; a lift sends the key up, then each of its modifiers up (low bit first). Modifiers are one
 * bitmask with no counting: if two held hotkeys share a modifier, lifting one drops it for the other (accepted:
 * hotkeys are tapped, not chorded). Pressing a hotkey already held, or lifting one not held, sends nothing. A hotkey
 * whose key is itself a modifier (0xE0..0xE7) sends just that key. [releaseAll] lifts everything still held (for the
 * surface leaving the screen). Not thread-safe; call on the main thread.
 */
class MirrorHotkeyPresser(private val sender: MirrorKeySender) {
    private val heldHotkeys = LinkedHashSet<MirrorHotkey>()
    private var heldModifiers = 0

    val held: Set<MirrorHotkey> get() = heldHotkeys.toSet()

    fun press(hotkey: MirrorHotkey) {
        if (!heldHotkeys.add(hotkey)) return
        if (!hotkey.keyIsModifier()) {
            for (bit in 0..3) {
                val mask = 1 shl bit
                if (hotkey.modifiers and mask != 0 && heldModifiers and mask == 0) {
                    heldModifiers = heldModifiers or mask
                    sender.sendKey(FIRST_MODIFIER_CODE + bit, true)
                }
            }
        }
        sender.sendKey(hotkey.keyCode, true)
    }

    fun lift(hotkey: MirrorHotkey) {
        if (!heldHotkeys.remove(hotkey)) return
        sender.sendKey(hotkey.keyCode, false)
        if (hotkey.keyIsModifier()) return
        for (bit in 0..3) {
            val mask = 1 shl bit
            if (hotkey.modifiers and mask != 0 && heldModifiers and mask != 0) {
                heldModifiers = heldModifiers and mask.inv()
                sender.sendKey(FIRST_MODIFIER_CODE + bit, false)
            }
        }
    }

    fun releaseAll() {
        heldHotkeys.toList().forEach(::lift)
    }

    private fun MirrorHotkey.keyIsModifier() = keyCode in FIRST_MODIFIER_CODE..LAST_MODIFIER_CODE
}

/**
 * The hotkey a typed character makes under the typing layout [layout]: the inverse of what the keyboard surface sends.
 * A key whose normal legend is [char] (letters case-insensitive) gives its key code; one whose shifted legend is
 * [char] gives it with SHIFT; space is KEY_SPACE. A letter typed upper-case maps like lower-case (no Shift). Null when
 * no key prints it (e.g. "é" on QWERTY), or for an empty or multi-character string.
 */
fun hotkeyForCharacter(char: String, layout: KeyboardCharacterLayout): MirrorHotkey? {
    if (char.codePointCount(0, char.length) != 1) return null
    if (char == " ") return MirrorHotkey(KeyboardLayouts.KEY_SPACE)
    val keys = KeyboardLayouts.getLayout(KeyboardLayoutType.OLIVIA_75, layout)
        .flatten()
        .filter { it.keyCode != 0 }
    keys.firstOrNull { it.legend.equals(char, ignoreCase = true) }?.let { return MirrorHotkey(it.keyCode) }
    keys.firstOrNull { it.shiftedLegend == char }?.let {
        return MirrorHotkey(it.keyCode, HotkeyModifiers.SHIFT)
    }
    return null
}
