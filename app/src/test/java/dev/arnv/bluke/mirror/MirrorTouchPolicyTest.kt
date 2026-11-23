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

  private fun touch(timeMs: Long, vararg fingers: MirrorFinger) = policy.update(fit, fingers.toList(), timeMs)

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
    assertEquals(listOf(Pointer(32768, 32768, 1)), policy.update(letterboxed, listOf(f(500f, 350f)), 0))
    assertEquals(listOf(Pointer(32768, 0, 1)), policy.update(letterboxed, listOf(f(500f, 0f)), 10))
    assertEquals(listOf(Pointer(32768, 0, 0)), policy.update(letterboxed, emptyList(), 20))
  }

  @Test
  fun aDownOutsideTheImageDoesNothingUntilEveryFingerLifts() {
    val letterboxed = MirrorFit(0f, 100f, 1000f, 500f, MirrorRotation.NONE)
    assertEquals(emptyList<MirrorMessage>(), policy.update(letterboxed, listOf(f(500f, 50f)), 0))
    assertEquals(emptyList<MirrorMessage>(), policy.update(letterboxed, listOf(f(500f, 350f)), 10))
    assertEquals(emptyList<MirrorMessage>(), policy.update(letterboxed, listOf(f(500f, 350f), f(600f, 350f, 2)), 20))
    assertFalse(policy.buttonHeld)
    assertEquals(emptyList<MirrorMessage>(), policy.update(letterboxed, emptyList(), 30))
    assertEquals(listOf(Pointer(32768, 32768, 1)), policy.update(letterboxed, listOf(f(500f, 350f)), 40))
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
    assertEquals(listOf(Pointer(110, 200, 0)), policy.cancel())
    assertFalse(policy.buttonHeld)
    assertEquals(emptyList<MirrorMessage>(), policy.cancel())
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
    assertEquals(listOf(Pointer(0, 0, 1)), policy.update(rotated, listOf(f(65535f, 0f)), 0))
  }
}
