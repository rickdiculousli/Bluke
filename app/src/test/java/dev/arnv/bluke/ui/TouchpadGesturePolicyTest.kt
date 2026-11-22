package dev.arnv.bluke.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchpadGesturePolicyTest {
    @Test
    fun tapAllowsNormalFingerMotionButRejectsLongPressOrDrag() {
        assertTrue(TouchpadGesturePolicy.isTap(120, distanceSquaredPx = 100f, tapSlopPx = 12f))
        assertFalse(TouchpadGesturePolicy.isTap(250, distanceSquaredPx = 0f, tapSlopPx = 12f))
        assertFalse(TouchpadGesturePolicy.isTap(120, distanceSquaredPx = 145f, tapSlopPx = 12f))
    }

    @Test
    fun secondTapTimeEligibilityIsBounded() {
        assertTrue(TouchpadGesturePolicy.isSecondTap(300))
        assertFalse(TouchpadGesturePolicy.isSecondTap(301))
        assertFalse(TouchpadGesturePolicy.isSecondTap(-1))
    }

    @Test
    fun twoFingerScrollMovesContentWithTheFingersOnBothAxes() {
        val down = TouchpadGesturePolicy.scrollStep(0f, 100f, 0f, 100f, scrollSensitivity = 1f)
        assertTrue(down.wheel > 0f && down.pan == 0f)
        val right = TouchpadGesturePolicy.scrollStep(100f, 0f, 100f, 0f, scrollSensitivity = 1f)
        assertTrue(right.pan < 0f && right.wheel == 0f)
        val faster = TouchpadGesturePolicy.scrollStep(100f, 0f, 100f, 0f, scrollSensitivity = 2f)
        assertTrue(faster.pan == right.pan * 2)
        val diagonal = TouchpadGesturePolicy.scrollStep(10f, 10f, 60f, 60f, scrollSensitivity = 1f)
        assertTrue(diagonal.wheel > 0f && diagonal.pan < 0f)
    }

    @Test
    fun mostlyVerticalScrollNeverPansSideways() {
        // A long downward swipe drifting right: this move is half sideways, the gesture mostly down.
        val drift = TouchpadGesturePolicy.scrollStep(10f, 10f, gestureDx = 30f, gestureDy = 200f, scrollSensitivity = 1f)
        assertTrue(drift.pan == 0f && drift.wheel > 0f)
    }

    @Test
    fun timingHelperAcceptsAQuickSecondTap() {
        assertTrue(
            TouchpadGesturePolicy.isSecondTap(elapsedSinceReleaseMillis = 120)
        )
    }
}
