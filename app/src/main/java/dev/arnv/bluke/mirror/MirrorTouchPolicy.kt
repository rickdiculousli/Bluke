package dev.arnv.bluke.mirror

import kotlin.math.hypot

enum class MirrorTouchMode {
  /** Down presses at the point, move drags, up releases. */
  DIRECT,
  /** One finger moves without pressing, so tooltips show; a tap clicks. */
  HOVER,
}

/** One finger that is down, in surface pixels. [id] stays the same for a finger while it's down. */
data class MirrorFinger(val id: Long, val x: Float, val y: Float)

/**
 * Turns touches on the Mirror surface into Pointer, Wheel, Heartbeat and Release messages, free of
 * `MotionEvent`. The caller reports, on every touch event, the full set of fingers now down
 * ([update]); an empty set means every finger lifted.
 *
 * - One finger, Direct: a down inside the image presses there (`buttons=1`), moves drag (clamped
 *   to the image once they leave it), the last finger up releases at the last point.
 * - One finger, Hover: moves without pressing (`buttons=0`); a tap (held under [tapTimeoutMs], never
 *   moved [tapSlopPx] or more) clicks at the down point: press, then release.
 * - A second finger releases a held button, then the fingers' centroid movement scrolls as Wheel
 *   (pixels; fingers moving down give positive dy, scrolling the view up) until every finger lifts.
 * - A first finger down outside the image does nothing until every finger lifts.
 *
 * Not thread-safe: call it from the thread that delivers touches.
 */
