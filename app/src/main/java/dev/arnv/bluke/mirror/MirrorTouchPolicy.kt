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
  /** Direct: a finger landed on the image and pressed the button; or the left-button key pressed it. */
  PRESSED,
  /** Hover: a tap clicked. */
  TAPPED,
  /** The left-button key let go of the button. */
  RELEASED,
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
 *   centroid movement scrolls as Wheel, after a `buttons=0` move to the centroid so the computer scrolls under the fingers (pixels; fingers moving down give positive dy, scrolling the
 *   view up) until every finger lifts.
 * - A first finger down outside the image does nothing until every finger lifts.
 * - After the link drops mid-touch ([abandon]), the fingers still down send nothing until every
 *   one lifts, so a reconnect never turns the rest of the old touch into a fresh press.
 * - A first finger landing ([update]'s `firstDown`) always starts a new gesture, whatever this
 *   policy missed while it wasn't told about touches.
 *
 * The left-button key (Hover only; [pressKey], [liftKey]) holds the button from the thumb:
 * - Its press sends `buttons=1` at the last point this policy sent this session, and its lift
 *   `buttons=0` at the point last sent. Mirror can't know where the computer's cursor is now, so
 *   before any point has been sent this session the key is inert, and a press that was inert stays
 *   so until the thumb lifts.
 * - While it's held, the finger on the picture drags (`buttons=1`); a finger landing elsewhere moves
 *   the pointer straight there, still held. Lifting it doesn't release, a tap doesn't click, and a
 *   second finger doesn't scroll: only the key's lift releases. After the lift, a finger still down
 *   goes on hovering, never clicking.
 * - A link drop ([abandon]), the surface leaving ([release]) or a mode change ([changeMode]) lets go:
 *   the key reads released, and the thumb still on it does nothing until it lifts.
 *
 * Fingers that landed on one of the overlay's controls belong to that control until they lift
 * ([MirrorTouchPoint.onControl], set by [MirrorFingerRouter]): the gesture logic never sees them.
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

    /**
     * The left-button key holds the button. [trackedId] is the finger the pointer follows, if one
     * is down; [ignored] are other fingers, which do nothing until they lift.
     */
    class KeyHeld(var trackedId: Long?, val ignored: MutableSet<Long>) : Gesture
  }

  private var gesture: Gesture = Gesture.Idle

  /** Where the last Pointer this policy produced went, this session; null until one has. */
  private var lastSent: NormalizedPoint? = null

  /** The fingers down at the last [update], so a key press knows which are already there. */
  private var lastFingers: List<MirrorFinger> = emptyList()

  /** Whether the left-button key holds the button now. */
  val keyHeld: Boolean
    get() = gesture is Gesture.KeyHeld

  /** Whether the companion holds the left button for us now: a Direct finger, or the key. */
  val buttonHeld: Boolean
    get() = keyHeld || (gesture as? Gesture.OneFinger)?.mode == MirrorTouchMode.DIRECT

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
    // The key's hold outlives the fingers: a fresh landing just starts following the new finger.
    if (firstDown && gesture != Gesture.Idle && gesture !is Gesture.KeyHeld) {
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
            gesture = startScroll(fit, active, out)
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
      is Gesture.KeyHeld -> heldMove(g, fit, fingers, lifted, out)
    }
    lastFingers = fingers
    return result(out, fingers)
  }

  /**
   * The system cancelled the touch on the picture: releases a finger's held button (never a click)
   * and forgets the gesture. The key's hold stays: the key's own lift (or cancel) lets go of it.
   */
  fun cancel(): MirrorTouchUpdate {
    val out = Out()
    when (val g = gesture) {
      is Gesture.OneFinger -> {
        releaseHeld(g, out)
        gesture = Gesture.Idle
      }
      is Gesture.KeyHeld -> {
        g.trackedId = null
        g.ignored.clear()
      }
      else -> gesture = Gesture.Idle
    }
    lastFingers = emptyList()
    return result(out, emptyList())
  }

  /**
   * The left-button key went down: in Hover, with a point already sent this session, presses at
   * that point and holds. Otherwise (Direct, nothing sent yet, already held) it does nothing.
   */
  fun pressKey(): MirrorTouchUpdate {
    val out = Out()
    val at = lastSent
    if (mode != MirrorTouchMode.HOVER || at == null || keyHeld) return result(out, lastFingers)
    val down = lastFingers.map { it.id }
    gesture =
        when (val g = gesture) {
          // The hovering finger becomes the drag.
          is Gesture.OneFinger -> Gesture.KeyHeld(g.id, (down - g.id).toMutableSet())
          else -> Gesture.KeyHeld(null, down.toMutableSet())
        }
    out.messages += MirrorMessage.Pointer(at.x, at.y, 1)
    out.events += MirrorTouchEvent.PRESSED
    return result(out, lastFingers)
  }

  /**
   * The left-button key lifted: releases at the point last sent. A finger still on the picture
   * goes on hovering, and its lift never clicks. Does nothing unless the key holds the button.
   */
  fun liftKey(): MirrorTouchUpdate {
    val out = Out()
    val g = gesture as? Gesture.KeyHeld ?: return result(out, lastFingers)
    val at = checkNotNull(lastSent) { "the key held without a point sent" }
    out.messages += MirrorMessage.Pointer(at.x, at.y, 0)
    out.events += MirrorTouchEvent.RELEASED
    val tracked = g.trackedId?.let { id -> lastFingers.find { it.id == id } }
    gesture =
        when {
          tracked != null ->
              Gesture.OneFinger(
                  tracked.id, MirrorTouchMode.HOVER, tracked.x, tracked.y, 0L, at, at, leftSlop = true, ignored = g.ignored)
          lastFingers.isNotEmpty() -> Gesture.Ignored
          else -> Gesture.Idle
        }
    return result(out, lastFingers)
  }

  /** Switches Direct and Hover: lets go of whatever holds the button first (key or finger). */
  fun changeMode(next: MirrorTouchMode): MirrorTouchUpdate {
    val fingersDown = lastFingers.isNotEmpty()
    val lifted = liftKey()
    val cancelled = cancel()
    // A finger still on the picture belongs to the old mode: it does nothing until it lifts.
    gesture = if (fingersDown) Gesture.Ignored else Gesture.Idle
    mode = next
    return MirrorTouchUpdate(lifted.messages + cancelled.messages, lifted.events, emptyList(), held = false)
  }

  /**
   * The link dropped: the session that held the button is gone (the companion releases on its
   * own), so nothing is sent; fingers still down are ignored until every one lifts, and the key
   * reads released. The next session has sent no point yet, so the key is inert until one is.
   */
  fun abandon() {
    if (gesture != Gesture.Idle) gesture = Gesture.Ignored
    lastSent = null
  }

  /** `Heartbeat` when a button is held and nothing has been sent for 250 ms or more, else null. */
  fun heartbeat(nowMs: Long, lastSentMs: Long): MirrorMessage? =
      if (buttonHeld && nowMs - lastSentMs >= HEARTBEAT_INTERVAL_MS) MirrorMessage.Heartbeat else null

  /** For the surface's `ON_STOP`: `Release`, and the gesture (and the key's hold) is forgotten. */
  fun release(): List<MirrorMessage> {
    gesture = Gesture.Idle
    lastSent = null
    lastFingers = emptyList()
    return listOf(MirrorMessage.Release)
  }

  private fun result(out: Out, fingers: List<MirrorFinger>): MirrorTouchUpdate {
    out.messages.lastOrNull { it is MirrorMessage.Pointer }?.let {
      it as MirrorMessage.Pointer
      lastSent = NormalizedPoint(it.x, it.y)
    }
    val rings =
        when (val g = gesture) {
          is Gesture.OneFinger -> fingers.filter { it.id == g.id }
          is Gesture.Scroll -> fingers
          is Gesture.KeyHeld -> fingers.filter { it.id == g.trackedId }
          else -> emptyList()
        }
    return MirrorTouchUpdate(out.messages, out.events, rings, held = buttonHeld)
  }

  /**
   * One touch event while the key holds the button: the tracked finger drags; with none tracked, a
   * finger landing on the image moves the pointer straight there and becomes the tracked one.
   * Every other finger is ignored until it lifts, so nothing scrolls, clicks or lets go.
   */
  private fun heldMove(
      g: Gesture.KeyHeld,
      fit: MirrorFit,
      fingers: List<MirrorFinger>,
      lifted: List<MirrorFinger>,
      out: Out,
  ) {
    g.ignored.retainAll(fingers.map { it.id }.toSet())
    val tracked = g.trackedId?.let { id -> fingers.find { it.id == id } }
    val follow =
        tracked
            ?: run {
              // The tracked finger lifted: drag to where it left the glass, still held.
              g.trackedId?.let { id -> lifted.find { it.id == id } }?.let { dragHeld(fit.toNormalizedClamped(it.x, it.y), out) }
              g.trackedId = null
              fingers.firstOrNull { it.id !in g.ignored && fit.toNormalized(it.x, it.y) != null }
            }
    fingers.filter { it.id != follow?.id }.forEach { g.ignored += it.id }
    follow ?: return
    g.trackedId = follow.id
    dragHeld(fit.toNormalizedClamped(follow.x, follow.y), out)
  }

  /** A held move to [point], unless the pointer is there already. */
  private fun dragHeld(point: NormalizedPoint, out: Out) {
    val current = (out.messages.lastOrNull { it is MirrorMessage.Pointer } as MirrorMessage.Pointer?)
        ?.let { NormalizedPoint(it.x, it.y) } ?: lastSent
    if (point != current) out.messages += MirrorMessage.Pointer(point.x, point.y, 1)
  }

  private fun start(fit: MirrorFit, fingers: List<MirrorFinger>, timeMs: Long, out: Out) {
    if (fingers.size > 1) {
      gesture = startScroll(fit, fingers, out)
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

  /**
   * Starts a scroll at the fingers' centroid. A Wheel carries no position and the computer
   * scrolls under its cursor, so the cursor moves there first (`buttons=0`).
   */
  private fun startScroll(fit: MirrorFit, fingers: List<MirrorFinger>, out: Out): Gesture.Scroll {
    val cx = fingers.map { it.x }.average().toFloat()
    val cy = fingers.map { it.y }.average().toFloat()
    val at = fit.toNormalizedClamped(cx, cy)
    out.messages += MirrorMessage.Pointer(at.x, at.y, 0)
    return Gesture.Scroll(cx, cy)
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

    /**
     * Whether the companion's held-button rule (released after 1 s of silence) counts [message] as
     * the phone being alive: the touch thread's traffic and Ping, never FrameAck, which the decoder
     * sends even while the touch thread hangs. The heartbeat keys on the last of these.
     */
    fun countsAsLiveness(message: MirrorMessage): Boolean =
        when (message) {
          is MirrorMessage.Pointer,
          is MirrorMessage.Wheel,
          MirrorMessage.Heartbeat,
          MirrorMessage.Release,
          is MirrorMessage.Ping -> true
          else -> false
        }
  }
}

/**
 * One surface touch event through the policy: a cancel cancels, else the fingers still down, the
 * ones this event lifted, and whether it is the first finger landing. Fingers that belong to an
 * overlay control aren't part of it at all; an event that only moved them changes nothing.
 */
fun MirrorTouchPolicy.update(frame: MirrorTouchFrame): MirrorTouchUpdate {
  if (frame.cancelled) return cancel()
  val picture = frame.points.filter { !it.onControl }
  return update(
      frame.fit,
      picture.filter { it.down }.map { MirrorFinger(it.id, it.x, it.y) },
      frame.uptimeMillis,
      lifted = picture.filter { !it.down && it.wasDown }.map { MirrorFinger(it.id, it.x, it.y) },
      firstDown = picture.any { it.down && !it.wasDown } && picture.none { it.wasDown },
  )
}

/**
 * Which pointers belong to an overlay control, decided once per pointer, where it landed: one that
 * lands on a control belongs to it until it lifts, one that lands anywhere else is a picture finger.
 * A pointer first seen already down landed where the picture couldn't see it (on a control, above
 * the picture), so it's a control's too. Not thread-safe: call it from the thread that delivers touches.
 */
class MirrorFingerRouter {
  private val onControl = mutableMapOf<Long, Boolean>()

  /** [frame] with each point's [MirrorTouchPoint.onControl] set; [hitsControl] tests a landing point. */
  fun route(frame: MirrorTouchFrame, hitsControl: (x: Float, y: Float) -> Boolean): MirrorTouchFrame {
    // An event carries every pointer on the window, so an id missing from it has lifted, even one
    // whose lift this layer never saw (a thumb that let go after the picture's last finger did).
    onControl.keys.retainAll(frame.points.map { it.id }.toSet())
    val points =
        frame.points.map { p ->
          // Android reuses pointer ids, so every landing is decided afresh, never from an old owner.
          val owned =
              if (!p.wasDown) hitsControl(p.x, p.y).also { onControl[p.id] = it }
              else onControl.getOrPut(p.id) { true }
          p.copy(onControl = owned)
        }
    if (frame.cancelled) onControl.clear() else frame.points.filter { !it.down }.forEach { onControl -= it.id }
    return frame.copy(points = points)
  }
}
