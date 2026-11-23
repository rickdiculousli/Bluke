package dev.arnv.bluke.mirror

import kotlin.math.roundToInt

enum class MirrorRotation {
  NONE,
  /** The image is turned 90° clockwise: its top edge lies along the box's right edge. */
  CLOCKWISE,
}

/**
 * Where the mirrored image sits on the surface: the on-screen box (in surface pixels) and how the
 * image is turned inside it. For [MirrorRotation.CLOCKWISE] the box's [width] spans the image's
 * height. Drawing and touch mapping both read this one value, so they can't disagree.
 */
data class MirrorFit(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val rotation: MirrorRotation,
) {
  val right: Float
    get() = left + width

  val bottom: Float
    get() = top + height
}

/** A point on the mirrored display: 0 is the left or top edge, 65535 the right or bottom edge. */
data class NormalizedPoint(val x: Int, val y: Int)

const val MIRROR_NORMALIZED_MAX = 65535

/**
 * Letterboxes a [frameWidth]×[frameHeight] image into the surface, as large as fits and centred.
 * With [allowRotation], the image is turned clockwise when that makes it larger.
 */
fun fitMirror(
    frameWidth: Int,
    frameHeight: Int,
    surfaceWidth: Float,
    surfaceHeight: Float,
    allowRotation: Boolean,
): MirrorFit {
  if (frameWidth <= 0 || frameHeight <= 0 || surfaceWidth <= 0f || surfaceHeight <= 0f) {
    return MirrorFit(0f, 0f, 0f, 0f, MirrorRotation.NONE)
  }
  val upright = minOf(surfaceWidth / frameWidth, surfaceHeight / frameHeight)
  val turned = minOf(surfaceWidth / frameHeight, surfaceHeight / frameWidth)
  val rotate = allowRotation && turned > upright
  val scale = if (rotate) turned else upright
  val boxWidth = (if (rotate) frameHeight else frameWidth) * scale
  val boxHeight = (if (rotate) frameWidth else frameHeight) * scale
  return MirrorFit(
      left = (surfaceWidth - boxWidth) / 2f,
      top = (surfaceHeight - boxHeight) / 2f,
      width = boxWidth,
      height = boxHeight,
      rotation = if (rotate) MirrorRotation.CLOCKWISE else MirrorRotation.NONE,
  )
}

/** The display point under surface pixel ([x], [y]), or null when it's outside the image. Edges are inside. */
fun MirrorFit.toNormalized(x: Float, y: Float): NormalizedPoint? {
  if (width <= 0f || height <= 0f) return null
  if (x < left || x > right || y < top || y > bottom) return null
  return toNormalizedClamped(x, y)
}

/** Like [toNormalized], but a point outside the image maps to the nearest edge (a drag that left it). */
fun MirrorFit.toNormalizedClamped(x: Float, y: Float): NormalizedPoint {
  if (width <= 0f || height <= 0f) return NormalizedPoint(0, 0)
  val u = ((x - left) / width).coerceIn(0f, 1f)
  val v = ((y - top) / height).coerceIn(0f, 1f)
  val (imageX, imageY) =
      when (rotation) {
        MirrorRotation.NONE -> u to v
        // Turned clockwise: the image's left edge is the box's top, its top edge the box's right.
        MirrorRotation.CLOCKWISE -> v to 1f - u
      }
  return NormalizedPoint(scale(imageX), scale(imageY))
}

private fun scale(f: Float): Int =
    (f.toDouble() * MIRROR_NORMALIZED_MAX).roundToInt().coerceIn(0, MIRROR_NORMALIZED_MAX)