class MirrorTouchPolicy(
    var mode: MirrorTouchMode = MirrorTouchMode.DIRECT,
    private val tapSlopPx: Float,
    private val tapTimeoutMs: Long = 400,
) {
  private sealed interface Gesture {
    data object Idle : Gesture

    /** Fingers are down but this gesture sends nothing (started outside the image, or a scroll ended). */
    data object Ignored : Gesture

    class OneFinger(
        val id: Long,
        val mode: MirrorTouchMode,
        val downX: Float,
        val downY: Float,
        val downAtMs: Long,
        val downPoint: NormalizedPoint,
        var last: NormalizedPoint,
        var leftSlop: Boolean = false,
    ) : Gesture

    class Scroll(var centroidX: Float, var centroidY: Float) : Gesture {
      var remainderX = 0f
      var remainderY = 0f
    }
  }

  private var gesture: Gesture = Gesture.Idle

  /** Whether the companion holds the left button for us now. */
  val buttonHeld: Boolean
    get() = (gesture as? Gesture.OneFinger)?.mode == MirrorTouchMode.DIRECT

  /** Reports the fingers down after a touch event at [timeMs], mapped through [fit]; returns the messages to send, in order. */
  fun update(fit: MirrorFit, fingers: List<MirrorFinger>, timeMs: Long): List<MirrorMessage> {
    val out = mutableListOf<MirrorMessage>()
    when (val g = gesture) {
      Gesture.Idle -> if (fingers.isNotEmpty()) start(fit, fingers, timeMs, out)
      Gesture.Ignored -> if (fingers.isEmpty()) gesture = Gesture.Idle
      is Gesture.OneFinger -> {
        val finger = fingers.singleOrNull()
        when {
          fingers.isEmpty() -> lift(g, timeMs, out)
          finger == null -> {
            if (g.mode == MirrorTouchMode.DIRECT) out += MirrorMessage.Pointer(g.last.x, g.last.y, 0)
            gesture = startScroll(fingers)
          }
          finger.id != g.id -> {
            lift(g, timeMs, out)
            start(fit, fingers, timeMs, out)
          }
          else -> move(g, fit, finger, out)
        }
      }
      is Gesture.Scroll -> {
        when {
          fingers.isEmpty() -> gesture = Gesture.Idle
          fingers.size == 1 -> gesture = Gesture.Ignored
          else -> scroll(g, fingers, out)
        }
      }
    }
    return out
  }

  /** The gesture was cancelled: releases a held button and forgets the gesture. */
  fun cancel(): List<MirrorMessage> {
    val g = gesture
    gesture = Gesture.Idle
    return if (g is Gesture.OneFinger && g.mode == MirrorTouchMode.DIRECT) {
      listOf(MirrorMessage.Pointer(g.last.x, g.last.y, 0))
    } else {
      emptyList()
    }
  }

  /** `Heartbeat` when a button is held and nothing has been sent for 250 ms or more, else null. */
  fun heartbeat(nowMs: Long, lastSentMs: Long): MirrorMessage? =
      if (buttonHeld && nowMs - lastSentMs >= HEARTBEAT_INTERVAL_MS) MirrorMessage.Heartbeat else null

  /** For the surface's `ON_STOP`: `Release`, and the gesture is forgotten. */
  fun release(): List<MirrorMessage> {
    gesture = Gesture.Idle
    return listOf(MirrorMessage.Release)
  }

  private fun start(fit: MirrorFit, fingers: List<MirrorFinger>, timeMs: Long, out: MutableList<MirrorMessage>) {
    if (fingers.size > 1) {
      gesture = startScroll(fingers)
      return
    }
    val f = fingers.single()
    val point = fit.toNormalized(f.x, f.y)
    if (point == null) {
      gesture = Gesture.Ignored
      return
    }
    gesture = Gesture.OneFinger(f.id, mode, f.x, f.y, timeMs, point, point)
    out += MirrorMessage.Pointer(point.x, point.y, if (mode == MirrorTouchMode.DIRECT) 1 else 0)
  }

  private fun move(g: Gesture.OneFinger, fit: MirrorFit, f: MirrorFinger, out: MutableList<MirrorMessage>) {
    if (hypot(f.x - g.downX, f.y - g.downY) >= tapSlopPx) g.leftSlop = true
    val point = fit.toNormalizedClamped(f.x, f.y)
    if (point == g.last) return
    g.last = point
    out += MirrorMessage.Pointer(point.x, point.y, if (g.mode == MirrorTouchMode.DIRECT) 1 else 0)
  }

  private fun lift(g: Gesture.OneFinger, timeMs: Long, out: MutableList<MirrorMessage>) {
    gesture = Gesture.Idle
    when (g.mode) {
      MirrorTouchMode.DIRECT -> out += MirrorMessage.Pointer(g.last.x, g.last.y, 0)
      MirrorTouchMode.HOVER ->
          if (!g.leftSlop && timeMs - g.downAtMs < tapTimeoutMs) {
            out += MirrorMessage.Pointer(g.downPoint.x, g.downPoint.y, 1)
            out += MirrorMessage.Pointer(g.downPoint.x, g.downPoint.y, 0)
          }
    }
  }

  private fun startScroll(fingers: List<MirrorFinger>): Gesture.Scroll =
      Gesture.Scroll(fingers.map { it.x }.average().toFloat(), fingers.map { it.y }.average().toFloat())

  private fun scroll(g: Gesture.Scroll, fingers: List<MirrorFinger>, out: MutableList<MirrorMessage>) {
    val cx = fingers.map { it.x }.average().toFloat()
    val cy = fingers.map { it.y }.average().toFloat()
    // Content follows the fingers: fingers moving down or right move the view up or left,
    // which PROTOCOL.md "Wheel" spells as positive dy and positive dx.
    val dx = cx - g.centroidX + g.remainderX
    val dy = cy - g.centroidY + g.remainderY
    g.centroidX = cx
    g.centroidY = cy
    val wholeX = dx.toInt()
    val wholeY = dy.toInt()
    g.remainderX = dx - wholeX
    g.remainderY = dy - wholeY
    if (wholeX == 0 && wholeY == 0) return
    out +=
        MirrorMessage.Wheel(
            wholeX.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()),
            wholeY.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()),
        )
  }

  companion object {
    const val HEARTBEAT_INTERVAL_MS = 250L
  }
}
