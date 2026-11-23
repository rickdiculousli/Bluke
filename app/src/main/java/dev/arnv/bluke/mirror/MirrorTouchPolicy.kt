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

/** Something a touch did at the computer, for the surface's haptics. */
enum class MirrorTouchEvent {
  /** Direct: a finger landed on the image and pressed the button. */
  PRESSED,
  /** Hover: a tap clicked. */
  TAPPED,
}

/**
 * What one touch event did: the [messages] to send, in order; the [events] it caused, in order;
 * and the fingers to mark on the picture ([rings]), filled when [held] (Direct holds the button).
 */
data class MirrorTouchUpdate(
    val messages: List<MirrorMessage>,
    val events: List<MirrorTouchEvent>,
    val rings: List<MirrorFinger>,
    val held: Boolean,
)

/**
 * Turns touches on the Mirror surface into Pointer, Wheel, Heartbeat and Release messages, free of
 * `MotionEvent`. The caller reports, on every touch event, the full set of fingers now down
 * ([update]); an empty set means every finger lifted, and [cancel] means the system took the touch.
 *
 * - One finger, Direct: a down inside the image presses there (`buttons=1`), moves drag (clamped
 *   to the image once they leave it), the lift releases where the finger left the glass (a drag
 *   there first if the lift's own point differs from the last move's).
 * - One finger, Hover: moves without pressing (`buttons=0`); a tap (held under [tapTimeoutMs], never
 *   moved [tapSlopPx] or more) clicks at the down point: press, then release. A cancel never clicks.
 * - A second finger that lands outside the image (a thumb resting on the bars beside it) is
 *   ignored until it lifts. One that lands on the image releases a held button, then the fingers'
 *   centroid movement scrolls as Wheel (pixels; fingers moving down give positive dy, scrolling the
 *   view up) until every finger lifts.
 * - A first finger down outside the image does nothing until every finger lifts.
 * - After the link drops mid-touch ([abandon]), the fingers still down send nothing until every
 *   one lifts, so a reconnect never turns the rest of the old touch into a fresh press.
 * - A first finger landing ([update]'s `firstDown`) always starts a new gesture, whatever this
 *   policy missed while it wasn't told about touches.
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
        /** Other fingers that landed outside the image while this one was down. */
        val ignored: MutableSet<Long> = mutableSetOf(),
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

  private class Out {
    val messages = mutableListOf<MirrorMessage>()
    val events = mutableListOf<MirrorTouchEvent>()
  }

  /**
   * Reports the fingers down after a touch event at [timeMs], mapped through [fit]. [lifted] are
   * the fingers this event lifted, where they left the glass; [firstDown] when this event is the
   * first finger landing (no finger was down before it).
   */
  fun update(
      fit: MirrorFit,
      fingers: List<MirrorFinger>,
      timeMs: Long,
      lifted: List<MirrorFinger> = emptyList(),
      firstDown: Boolean = false,
  ): MirrorTouchUpdate {
    val out = Out()
    if (firstDown && gesture != Gesture.Idle) {
      // A gesture whose end this policy never saw (it wasn't told while the picture was away).
      (gesture as? Gesture.OneFinger)?.let { releaseHeld(it, out) }
      gesture = Gesture.Idle
    }
    when (val g = gesture) {
      Gesture.Idle -> if (fingers.isNotEmpty()) start(fit, fingers, timeMs, out)
      Gesture.Ignored -> if (fingers.isEmpty()) gesture = Gesture.Idle
      is Gesture.OneFinger -> {
        val active = fingers.filter { it.id !in g.ignored }
        val tracked = active.find { it.id == g.id }
        val newcomers = active.filter { it.id != g.id }
        when {
          tracked != null && newcomers.isNotEmpty() && newcomers.all { fit.toNormalized(it.x, it.y) == null } -> {
            g.ignored += newcomers.map { it.id }
            move(g, fit, tracked, out)
          }
          active.isEmpty() -> {
            lift(g, fit, lifted.find { it.id == g.id }, timeMs, out)
            if (fingers.isNotEmpty()) gesture = Gesture.Ignored
          }
          tracked == null -> {
            lift(g, fit, lifted.find { it.id == g.id }, timeMs, out)
            start(fit, active, timeMs, out)
          }
          newcomers.isEmpty() -> move(g, fit, tracked, out)
          else -> {
            releaseHeld(g, out)
            gesture = startScroll(active)
          }
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
    return result(out, fingers)
  }

  /** The system cancelled the touch: releases a held button (never a click) and forgets the gesture. */
  fun cancel(): MirrorTouchUpdate {
    val out = Out()
    (gesture as? Gesture.OneFinger)?.let { releaseHeld(it, out) }
    gesture = Gesture.Idle
    return result(out, emptyList())
  }

  /**
   * The link dropped: the session that held the button is gone (the companion releases on its
   * own), so nothing is sent; fingers still down are ignored until every one lifts.
   */
  fun abandon() {
    if (gesture != Gesture.Idle) gesture = Gesture.Ignored
  }

  /** `Heartbeat` when a button is held and nothing has been sent for 250 ms or more, else null. */
  fun heartbeat(nowMs: Long, lastSentMs: Long): MirrorMessage? =
      if (buttonHeld && nowMs - lastSentMs >= HEARTBEAT_INTERVAL_MS) MirrorMessage.Heartbeat else null

  /** For the surface's `ON_STOP`: `Release`, and the gesture is forgotten. */
  fun release(): List<MirrorMessage> {
    gesture = Gesture.Idle
    return listOf(MirrorMessage.Release)
  }

  private fun result(out: Out, fingers: List<MirrorFinger>): MirrorTouchUpdate {
    val rings =
        when (val g = gesture) {
          is Gesture.OneFinger -> fingers.filter { it.id == g.id }
          is Gesture.Scroll -> fingers
          else -> emptyList()
        }
    return MirrorTouchUpdate(out.messages, out.events, rings, held = buttonHeld)
  }

  private fun start(fit: MirrorFit, fingers: List<MirrorFinger>, timeMs: Long, out: Out) {
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
    if (mode == MirrorTouchMode.DIRECT) {
      out.messages += MirrorMessage.Pointer(point.x, point.y, 1)
      out.events += MirrorTouchEvent.PRESSED
    } else {
      out.messages += MirrorMessage.Pointer(point.x, point.y, 0)
    }
  }

  private fun move(g: Gesture.OneFinger, fit: MirrorFit, f: MirrorFinger, out: Out) {
    if (hypot(f.x - g.downX, f.y - g.downY) >= tapSlopPx) g.leftSlop = true
    val point = fit.toNormalizedClamped(f.x, f.y)
    if (point == g.last) return
    g.last = point
    out.messages += MirrorMessage.Pointer(point.x, point.y, if (g.mode == MirrorTouchMode.DIRECT) 1 else 0)
  }

  private fun lift(g: Gesture.OneFinger, fit: MirrorFit, at: MirrorFinger?, timeMs: Long, out: Out) {
    gesture = Gesture.Idle
    when (g.mode) {
      MirrorTouchMode.DIRECT -> {
        // The up event carries its own point; drag there before letting go.
        at?.let { fit.toNormalizedClamped(it.x, it.y) }?.takeIf { it != g.last }?.let { point ->
          g.last = point
          out.messages += MirrorMessage.Pointer(point.x, point.y, 1)
        }
        releaseHeld(g, out)
      }
      MirrorTouchMode.HOVER ->
          if (!g.leftSlop && timeMs - g.downAtMs < tapTimeoutMs) {
            out.messages += MirrorMessage.Pointer(g.downPoint.x, g.downPoint.y, 1)
            out.messages += MirrorMessage.Pointer(g.downPoint.x, g.downPoint.y, 0)
            out.events += MirrorTouchEvent.TAPPED
          }
    }
  }

  private fun releaseHeld(g: Gesture.OneFinger, out: Out) {
    if (g.mode != MirrorTouchMode.DIRECT) return
    out.messages += MirrorMessage.Pointer(g.last.x, g.last.y, 0)
  }

  private fun startScroll(fingers: List<MirrorFinger>): Gesture.Scroll {
    return Gesture.Scroll(fingers.map { it.x }.average().toFloat(), fingers.map { it.y }.average().toFloat())
  }

  private fun scroll(g: Gesture.Scroll, fingers: List<MirrorFinger>, out: Out) {
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
    out.messages +=
        MirrorMessage.Wheel(
            wholeX.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()),
            wholeY.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()),
        )
  }

  companion object {
    const val HEARTBEAT_INTERVAL_MS = 250L
  }
}

/**
 * One surface touch event through the policy: a cancel cancels, else the fingers still down, the
 * ones this event lifted, and whether it is the first finger landing.
 */
fun MirrorTouchPolicy.update(frame: MirrorTouchFrame): MirrorTouchUpdate =
    if (frame.cancelled) {
      cancel()
    } else {
      update(
          frame.fit,
          frame.points.filter { it.down }.map { MirrorFinger(it.id, it.x, it.y) },
          frame.uptimeMillis,
          lifted = frame.points.filter { !it.down && it.wasDown }.map { MirrorFinger(it.id, it.x, it.y) },
          firstDown = frame.points.none { it.wasDown },
      )
    }
