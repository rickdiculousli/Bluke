package dev.arnv.bluke.ui

object TouchpadGesturePolicy {
    const val TAP_TIMEOUT_MILLIS = 250L
    const val DOUBLE_TAP_TIMEOUT_MILLIS = 300L
    const val TAP_SLOP_DP = 12f
    private const val SCROLL_UNITS_PER_PX = 0.06f

    private const val SCROLL_AXIS_LOCK_RATIO = 2f

    /** Wheel and pan deltas for a two-finger move. Content follows the fingers on both axes:
     *  fingers down scroll up (wheel +), fingers right scroll left (pan -). */
    data class ScrollStep(val wheel: Float, val pan: Float)

    /**
     * [fingerDx]/[fingerDy] is this move; [gestureDx]/[gestureDy] the travel since the two fingers
     * landed, this move included. A gesture twice as long on one axis as the other scrolls only
     * that axis, so a vertical swipe's drift never slides the page sideways; a diagonal one pans freely.
     */
    fun scrollStep(
        fingerDx: Float,
        fingerDy: Float,
        gestureDx: Float,
        gestureDy: Float,
        scrollSensitivity: Float,
    ): ScrollStep {
        val travelX = kotlin.math.abs(gestureDx)
        val travelY = kotlin.math.abs(gestureDy)
        val scale = SCROLL_UNITS_PER_PX * scrollSensitivity
        return ScrollStep(
            wheel = if (travelX >= travelY * SCROLL_AXIS_LOCK_RATIO) 0f else fingerDy * scale,
            pan = if (travelY >= travelX * SCROLL_AXIS_LOCK_RATIO) 0f else -fingerDx * scale,
        )
    }

    fun isTap(durationMillis: Long, distanceSquaredPx: Float, tapSlopPx: Float): Boolean =
        durationMillis in 0 until TAP_TIMEOUT_MILLIS &&
            distanceSquaredPx <= tapSlopPx * tapSlopPx

    // Time eligibility only; the tap-drag sequencer also checks main's landing tolerance.
    fun isSecondTap(elapsedSinceReleaseMillis: Long): Boolean =
        elapsedSinceReleaseMillis in 0..DOUBLE_TAP_TIMEOUT_MILLIS
}
