package dev.arnv.bluke.mirror

/**
 * Holds the newest received frame for the decoder. Latest wins: a frame not yet taken is replaced
 * by a newer one, and [offer] hands back the one it displaced so the caller can ack it as dropped
 * (`FrameAck(seq, MIRROR_FRAME_DROPPED)`). Thread-safe: the reader offers, the decoder takes.
 */
class LatestFrameSlot<T : Any> {
  private var pending: T? = null

  /** Stores [frame]; returns the frame it replaced, or null. */
  @Synchronized
  fun offer(frame: T): T? {
    val displaced = pending
    pending = frame
    return displaced
  }

  /** The newest frame, emptying the slot; null when nothing is waiting. */
  @Synchronized
  fun take(): T? {
    val frame = pending
    pending = null
    return frame
  }
}

/**
 * The buffers frames are decoded into, reused. [acquire] picks the buffer to decode the next frame
 * into, never one of the two most recently [publish]ed (the render thread may still be drawing the
 * newest, and may have just swapped away from the one before). Buffers are made by [create] on
 * first need, at most [size] of them. One decoder thread: acquire, decode, publish. Acquiring again
 * without publishing (a failed decode) returns the same buffer.
 */
class FramePool<B : Any>(private val size: Int = 3, private val create: () -> B) {
  init {
    require(size >= 3) { "a pool needs at least 3 buffers to keep 2 published" }
  }

  private val buffers = mutableListOf<B>()
  private val published = ArrayDeque<B>(2)

  @Synchronized
  fun acquire(): B {
    buffers.firstOrNull { candidate -> published.none { it === candidate } }?.let { return it }
    return create().also { buffers += it }
  }

  /** [buffer] now holds the newest decoded frame and is handed to the renderer. */
  @Synchronized
  fun publish(buffer: B) {
    require(buffers.any { it === buffer }) { "buffer is not from this pool" }
    published.removeAll { it === buffer }
    published.addLast(buffer)
    while (published.size > 2) published.removeFirst()
  }

  /** The newest published buffer, for the renderer; null before the first publish. */
  @Synchronized
  fun latest(): B? = published.lastOrNull()
}

/** The largest frame the phone takes, per axis; it tells the companion so in Welcome. */
const val MIRROR_MAX_WIDTH = 480
const val MIRROR_MAX_HEIGHT = 480

/**
 * Whether a Frame's header size is one the phone decodes: 1 to [MIRROR_MAX_WIDTH] by 1 to
 * [MIRROR_MAX_HEIGHT]. Anything else is dropped before a buffer is made for it (a zero side can't
 * make a bitmap; a huge one would exhaust memory).
 */
fun mirrorFrameSizeAllowed(width: Int, height: Int): Boolean =
    width in 1..MIRROR_MAX_WIDTH && height in 1..MIRROR_MAX_HEIGHT

/**
 * The Mirror toolbar's numbers: frames drawn per second, link latency, and whether the link has
 * gone quiet. Thread-safe. Times are milliseconds on one monotonic clock.
 */
class MirrorRates {
  private val drawnAtMs = ArrayDeque<Long>()
  private val roundTripsUs = ArrayDeque<Long>()
  private var lastHeardMs: Long? = null

  /** A session began at [nowMs]: earlier sessions' numbers are forgotten, and the link counts as heard. */
  @Synchronized
  fun reset(nowMs: Long) {
    drawnAtMs.clear()
    roundTripsUs.clear()
    lastHeardMs = nowMs
  }

  /** A message arrived from the companion at [nowMs]. */
  @Synchronized
  fun heard(nowMs: Long) {
    lastHeardMs = nowMs
  }

  /**
   * The liveness rule: the companion answers a Ping every second, so nothing heard for more than
   * [LINK_QUIET_MS] means at least one answer is overdue and the picture may be frozen. False
   * before any session.
   */
  @Synchronized
  fun linkQuiet(nowMs: Long): Boolean = lastHeardMs?.let { nowMs - it > LINK_QUIET_MS } ?: false

  /** A frame was drawn at [nowMs] (monotonic). */
  @Synchronized
  fun frameDrawn(nowMs: Long) {
    drawnAtMs.addLast(nowMs)
    prune(nowMs)
  }

  /** Frames drawn in the second up to [nowMs]: (nowMs − 1000, nowMs]. */
  @Synchronized
  fun fps(nowMs: Long): Int {
    prune(nowMs)
    return drawnAtMs.size
  }

  /** A Pong arrived [roundTripUs] microseconds after its Ping was sent. */
  @Synchronized
  fun roundTrip(roundTripUs: Long) {
    roundTripsUs.addLast(roundTripUs)
    while (roundTripsUs.size > 5) roundTripsUs.removeFirst()
  }

  /**
   * The median of the last five round trips in whole milliseconds (rounded), the upper middle
   * while fewer than five, odd or even; null before the first Pong.
   */
  @Synchronized
  fun latencyMs(): Long? {
    if (roundTripsUs.isEmpty()) return null
    val sorted = roundTripsUs.sorted()
    return (sorted[sorted.size / 2] + 500) / 1000
  }

  companion object {
    const val LINK_QUIET_MS = 2_000L
  }

  private fun prune(nowMs: Long) {
    while (drawnAtMs.isNotEmpty() && drawnAtMs.first() <= nowMs - 1000) drawnAtMs.removeFirst()
  }
}
