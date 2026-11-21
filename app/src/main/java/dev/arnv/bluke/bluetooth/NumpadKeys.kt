package dev.arnv.bluke.bluetooth

internal const val KEY_NUM_LOCK = 0x53
internal const val KEY_LEFT_SHIFT = 0xE1

/** What a numpad legend sends: a keypad usage where one exists, so the host's layout doesn't change it. */
internal data class NumpadStroke(val keyCode: Int, val shift: Boolean = false) {
    /** Keypad digits and the decimal point type navigation keys while the host's Num Lock is off. */
    val needsNumLock: Boolean get() = keyCode in 0x59..0x63
}

internal fun numpadStroke(legend: String): NumpadStroke? = when (legend) {
    "1", "2", "3", "4", "5", "6", "7", "8", "9" -> NumpadStroke(0x59 + (legend[0] - '1'))
    "0" -> NumpadStroke(0x62)
    "." -> NumpadStroke(0x63)
    "/" -> NumpadStroke(0x54)
    "*" -> NumpadStroke(0x55)
    "-" -> NumpadStroke(0x56)
    "+" -> NumpadStroke(0x57)
    "Enter" -> NumpadStroke(0x58)
    "⌫" -> NumpadStroke(0x2A)
    // Keypad % and = sit outside the descriptor's key range (and Windows ignores them),
    // so these two stay main-row keys and follow the host's layout.
    "%" -> NumpadStroke(0x22, shift = true)
    "=" -> NumpadStroke(0x2E)
    else -> null
}
