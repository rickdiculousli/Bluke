package dev.arnv.bluke.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class TouchpadScrollerTest {
    private class Run(val scroller: TouchpadScroller) {
        var time = 0L
        var wheel = 0
        var pan = 0

        fun take(report: ScrollReport?) {
            report?.let { wheel += it.wheel; pan += it.pan }
        }

        /** Two fingers travel by (dx, dy) dp over [millis], one touch event every 8 ms. */
        fun swipe(dx: Float, dy: Float, millis: Long, startX: Float = 500f, startY: Float = 500f) {
            scroller.begin(startX, startY, time)
            val steps = (millis / 8).toInt()
            for (i in 1..steps) {
                time += 8
                take(scroller.move(startX + dx * i / steps, startY + dy * i / steps, time))
            }
        }

        fun liftAll() = take(scroller.liftAll(time))

        /** Fingers swing (dx, dy) dp one way and back, [cycles] times, [millis] per swing; reports total steps sent. */
        fun wiggle(dx: Float, dy: Float, millis: Long, cycles: Int, drift: Float = 0f): Int {
            var sent = 0
            scroller.begin(500f, 500f, time)
            val steps = (millis / 8).toInt()
            var n = 0
            repeat(cycles * 2) { swing ->
                for (i in 1..steps) {
                    time += 8
                    n++
                    val f = if (swing % 2 == 0) i.toFloat() / steps else 1f - i.toFloat() / steps
                    scroller.move(500f + dx * f + drift * n / (cycles * 2 * steps), 500f + dy * f, time)?.let {
                        take(it); sent += kotlin.math.abs(it.wheel) + kotlin.math.abs(it.pan)
                    }
                }
            }
            return sent
        }

        fun tickUntilIdle(maxMillis: Long = 10_000) {
            val end = time + maxMillis
            while (scroller.isActive && time < end) { time += 8; take(scroller.tick(time)) }
        }
    }

    private fun run(settings: TouchpadScrollSettings) = Run(TouchpadScroller(settings))
    private val still = TouchpadScrollSettings(momentum = false)
    private fun steps(dp: Float, speed: Float = 1f) = (dp * TouchpadScroller.UNITS_PER_DP * speed).roundToInt()

    @Test fun contentFollowsTheFingersUnlessInverted() {
        val down = run(still).apply { swipe(0f, 1000f, 200); liftAll() }
        assertEquals(steps(1000f), down.wheel)
        assertEquals(0, down.pan)
        val right = run(still).apply { swipe(1000f, 0f, 200); liftAll() }
        assertEquals(-steps(1000f), right.pan)
        val inverted = run(still.copy(inverted = true)).apply { swipe(0f, 1000f, 200); liftAll() }
        assertEquals(-steps(1000f), inverted.wheel)
    }

    @Test fun stepsGoOutWhileTheFingersMoveNotAfter() {
        val scroller = TouchpadScroller(still)
        scroller.begin(0f, 0f, 0)
        val oneStep = 1f / TouchpadScroller.UNITS_PER_DP
        assertNotNull("a move that completes a step reports it at once", scroller.move(0f, oneStep, 8))
    }

    @Test fun speedScalesTheDistance() {
        val fast = run(still.copy(speed = 2f)).apply { swipe(0f, 1000f, 200); liftAll() }
        assertEquals(steps(1000f, speed = 2f), fast.wheel)
    }

    @Test fun oneScrollStepIsAboutSixMillimetresOfFingerTravel() {
        // Distance is physical: 6 mm is ~37.8 dp on any screen density.
        assertEquals(1, steps(6f * 160f / 25.4f))
    }

    @Test fun aMostlyVerticalSwipeNeverScrollsSideways() {
        val drift = run(still).apply { swipe(400f, 3000f, 400); liftAll() }
        assertEquals(0, drift.pan)
        assertEquals(steps(3000f), drift.wheel)
        val diagonal = run(still).apply { swipe(1000f, 1000f, 200); liftAll() }
        assertTrue(diagonal.wheel > 0 && diagonal.pan < 0)
    }

    @Test fun aQuickBackAndForthScrollsTheWholeWayEachWay() {
        // Four 1.5 cm swings each way, 120 ms per swing, the fingers drifting 2 mm sideways meanwhile.
        val swing = 15f * 160f / 25.4f
        val wiggle = run(still)
        val sent = wiggle.wiggle(0f, swing, 120, cycles = 4, drift = 2f * 160f / 25.4f)
        assertEquals(0, wiggle.pan)
        val travelled = swing * 8 * TouchpadScroller.UNITS_PER_DP // ~20.4 steps of finger travel
        assertTrue("sent $sent for $travelled", sent >= travelled.toInt() - 1)
    }

    @Test fun aFlickGlidesOnThenStops() {
        val flick = run(TouchpadScrollSettings()).apply { swipe(0f, 1000f, 100) }
        flick.liftAll()
        val atLift = flick.wheel
        assertTrue(flick.scroller.isActive)
        flick.tickUntilIdle()
        assertFalse("the glide stops on its own", flick.scroller.isActive)
        assertTrue("momentum carries past the fingers' distance", flick.wheel > atLift)
    }

    @Test fun aGlideIsOverWithinASecondLikeAMacTrackpad() {
        val hard = run(TouchpadScrollSettings(glide = 1f)).apply { swipe(0f, 3000f, 40) }
        val lift = hard.time
        hard.liftAll()
        hard.tickUntilIdle()
        assertTrue(hard.time - lift <= TouchpadScroller.MAX_GLIDE_DURATION_MILLIS + 8)
    }

    @Test fun aLongerGlideCarriesFurther() {
        fun distance(glide: Float) = run(TouchpadScrollSettings(glide = glide))
            .apply { swipe(0f, 1000f, 100); liftAll(); tickUntilIdle() }.wheel
        assertTrue(distance(1f) > distance(0f))
    }

    @Test fun aSlowDragDoesNotGlide() {
        val drag = run(TouchpadScrollSettings()).apply { swipe(0f, 200f, 1000) } // 0.2 dp/ms, ~3 cm/s
        drag.liftAll()
        assertFalse(drag.scroller.isActive)
    }

    @Test fun fingersThatStoppedBeforeLiftingDoNotGlide() {
        val rest = run(TouchpadScrollSettings()).apply { swipe(0f, 1000f, 100) }
        rest.time += 200 // fingers held still, then lifted
        rest.liftAll()
        assertFalse(rest.scroller.isActive)
    }

    @Test fun liftingOneFingerThenTheOtherStillGlides() {
        val flick = run(TouchpadScrollSettings()).apply { swipe(0f, 1000f, 100) }
        flick.take(flick.scroller.end(flick.time)) // first finger up
        flick.time += 30
        flick.liftAll() // second finger up a moment later
        assertTrue(flick.scroller.isActive)
    }

    @Test fun aNewTouchStopsTheGlide() {
        val flick = run(TouchpadScrollSettings()).apply { swipe(0f, 1000f, 100); liftAll() }
        flick.scroller.stop()
        assertFalse(flick.scroller.isActive)
        assertEquals(null, flick.scroller.tick(flick.time + 8))
    }

    @Test fun theRemainingFingerPointsOnlyAfterTheScrollSettles() {
        val handover = run(still).apply { swipe(0f, 1000f, 200) }
        assertFalse(handover.scroller.allowsPointing(handover.time))
        handover.scroller.end(handover.time)
        assertFalse(handover.scroller.allowsPointing(handover.time + 50))
        assertTrue(handover.scroller.allowsPointing(handover.time + TouchpadScroller.POINTER_RESUME_MILLIS))
    }
}
