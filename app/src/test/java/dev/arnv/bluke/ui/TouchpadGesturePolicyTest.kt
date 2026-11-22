package dev.arnv.bluke.ui

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
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
    fun scrollFollowsTheFingerThatMovedFurther() {
        val resting = Offset(0f, 0f)
        val moving = Offset(3f, 40f)
        // One finger resting: the scroll moves the full distance, not half of it.
        assertEquals(moving, TouchpadGesturePolicy.leadingFingerMove(resting, moving))
        assertEquals(moving, TouchpadGesturePolicy.leadingFingerMove(moving, resting))
        assertEquals(moving, TouchpadGesturePolicy.leadingFingerMove(Offset(0f, 30f), moving))
    }

    @Test
    fun timingHelperAcceptsAQuickSecondTap() {
        assertTrue(
            TouchpadGesturePolicy.isSecondTap(elapsedSinceReleaseMillis = 120)
        )
    }
}
