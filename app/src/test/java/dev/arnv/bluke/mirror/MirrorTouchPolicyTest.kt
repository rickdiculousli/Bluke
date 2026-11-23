package dev.arnv.bluke.mirror

import dev.arnv.bluke.mirror.MirrorMessage.Pointer
import dev.arnv.bluke.mirror.MirrorMessage.Wheel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorTouchPolicyTest {
  // A 65535-pixel square at the origin: surface pixels equal normalised units, so expectations read directly.
  private val fit = MirrorFit(0f, 0f, 65535f, 65535f, MirrorRotation.NONE)
  private val policy = MirrorTouchPolicy(tapSlopPx = 20f)

  private fun f(x: Float, y: Float, id: Long = 1) = MirrorFinger(id, x, y)

  private fun touch(timeMs: Long, vararg fingers: MirrorFinger) = policy.update(fit, fingers.toList(), timeMs).messages

  private fun touchUpdate(timeMs: Long, vararg fingers: MirrorFinger) = policy.update(fit, fingers.toList(), timeMs)

  @Test
  fun directPressesDragsAndReleasesAtTheLastPoint() {
    assertEquals(listOf(Pointer(100, 200, 1)), touch(0, f(100f, 200f)))
    assertTrue(policy.buttonHeld)
    assertEquals(listOf(Pointer(300, 400, 1)), touch(10, f(300f, 400f)))
    assertEquals(emptyList<MirrorMessage>(), touch(20, f(300f, 400f)))
    assertEquals(listOf(Pointer(300, 400, 0)), touch(30))
    assertFalse(policy.buttonHeld)
  }

  @Test
  fun aDragThatLeavesTheImageIsClamped() {
    val letterboxed = MirrorFit(0f, 100f, 1000f, 500f, MirrorRotation.NONE)
    assertEquals(listOf(Pointer(32768, 32768, 1)), policy.update(letterboxed, listOf(f(500f, 350f)), 0).messages)
    assertEquals(listOf(Pointer(32768, 0, 1)), policy.update(letterboxed, listOf(f(500f, 0f)), 10).messages)
    assertEquals(listOf(Pointer(32768, 0, 0)), policy.update(letterboxed, emptyList(), 20).messages)
  }

  @Test
  fun aDownOutsideTheImageDoesNothingUntilEveryFingerLifts() {
    val letterboxed = MirrorFit(0f, 100f, 1000f, 500f, MirrorRotation.NONE)
    assertEquals(emptyList<MirrorMessage>(), policy.update(letterboxed, listOf(f(500f, 50f)), 0).messages)
    assertEquals(emptyList<MirrorMessage>(), policy.update(letterboxed, listOf(f(500f, 350f)), 10).messages)
    assertEquals(emptyList<MirrorMessage>(), policy.update(letterboxed, listOf(f(500f, 350f), f(600f, 350f, 2)), 20).messages)
    assertFalse(policy.buttonHeld)
    assertEquals(emptyList<MirrorMessage>(), policy.update(letterboxed, emptyList(), 30).messages)
    assertEquals(listOf(Pointer(32768, 32768, 1)), policy.update(letterboxed, listOf(f(500f, 350f)), 40).messages)
  }

  @Test
  fun hoverMovesWithoutPressingAndATapClicks() {
    policy.mode = MirrorTouchMode.HOVER
    assertEquals(listOf(Pointer(100, 100, 0)), touch(0, f(100f, 100f)))
    assertFalse(policy.buttonHeld)
    assertEquals(listOf(Pointer(105, 100, 0)), touch(50, f(105f, 100f)))
    assertEquals(listOf(Pointer(100, 100, 1), Pointer(100, 100, 0)), touch(100))
  }

  @Test
  fun hoverMovingBeyondTheSlopIsNotATap() {
    policy.mode = MirrorTouchMode.HOVER
    touch(0, f(100f, 100f))
    assertEquals(listOf(Pointer(100, 130, 0)), touch(50, f(100f, 130f)))
    touch(60, f(100f, 100f))
    assertEquals(emptyList<MirrorMessage>(), touch(100))
  }

  @Test
  fun hoverHeldTooLongIsNotATap() {
    policy.mode = MirrorTouchMode.HOVER
    touch(0, f(100f, 100f))
    assertEquals(emptyList<MirrorMessage>(), touch(400))
  }

  @Test
  fun aSecondFingerReleasesTheButtonThenScrolls() {
    touch(0, f(1000f, 1000f))
    assertEquals(listOf(Pointer(1000, 1000, 0)), touch(10, f(1000f, 1000f), f(1200f, 1000f, 2)))
    assertFalse(policy.buttonHeld)
    // Fingers move down 30 px: positive dy (the view scrolls up).
    assertEquals(listOf(Wheel(0, 30)), touch(20, f(1000f, 1030f), f(1200f, 1030f, 2)))
    // Fingers move up and right.
    assertEquals(listOf(Wheel(10, -20)), touch(30, f(1010f, 1010f), f(1210f, 1010f, 2)))
    // Lifting one finger ends the scroll without a press; lifting the other ends the gesture.
    assertEquals(emptyList<MirrorMessage>(), touch(40, f(1010f, 1010f)))
    assertEquals(emptyList<MirrorMessage>(), touch(50, f(1500f, 1500f)))
    assertEquals(emptyList<MirrorMessage>(), touch(60))
    assertEquals(listOf(Pointer(5, 5, 1)), touch(70, f(5f, 5f)))
  }

  @Test
  fun subPixelScrollAccumulates() {
    touch(0, f(0f, 0f), f(10f, 0f, 2))
    assertEquals(emptyList<MirrorMessage>(), touch(10, f(0f, 0.6f), f(10f, 0.6f, 2)))
    assertEquals(listOf(Wheel(0, 1)), touch(20, f(0f, 1.2f), f(10f, 1.2f, 2)))
  }

  @Test
  fun hoverTwoFingersScrollWithoutAClick() {
    policy.mode = MirrorTouchMode.HOVER
    touch(0, f(100f, 100f))
    assertEquals(emptyList<MirrorMessage>(), touch(10, f(100f, 100f), f(200f, 100f, 2)))
    assertEquals(listOf(Wheel(0, -5)), touch(20, f(100f, 95f), f(200f, 95f, 2)))
    assertEquals(emptyList<MirrorMessage>(), touch(30))
  }

  @Test
  fun cancelReleasesAHeldButton() {
    touch(0, f(100f, 200f))
    touch(10, f(110f, 200f))
    assertEquals(listOf(Pointer(110, 200, 0)), policy.cancel().messages)
    assertFalse(policy.buttonHeld)
    assertEquals(emptyList<MirrorMessage>(), policy.cancel().messages)
  }

  @Test
  fun heartbeatOnlyWhileHeldAndQuietFor250ms() {
    assertNull(policy.heartbeat(nowMs = 1000, lastSentMs = 0))
    touch(0, f(100f, 200f))
    assertNull(policy.heartbeat(nowMs = 249, lastSentMs = 0))
    assertEquals(MirrorMessage.Heartbeat, policy.heartbeat(nowMs = 250, lastSentMs = 0))
    touch(260)
    assertNull(policy.heartbeat(nowMs = 1000, lastSentMs = 0))
  }

  @Test
  fun releaseSendsReleaseAndForgetsTheGesture() {
    touch(0, f(100f, 200f))
    assertEquals(listOf(MirrorMessage.Release), policy.release())
    assertFalse(policy.buttonHeld)
    assertEquals(emptyList<MirrorMessage>(), touch(10))
  }

  @Test
  fun rotatedFitMapsTouchesThroughTheRotation() {
    val rotated = MirrorFit(0f, 0f, 65535f, 65535f, MirrorRotation.CLOCKWISE)
    assertEquals(listOf(Pointer(0, 0, 1)), policy.update(rotated, listOf(f(65535f, 0f)), 0).messages)
  }

  @Test
  fun aCancelledHoverTapNeverClicks() {
    policy.mode = MirrorTouchMode.HOVER
    touch(0, f(100f, 100f))
    val cancelled = policy.cancel()
    assertEquals(emptyList<MirrorMessage>(), cancelled.messages)
    assertEquals(emptyList<MirrorTouchEvent>(), cancelled.events)
    // The next touch starts afresh.
    assertEquals(listOf(Pointer(50, 50, 0)), touch(20, f(50f, 50f)))
  }

  @Test
  fun aCancelledFrameGoesThroughCancel() {
    touch(0, f(100f, 200f))
    val frame = MirrorTouchFrame(
        points = listOf(MirrorTouchPoint(1, 100f, 200f, down = false, wasDown = true)),
        uptimeMillis = 10,
        fit = fit,
        cancelled = true,
    )
    val update = policy.update(frame)
    assertEquals(listOf(Pointer(100, 200, 0)), update.messages)
    assertEquals(emptyList<MirrorTouchEvent>(), update.events)
  }

  @Test
  fun aSecondFingerOutsideTheImageIsIgnoredMidDrag() {
    val letterboxed = MirrorFit(0f, 100f, 1000f, 500f, MirrorRotation.NONE)
    fun at(t: Long, vararg fs: MirrorFinger) = policy.update(letterboxed, fs.toList(), t)
    assertEquals(listOf(Pointer(32768, 32768, 1)), at(0, f(500f, 350f)).messages)
    // A thumb lands on the bar above the image: the drag goes on, the button stays held.
    val thumb = f(900f, 20f, id = 2)
    assertEquals(emptyList<MirrorMessage>(), at(10, f(500f, 350f), thumb).messages)
    assertTrue(policy.buttonHeld)
    assertEquals(listOf(Pointer(32768, 0, 1)), at(20, f(500f, 100f), thumb).messages)
    // Even when the thumb slides onto the image it stays ignored.
    assertEquals(emptyList<MirrorMessage>(), at(30, f(500f, 100f), f(900f, 300f, id = 2)).messages)
    assertTrue(policy.buttonHeld)
    // The drag lifts and releases; the thumb still down sends nothing until it lifts too.
    assertEquals(listOf(Pointer(32768, 0, 0)), at(40, f(900f, 300f, id = 2)).messages)
    assertEquals(emptyList<MirrorMessage>(), at(50, f(910f, 300f, id = 2)).messages)
    assertEquals(emptyList<MirrorMessage>(), at(60).messages)
  }

  @Test
  fun theUpdateReportsWhatHappenedForRingsAndHaptics() {
    val press = touchUpdate(0, f(100f, 200f))
    assertEquals(listOf(MirrorTouchEvent.PRESSED), press.events)
    assertEquals(listOf(f(100f, 200f)), press.rings)
    assertTrue(press.held)
    val scroll = touchUpdate(10, f(100f, 200f), f(300f, 200f, 2))
    assertEquals(emptyList<MirrorTouchEvent>(), scroll.events)
    assertEquals(listOf(Pointer(100, 200, 0)), scroll.messages)
    assertEquals(2, scroll.rings.size)
    assertFalse(scroll.held)
    val lift = touchUpdate(20)
    assertEquals(emptyList<MirrorTouchEvent>(), lift.events)
    assertEquals(emptyList<MirrorFinger>(), lift.rings)

    policy.mode = MirrorTouchMode.HOVER
    val hover = touchUpdate(100, f(50f, 50f))
    assertEquals(emptyList<MirrorTouchEvent>(), hover.events)
    assertEquals(listOf(f(50f, 50f)), hover.rings)
    assertFalse(hover.held)
    assertEquals(listOf(MirrorTouchEvent.TAPPED), touchUpdate(150).events)
  }

  @Test
  fun aFingerOutsideTheImageGetsNoRing() {
    val letterboxed = MirrorFit(0f, 100f, 1000f, 500f, MirrorRotation.NONE)
    val update = policy.update(letterboxed, listOf(f(500f, 50f)), 0)
    assertEquals(emptyList<MirrorFinger>(), update.rings)
    assertEquals(emptyList<MirrorTouchEvent>(), update.events)
  }

  private fun point(id: Long, x: Float, y: Float, down: Boolean, wasDown: Boolean) =
      MirrorTouchPoint(id, x, y, down = down, wasDown = wasDown)

  private fun frame(t: Long, vararg points: MirrorTouchPoint) = MirrorTouchFrame(points.toList(), t, fit)

  @Test
  fun directReleasesWhereTheFingerLeftTheGlass() {
    policy.update(frame(0, point(1, 100f, 200f, down = true, wasDown = false)))
    policy.update(frame(10, point(1, 300f, 400f, down = true, wasDown = true)))
    // The up event's own point is past the last move: drag there, then let go there.
    val up = policy.update(frame(20, point(1, 310f, 405f, down = false, wasDown = true)))
    assertEquals(listOf(Pointer(310, 405, 1), Pointer(310, 405, 0)), up.messages)
    assertFalse(policy.buttonHeld)
  }

  @Test
  fun aLiftAtTheLastMovesPointReleasesWithoutADrag() {
    policy.update(frame(0, point(1, 100f, 200f, down = true, wasDown = false)))
    policy.update(frame(10, point(1, 300f, 400f, down = true, wasDown = true)))
    val up = policy.update(frame(20, point(1, 300f, 400f, down = false, wasDown = true)))
    assertEquals(listOf(Pointer(300, 400, 0)), up.messages)
  }

  @Test
  fun afterTheLinkDropsTheRestOfTheTouchSendsNothing() {
    touch(0, f(100f, 200f))
    policy.abandon()
    assertFalse(policy.buttonHeld)
    // A new session starts while the finger is still down: its moves don't press again.
    assertEquals(emptyList<MirrorMessage>(), touch(10, f(120f, 200f)))
    assertEquals(emptyList<MirrorFinger>(), touchUpdate(20, f(130f, 200f)).rings)
    assertEquals(emptyList<MirrorMessage>(), touch(30))
    // Once every finger lifted, the next touch presses as usual.
    assertEquals(listOf(Pointer(50, 50, 1)), touch(40, f(50f, 50f)))
  }

  @Test
  fun aFirstFingerLandingStartsAfreshEvenIfTheLiftWasMissed() {
    // The link dropped mid-touch and the lift happened while the picture was away, unseen.
    touch(0, f(100f, 200f))
    policy.abandon()
    val down = policy.update(frame(10, point(5, 50f, 60f, down = true, wasDown = false)))
    assertEquals(listOf(Pointer(50, 60, 1)), down.messages)
    assertEquals(listOf(MirrorTouchEvent.PRESSED), down.events)
  }

  @Test
  fun aMissedLiftOfAHeldButtonIsReleasedBeforeTheNextPress() {
    touch(0, f(100f, 200f))
    val down = policy.update(frame(10, point(5, 50f, 60f, down = true, wasDown = false)))
    assertEquals(listOf(Pointer(100, 200, 0), Pointer(50, 60, 1)), down.messages)
  }

  @Test
  fun abandonWithNoTouchLeavesTheNextOneAlone() {
    policy.abandon()
    assertEquals(listOf(Pointer(10, 20, 1)), touch(0, f(10f, 20f)))
  }
}
