package dev.arnv.bluke.ui

import android.content.SharedPreferences
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToInt

const val TOUCHPAD_SCROLL_SPEED_PREFERENCE = "touchpad_scroll_sensitivity"
const val TOUCHPAD_INVERT_SCROLL_PREFERENCE = "touchpad_invert_scroll"
const val TOUCHPAD_SCROLL_SMOOTHING_PREFERENCE = "touchpad_scroll_smoothing"
const val TOUCHPAD_SCROLL_MOMENTUM_PREFERENCE = "touchpad_scroll_momentum"
const val TOUCHPAD_SCROLL_GLIDE_PREFERENCE = "touchpad_scroll_glide"

/** How two-finger scrolling feels. [smoothing] and [glide] run from 0 (off / shortest) to 1. */
data class TouchpadScrollSettings(
    val speed: Float = 1f,
    val inverted: Boolean = false,
    val smoothing: Float = 0.35f,
    val momentum: Boolean = true,
    val glide: Float = 0.5f,
) {
    companion object {
        fun from(prefs: SharedPreferences): TouchpadScrollSettings {
            val defaults = TouchpadScrollSettings()
            return TouchpadScrollSettings(
                speed = prefs.getFloat(TOUCHPAD_SCROLL_SPEED_PREFERENCE, defaults.speed),
                inverted = prefs.getBoolean(TOUCHPAD_INVERT_SCROLL_PREFERENCE, defaults.inverted),
                smoothing = prefs.getFloat(TOUCHPAD_SCROLL_SMOOTHING_PREFERENCE, defaults.smoothing).coerceIn(0f, 1f),
                momentum = prefs.getBoolean(TOUCHPAD_SCROLL_MOMENTUM_PREFERENCE, defaults.momentum),
                glide = prefs.getFloat(TOUCHPAD_SCROLL_GLIDE_PREFERENCE, defaults.glide).coerceIn(0f, 1f),
            )
        }
    }
}

/** One mouse report's worth of scrolling: vertical wheel and horizontal pan steps. */
data class ScrollReport(val wheel: Int, val pan: Int)

/**
 * Turns two-finger movement into wheel and pan steps. The view feeds it finger positions as
 * they arrive and calls [tick] on a steady frame clock while [isActive]; ticks produce the reports.
 *
 * - Direction: content follows the fingers (fingers down scroll up: wheel +; fingers right
 *   scroll left: pan -); [TouchpadScrollSettings.inverted] flips both.
 * - Axis lock: a gesture twice as long on one axis as the other scrolls only that axis.
 * - Smoothing: the scroll trails the fingers through a low-pass filter, then catches up after they lift.
 * - Momentum: lifting every finger while moving keeps scrolling and slows to a stop; a new touch stops it.
 * - Handover: after a scroll ends, a remaining finger moves the pointer only once [allowsPointing].
 */
class TouchpadScroller(var settings: TouchpadScrollSettings = TouchpadScrollSettings()) {
    private var scrolling = false
    private var settling = false
    private var gliding = false
    private var originX = 0f
    private var originY = 0f
    private var targetX = 0f
    private var targetY = 0f
    private var smoothX = 0f
    private var smoothY = 0f
    private var lastTick = 0L
    private var accWheel = 0f
    private var accPan = 0f
    private var glideWheel = 0f // scroll units per millisecond
    private var glidePan = 0f
    private var endedAt = Long.MIN_VALUE / 2
    private var releaseWheel = 0f
    private var releasePan = 0f
    private val samples = ArrayDeque<Sample>()

    private class Sample(val time: Long, val x: Float, val y: Float)

    /** Something is still to be sent: fingers are scrolling, the smoothed scroll is catching up, or it glides. */
    val isActive: Boolean get() = scrolling || settling || gliding

    /** Two fingers landed with their midpoint at ([x], [y]). Stops any glide. */
    fun begin(x: Float, y: Float, time: Long) {
        stop()
        scrolling = true
        originX = x; originY = y
        targetX = x; targetY = y
        smoothX = x; smoothY = y
        lastTick = time
        samples.addLast(Sample(time, x, y))
    }

    fun move(x: Float, y: Float, time: Long) {
        if (!scrolling) return
        targetX = x; targetY = y
        samples.addLast(Sample(time, x, y))
        while (samples.size > 2 && time - samples.first().time > VELOCITY_WINDOW_MILLIS) samples.removeFirst()
    }

    /** The fingers stopped scrolling (one lifted or both did). The smoothed remainder still plays out. */
    fun end(time: Long) {
        if (!scrolling) return
        scrolling = false
        settling = settings.smoothing > 0f
        endedAt = time
        val (wheel, pan) = releaseVelocity(time)
        releaseWheel = wheel
        releasePan = pan
    }

