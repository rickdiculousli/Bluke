package dev.arnv.bluke.ui

import androidx.compose.ui.geometry.Offset

object TouchpadGesturePolicy {
    const val TAP_TIMEOUT_MILLIS = 250L
    const val DOUBLE_TAP_TIMEOUT_MILLIS = 300L
    const val TAP_SLOP_DP = 12f


    fun isTap(durationMillis: Long, distanceSquaredPx: Float, tapSlopPx: Float): Boolean =
        durationMillis in 0 until TAP_TIMEOUT_MILLIS &&
            distanceSquaredPx <= tapSlopPx * tapSlopPx

    /** A two-finger scroll moves with whichever finger moved further this event, as a Mac trackpad
     *  does: resting one finger doesn't halve the scroll, as following their midpoint would. */
    fun leadingFingerMove(first: Offset, second: Offset): Offset =
        if (second.getDistanceSquared() > first.getDistanceSquared()) second else first

    // Time eligibility only; the tap-drag sequencer also checks main's landing tolerance.
    fun isSecondTap(elapsedSinceReleaseMillis: Long): Boolean =
        elapsedSinceReleaseMillis in 0..DOUBLE_TAP_TIMEOUT_MILLIS
}
