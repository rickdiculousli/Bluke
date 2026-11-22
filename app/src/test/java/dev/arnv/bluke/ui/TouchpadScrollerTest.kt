package dev.arnv.bluke.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchpadScrollerTest {
    private class Run(val scroller: TouchpadScroller) {
        var time = 0L
        var wheel = 0
        var pan = 0
        var reports = 0

        private fun tick() {
            scroller.tick(time)?.let { wheel += it.wheel; pan += it.pan; reports++ }
        }

        /** Two fingers travel by (dx, dy) px over [millis], moving and ticking every 8 ms. */
        fun swipe(dx: Float, dy: Float, millis: Long, startX: Float = 500f, startY: Float = 500f) {
            scroller.begin(startX, startY, time)
            val steps = (millis / 8).toInt()
            for (i in 1..steps) {
                time += 8
                scroller.move(startX + dx * i / steps, startY + dy * i / steps, time)
                tick()
            }
        }

        fun liftAll() = scroller.liftAll(time)

        fun tickUntilIdle(maxMillis: Long = 10_000) {
            val end = time + maxMillis
            while (scroller.isActive && time < end) { time += 8; tick() }
        }
    }

    private fun run(settings: TouchpadScrollSettings) = Run(TouchpadScroller(settings))
    private val crisp = TouchpadScrollSettings(smoothing = 0f, momentum = false)

    @Test fun contentFollowsTheFingersUnlessInverted() {
        val down = run(crisp).apply { swipe(0f, 100f, 200); liftAll(); tickUntilIdle() }
        assertEquals(6, down.wheel) // 100 px * 0.06 units/px
        assertEquals(0, down.pan)
        val right = run(crisp).apply { swipe(100f, 0f, 200); liftAll(); tickUntilIdle() }
        assertEquals(-6, right.pan)
        val inverted = run(crisp.copy(inverted = true)).apply { swipe(0f, 100f, 200); liftAll(); tickUntilIdle() }
        assertEquals(-6, inverted.wheel)
    }

    @Test fun speedScalesTheDistance() {
        val fast = run(crisp.copy(speed = 2f)).apply { swipe(0f, 100f, 200); liftAll(); tickUntilIdle() }
        assertEquals(12, fast.wheel)
    }

    @Test fun aMostlyVerticalSwipeNeverScrollsSideways() {
        val drift = run(crisp).apply { swipe(40f, 300f, 400); liftAll(); tickUntilIdle() }
        assertEquals(0, drift.pan)
        assertEquals(18, drift.wheel)
        val diagonal = run(crisp).apply { swipe(100f, 100f, 200); liftAll(); tickUntilIdle() }
        assertTrue(diagonal.wheel > 0 && diagonal.pan < 0)
    }

    @Test fun smoothingTrailsTheFingersButKeepsEveryStep() {
        val smooth = run(crisp.copy(smoothing = 1f)).apply { swipe(0f, 100f, 200) }
        assertTrue("smoothed scroll lags behind the fingers", smooth.wheel < 6)
        smooth.scroller.end(smooth.time)
        smooth.liftAll()
        smooth.tickUntilIdle()
        assertEquals(6, smooth.wheel)
    }

    @Test fun aFlickGlidesOnThenStops() {
        val flick = run(TouchpadScrollSettings(smoothing = 0f, momentum = true)).apply { swipe(0f, 200f, 100) }
        val atLift = flick.wheel
        flick.liftAll()
        assertTrue(flick.scroller.isActive)
        flick.tickUntilIdle()
        assertFalse("the glide stops on its own", flick.scroller.isActive)
        assertTrue("momentum carries past the fingers' distance", flick.wheel > atLift)
    }

    @Test fun aLongerGlideCarriesFurther() {
        fun distance(glide: Float) = run(TouchpadScrollSettings(smoothing = 0f, glide = glide))
            .apply { swipe(0f, 200f, 100); liftAll(); tickUntilIdle() }.wheel
        assertTrue(distance(1f) > distance(0f))
    }

    @Test fun fingersThatStoppedBeforeLiftingDoNotGlide() {
        val rest = run(TouchpadScrollSettings(smoothing = 0f)).apply { swipe(0f, 200f, 100) }
        rest.time += 200 // fingers held still, then lifted
        rest.liftAll()
        assertFalse(rest.scroller.isActive)
    }

    @Test fun liftingOneFingerThenTheOtherStillGlides() {
        val flick = run(TouchpadScrollSettings(smoothing = 0f)).apply { swipe(0f, 200f, 100) }
        flick.scroller.end(flick.time) // first finger up
        flick.time += 30
        flick.liftAll() // second finger up a moment later
        assertTrue(flick.scroller.isActive)
    }

    @Test fun aNewTouchStopsTheGlide() {
        val flick = run(TouchpadScrollSettings(smoothing = 0f)).apply { swipe(0f, 200f, 100); liftAll() }
        flick.scroller.stop()
        assertFalse(flick.scroller.isActive)
        assertEquals(null, flick.scroller.tick(flick.time + 8))
    }

    @Test fun theRemainingFingerPointsOnlyAfterTheScrollSettles() {
        val handover = run(crisp).apply { swipe(0f, 100f, 200) }
        assertFalse(handover.scroller.allowsPointing(handover.time))
        handover.scroller.end(handover.time)
        assertFalse(handover.scroller.allowsPointing(handover.time + 50))
        assertTrue(handover.scroller.allowsPointing(handover.time + TouchpadScroller.POINTER_RESUME_MILLIS))
    }
}