    /** Every finger is up. Starts a glide when the scroll ended moments ago while still moving. */
    fun liftAll(time: Long) {
        if (scrolling) end(time)
        if (!settings.momentum || time - endedAt > MOMENTUM_LIFT_GRACE_MILLIS) return
        if (hypot(releaseWheel, releasePan) < MIN_GLIDE_START_UNITS_PER_MS) return
        glideWheel = releaseWheel
        glidePan = releasePan
        releaseWheel = 0f
        releasePan = 0f
        gliding = true
    }

    /** A remaining finger may move the pointer: no scroll is running and the last one ended a moment ago. */
    fun allowsPointing(time: Long): Boolean = !scrolling && time - endedAt >= POINTER_RESUME_MILLIS

    /** Stops scrolling at once, dropping anything not yet sent. */
    fun stop() {
        scrolling = false
        settling = false
        gliding = false
        accWheel = 0f
        accPan = 0f
        releaseWheel = 0f
        releasePan = 0f
        samples.clear()
    }

    fun tick(time: Long): ScrollReport? {
        val dt = (time - lastTick).coerceIn(0L, MAX_TICK_MILLIS).toFloat()
        lastTick = time
        if (scrolling || settling) {
            val tau = settings.smoothing * MAX_SMOOTHING_MILLIS
            val follow = if (tau <= 0f) 1f else 1f - exp(-dt / tau)
            val nextX = smoothX + (targetX - smoothX) * follow
            val nextY = smoothY + (targetY - smoothY) * follow
            addUnits(nextX - smoothX, nextY - smoothY)
            smoothX = nextX
            smoothY = nextY
            if (settling && abs(targetX - smoothX) < SETTLED_PX && abs(targetY - smoothY) < SETTLED_PX) {
                addUnits(targetX - smoothX, targetY - smoothY)
                smoothX = targetX
                smoothY = targetY
                settling = false
            }
        }
        if (gliding) {
            accWheel += glideWheel * dt
            accPan += glidePan * dt
            val decay = exp(-dt / glideTimeConstant())
            glideWheel *= decay
            glidePan *= decay
            if (hypot(glideWheel, glidePan) < MIN_GLIDE_UNITS_PER_MS) gliding = false
        }
        val wheel = accWheel.roundToInt().coerceIn(-127, 127)
        val pan = accPan.roundToInt().coerceIn(-127, 127)
        accWheel -= wheel
        accPan -= pan
        if (!isActive) {
            accWheel = 0f
            accPan = 0f
        }
        return if (wheel != 0 || pan != 0) ScrollReport(wheel, pan) else null
    }

    /** Finger movement in px to scroll units, with direction and axis lock applied. */
    private fun units(dx: Float, dy: Float): Pair<Float, Float> {
        val travelX = abs(targetX - originX)
        val travelY = abs(targetY - originY)
        val scale = UNITS_PER_PX * settings.speed * if (settings.inverted) -1f else 1f
        val wheel = if (travelX >= travelY * AXIS_LOCK_RATIO && travelX > 0f) 0f else dy * scale
        val pan = if (travelY >= travelX * AXIS_LOCK_RATIO && travelY > 0f) 0f else -dx * scale
        return wheel to pan
    }

    private fun addUnits(dx: Float, dy: Float) {
        val (wheel, pan) = units(dx, dy)
        accWheel += wheel
        accPan += pan
    }

    /** Scroll units per millisecond over the last moments of movement; zero if the fingers had stopped. */
    private fun releaseVelocity(time: Long): Pair<Float, Float> {
        val last = samples.lastOrNull() ?: return 0f to 0f
        val first = samples.firstOrNull { last.time - it.time <= VELOCITY_WINDOW_MILLIS } ?: return 0f to 0f
        val span = (last.time - first.time).toFloat()
        if (span < MIN_VELOCITY_SPAN_MILLIS || time - last.time > STOPPED_BEFORE_LIFT_MILLIS) return 0f to 0f
        return units((last.x - first.x) / span, (last.y - first.y) / span)
    }

    private fun glideTimeConstant(): Float = MIN_GLIDE_MILLIS + settings.glide * (MAX_GLIDE_MILLIS - MIN_GLIDE_MILLIS)

    companion object {
        const val UNITS_PER_PX = 0.06f
        const val AXIS_LOCK_RATIO = 2f
        const val MAX_SMOOTHING_MILLIS = 60f
        const val MIN_GLIDE_MILLIS = 120f
        const val MAX_GLIDE_MILLIS = 900f
        const val POINTER_RESUME_MILLIS = 120L
        const val MOMENTUM_LIFT_GRACE_MILLIS = 120L
        private const val MAX_TICK_MILLIS = 50L
        private const val SETTLED_PX = 0.5f
        private const val VELOCITY_WINDOW_MILLIS = 80L
        private const val MIN_VELOCITY_SPAN_MILLIS = 10L
        private const val STOPPED_BEFORE_LIFT_MILLIS = 50L
        private const val MIN_GLIDE_START_UNITS_PER_MS = 0.03f
        private const val MIN_GLIDE_UNITS_PER_MS = 0.002f
    }
}
