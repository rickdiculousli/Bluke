package dev.arnv.bluke.ui

const val KEYBOARD_MODIFIER_STYLE_PREFERENCE = "keyboard_modifier_style"

/** How the on-screen modifiers are labelled and placed. Both send the same HID modifiers:
 *  GUI is Win on a PC and Command on a Mac, Alt is Option. */
enum class KeyboardModifierStyle(
    val preferenceValue: String,
    val displayName: String,
    val summary: String,
) {
    PC("pc", "PC", "Ctrl · Win · Alt"),
    MAC("mac", "Mac", "Ctrl · Opt · Cmd, Cmd beside the space bar");

    val guiLabel: String get() = if (this == MAC) "Cmd" else "Win"
    val altLabel: String get() = if (this == MAC) "Opt" else "Alt"

    companion object {
        fun fromPreference(value: String?): KeyboardModifierStyle =
            entries.firstOrNull { it.preferenceValue == value } ?: PC
    }
}

private val GUI_KEYS = setOf(KeyboardLayouts.MOD_LWIN, KeyboardLayouts.MOD_RWIN)
private val ALT_KEYS = setOf(KeyboardLayouts.MOD_LALT, KeyboardLayouts.MOD_RALT)

/**
 * Mac style relabels Win as Cmd and Alt as Opt, and moves the Cmd keys nearest the space bar,
 * as on a Mac keyboard: a PC's Ctrl Win Alt becomes Ctrl Opt Cmd, and Ctrl Alt | Win becomes
 * Ctrl Cmd | Opt so the left thumb has Cmd. The Win and Alt slots are ranked by distance from
 * the space bar (left before right at equal distance) and the Cmd keys fill them first.
 * Each key slot keeps its place, size and colour; only what it sends and shows moves.
 */
internal fun List<List<KeyLayoutInfo>>.withModifierStyle(
    style: KeyboardModifierStyle,
): List<List<KeyLayoutInfo>> {
    if (style == KeyboardModifierStyle.PC) return this
    return map { row ->
        val space = row.indexOfFirst { it.keyCode == KeyboardLayouts.KEY_SPACE }
        val keys = row.toMutableList()
        if (space >= 0) commandNearestSpace(keys, space)
        keys.map { key ->
            when (key.keyCode) {
                in GUI_KEYS -> key.copy(legend = style.guiLabel)
                in ALT_KEYS -> key.copy(legend = style.altLabel)
                else -> key
            }
        }
    }
}

private fun commandNearestSpace(keys: MutableList<KeyLayoutInfo>, space: Int) {
    fun isMod(index: Int) = keys[index].keyCode in GUI_KEYS || keys[index].keyCode in ALT_KEYS
    val left = (space - 1 downTo 0).filter(::isMod)
    val right = (space + 1 until keys.size).filter(::isMod)
    val slots = (0 until maxOf(left.size, right.size)).flatMap { rank ->
        listOfNotNull(left.getOrNull(rank), right.getOrNull(rank))
    }
    val contents = slots.map { keys[it] }.sortedBy { if (it.keyCode in GUI_KEYS) 0 else 1 }
    slots.zip(contents).forEach { (slot, content) ->
        keys[slot] = keys[slot].copy(
            legend = content.legend,
            keyCode = content.keyCode,
            physicalKeyCode = content.physicalKeyCode,
        )
    }
}
