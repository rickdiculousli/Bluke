package dev.arnv.bluke.mirror

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class MirrorFramesTest {
  @Test
  fun theSlotKeepsTheNewestAndHandsBackWhatItDisplaced() {
    val slot = LatestFrameSlot<Int>()
    assertNull(slot.take())
    assertNull(slot.offer(1))
    assertEquals(1, slot.offer(2))
    assertEquals(2, slot.offer(3))
    assertEquals(3, slot.take())
    assertNull(slot.take())
    assertNull(slot.offer(4))
  }

  private class Buffer(val n: Int)

  @Test
  fun thePoolNeverHandsOutEitherOfTheTwoNewestPublishedBuffers() {
    var made = 0
    val pool = FramePool(create = { Buffer(made++) })
    val recent = ArrayDeque<Buffer>()
    repeat(20) {
      val b = pool.acquire()
      recent.forEach { r -> assertNotSame(r, b) }
      pool.publish(b)
      assertSame(b, pool.latest())
      recent.addLast(b)
      if (recent.size > 2) recent.removeFirst()
    }
    assertEquals(3, made)
  }

  @Test
  fun aFailedDecodeReusesTheSameBuffer() {
    val pool = FramePool(create = { Buffer(0) })
    assertNull(pool.latest())
    val a = pool.acquire()
    assertSame(a, pool.acquire())
  }

  @Test
  fun thePoolCyclesThroughItsBuffersInOrder() {
    var made = 0
    val pool = FramePool(create = { Buffer(made++) })
    val order = (0 until 6).map { pool.acquire().also { pool.publish(it) }.n }
    assertEquals(listOf(0, 1, 2, 0, 1, 2), order)
  }

  @Test
  fun fpsCountsFramesDrawnInTheLastSecond() {
    val rates = MirrorRates()
    assertEquals(0, rates.fps(0))
    for (t in 0L until 1000L step 50) rates.frameDrawn(t)
    assertEquals(20, rates.fps(999))
    assertEquals(19, rates.fps(1000))
    assertEquals(0, rates.fps(5000))
  }

  @Test
  fun latencyIsTheMedianOfTheLastFiveRoundTrips() {
    val rates = MirrorRates()
    assertNull(rates.latencyMs())
    rates.roundTrip(9_000)
    assertEquals(9L, rates.latencyMs())
    listOf(100_000L, 5_000L, 7_400L, 6_000L).forEach(rates::roundTrip)
    // 9, 100, 5, 7.4, 6 ms: median 7.4 → 7.
    assertEquals(7L, rates.latencyMs())
    // The oldest (9 ms) drops out: 100, 5, 7.4, 6, 8 → median 7.4.
    rates.roundTrip(8_000)
    assertEquals(7L, rates.latencyMs())
    rates.roundTrip(200_000)
    rates.roundTrip(300_000)
    // 6, 8, 200, 300, 7.4 → median 8.
    assertEquals(8L, rates.latencyMs())
  }

  @Test
  fun onlyFramesWithinTheAdvertisedSizeAreDecoded() {
    assertTrue(mirrorFrameSizeAllowed(480, 270))
    assertTrue(mirrorFrameSizeAllowed(1, 1))
    assertTrue(mirrorFrameSizeAllowed(MIRROR_MAX_WIDTH, MIRROR_MAX_HEIGHT))
    assertFalse(mirrorFrameSizeAllowed(0, 270))
    assertFalse(mirrorFrameSizeAllowed(480, 0))
    assertFalse(mirrorFrameSizeAllowed(-1, 10))
    assertFalse(mirrorFrameSizeAllowed(481, 270))
    assertFalse(mirrorFrameSizeAllowed(480, 481))
    assertFalse(mirrorFrameSizeAllowed(65535, 65535))
  }

  @Test
  fun theLinkGoesQuietAfterTwoSecondsWithNothingHeard() {
    val rates = MirrorRates()
    assertFalse(rates.linkQuiet(1_000_000))
    rates.reset(1_000)
    assertFalse(rates.linkQuiet(3_000))
    assertTrue(rates.linkQuiet(3_001))
    rates.heard(3_500)
    assertFalse(rates.linkQuiet(5_500))
    assertTrue(rates.linkQuiet(5_501))
  }

  @Test
  fun aNewSessionForgetsTheLastOnesNumbers() {
    val rates = MirrorRates()
    rates.roundTrip(6_000)
    rates.frameDrawn(100)
    rates.reset(200)
    assertNull(rates.latencyMs())
    assertEquals(0, rates.fps(200))
  }
}
