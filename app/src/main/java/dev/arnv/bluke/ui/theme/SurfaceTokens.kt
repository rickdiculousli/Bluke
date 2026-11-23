package dev.arnv.bluke.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Colours the input surfaces draw that no Material role names. Everything else on a surface's chrome
 * is a role of the theme's dark scheme; content colours (keycaps, console faces) stay in their own tables.
 */
object SurfaceTokens {
    /** A lit indicator LED (caps, num and scroll lock; numpad on): a hardware green, readable on any dark container. */
    val LedLit = Color(0xFF5CE27E)
}
