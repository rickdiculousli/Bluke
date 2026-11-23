package dev.arnv.bluke.mirror

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MirrorGeometryTest {
  private val max = MIRROR_NORMALIZED_MAX

  @Test
  fun aWideFrameOnATallSurfaceIsLetterboxedTopAndBottom() {
    val fit = fitMirror(480, 270, 1080f, 2000f, allowRotation = false)
    assertEquals(MirrorFit(0f, (2000f - 607.5f) / 2f, 1080f, 607.5f, MirrorRotation.NONE), fit)
  }

  @Test
  fun aTallFrameOnAWideSurfaceIsPillarboxedLeftAndRight() {
    val fit = fitMirror(300, 600, 1000f, 400f, allowRotation = false)
    assertEquals(MirrorFit(400f, 0f, 200f, 400f, MirrorRotation.NONE), fit)
  }

  @Test
  fun rotationIsUsedOnlyWhenAllowedAndLarger() {
    val rotated = fitMirror(480, 270, 1080f, 2000f, allowRotation = true)
    // Turned, the 270-wide side spans the surface width: scale min(1080/270, 2000/480) = 4.
    assertEquals(MirrorFit(0f, (2000f - 1920f) / 2f, 1080f, 1920f, MirrorRotation.CLOCKWISE), rotated)
    // On a wide surface the upright image is already larger, so it stays upright.
    assertEquals(MirrorRotation.NONE, fitMirror(480, 270, 2000f, 1080f, allowRotation = true).rotation)
  }

  @Test
  fun uprightCornersAndCentreMapExactly() {
    val fit = fitMirror(480, 270, 1080f, 2000f, allowRotation = false)
    assertEquals(NormalizedPoint(0, 0), fit.toNormalized(fit.left, fit.top))
    assertEquals(NormalizedPoint(max, 0), fit.toNormalized(fit.right, fit.top))
    assertEquals(NormalizedPoint(0, max), fit.toNormalized(fit.left, fit.bottom))
    assertEquals(NormalizedPoint(max, max), fit.toNormalized(fit.right, fit.bottom))
    assertEquals(NormalizedPoint(32768, 32768), fit.toNormalized(540f, 1000f))
  }

  @Test
  fun rotatedCornersMapToTheImagesCorners() {
    val fit = fitMirror(480, 270, 1080f, 2000f, allowRotation = true)
    // Turned clockwise: the image's top-left sits at the box's top-right, and so on round.
    assertEquals(NormalizedPoint(0, 0), fit.toNormalized(fit.right, fit.top))
    assertEquals(NormalizedPoint(max, 0), fit.toNormalized(fit.right, fit.bottom))
    assertEquals(NormalizedPoint(max, max), fit.toNormalized(fit.left, fit.bottom))
    assertEquals(NormalizedPoint(0, max), fit.toNormalized(fit.left, fit.top))
    // A quarter down the box's right edge is a quarter across the image's top edge.
    assertEquals(NormalizedPoint(16384, 0), fit.toNormalized(fit.right, fit.top + fit.height / 4f))
  }

  @Test
  fun pointsRoundToTheNearestUnit() {
    val fit = MirrorFit(0f, 0f, 65535f, 65535f, MirrorRotation.NONE)
    assertEquals(NormalizedPoint(1, 2), fit.toNormalized(1.4f, 1.6f))
    assertEquals(NormalizedPoint(65534, 65535), fit.toNormalized(65534.4f, 65534.6f))
  }

  @Test
  fun touchesOutsideTheImageAreRejected() {
    val fit = fitMirror(480, 270, 1080f, 2000f, allowRotation = false)
    assertNull(fit.toNormalized(540f, fit.top - 0.5f))
    assertNull(fit.toNormalized(540f, fit.bottom + 0.5f))
    assertNull(fit.toNormalized(-1f, 1000f))
    assertNull(fit.toNormalized(1080.5f, 1000f))
  }

  @Test
  fun aDragThatLeavesTheImageIsClampedToItsEdge() {
    val fit = fitMirror(480, 270, 1080f, 2000f, allowRotation = false)
    assertEquals(NormalizedPoint(32768, 0), fit.toNormalizedClamped(540f, 0f))
    assertEquals(NormalizedPoint(max, max), fit.toNormalizedClamped(5000f, 5000f))
    val rotated = fitMirror(480, 270, 1080f, 2000f, allowRotation = true)
    assertEquals(NormalizedPoint(0, 0), rotated.toNormalizedClamped(2000f, -100f))
  }

  @Test
  fun anEmptySurfaceOrFrameMapsNothing() {
    val fit = fitMirror(0, 270, 1080f, 2000f, allowRotation = false)
    assertEquals(0f, fit.width)
    assertNull(fit.toNormalized(0f, 0f))
    assertNull(fitMirror(480, 270, 0f, 0f, allowRotation = true).toNormalized(0f, 0f))
  }
}
