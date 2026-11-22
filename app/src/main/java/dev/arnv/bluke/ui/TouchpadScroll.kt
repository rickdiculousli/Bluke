package dev.arnv.bluke.ui

import android.content.SharedPreferences
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToInt

const val TOUCHPAD_SCROLL_SPEED_PREFERENCE = "touchpad_scroll_sensitivity"
const val TOUCHPAD_INVERT_SCROLL_PREFERENCE = "touchpad_invert_scroll"
const val TOUCHPAD_SCROLL_MOMENTUM_PREFERENCE = "touchpad_scroll_momentum"
const val TOUCHPAD_SCROLL_GLIDE_PREFERENCE = "touchpad_scroll_glide"

/** How two-finger scrolling feels. [glide] runs from 0 (shortest) to 1. */
data class TouchpadScrollSettings(
    val speed: Float = 1f,
    val inverted: Boolean = false,
    val momentum: Boolean = true,
    val glide: Float = 0.5f,
) {
    companion object {
        fun from(prefs: SharedPreferences): TouchpadScrollSettings {
            val defaults = TouchpadScrollSettings()
            return TouchpadScrollSettings(
                speed = prefs.getFloat(TOUCHPAD_SCROLL_SPEED_PREFERENCE, defaults.speed),
                inverted = prefs.getBoolean(TOUCHPAD_INVERT_SCROLL_PREFERENCE, defaults.inverted),
                momentum = prefs.getBoolean(TOUCHPAD_SCROLL_MOMENTUM_PREFERENCE, defaults.momentum),
                glide = prefs.getFloat(TOUCHPAD_SCROLL_GLIDE_PREFERENCE, defaults.glide).coerceIn(0f, 1f),
            )
        }
    }
}

/** One mouse report's worth of scrolling: vertical wheel and horizontal pan steps. */
data class ScrollReport(val wheel: Int, val pan: Int)

/**
 * Turns two-finger movement into wheel and pan steps. The view feeds it finger positions in dp
 * (physical distance, so every screen density scrolls alike); [move] and [end] answer with the
 * report to send at once, and after the fingers lift [tick] plays any glide on a steady frame
 * clock while [isActive].
 *
 * - Direction: content follows the fingers (fingers down scroll up: wheel +; fingers right
 *   scroll left: pan -); [TouchpadScrollSettings.inverted] flips both.
 * - Axis lock: a gesture twice as long on one axis as the other scrolls only that axis.
 * - Momentum: lifting every finger while moving keeps scrolling and slows to a stop; a new touch stops it.
 *   It follows a Mac trackpad's glide as measured on macOS: speed fades with a ~200 ms time constant and
 *   the glide is over within a second. It ends before its steps thin out, because a Mac boosts every
 *   wheel step (a lone one still scrolls tens of pixels), so a thin tail would crawl on in visible jumps.
 * - Handover: after a scroll ends, a remaining finger moves the pointer only once [allowsPointing].
 */
class TouchpadScroller(var settings: TouchpadScrollSettings = TouchpadScrollSettings()) {
    private var scrolling = false
    private var gliding = false
    private var originX = 0f
    private var originY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastReportAt = Long.MIN_VALUE / 2
    private var lastTick = 0L
    private var accWheel = 0f
    private var accPan = 0f
    private var glideWheel = 0f // scroll units per millisecond
    private var glidePan = 0f
    private var glideStartedAt = 0L
    private var endedAt = Long.MIN_VALUE / 2
    private var releaseWheel = 0f
    private var releasePan = 0f
    private var releaseSpeedDp = 0f // finger speed at release, dp per millisecond
    private val samples = ArrayDeque<Sample>()

    private class Sample(val time: Long, val x: Float, val y: Float)

    /** A glide is playing; call [tick] on the frame clock until this turns false. */
    val isActive: Boolean get() = gliding

    /** Two fingers landed with their midpoint at ([x], [y]). Stops any glide. */
    fun begin(x: Float, y: Float, time: Long) {
        stop()
        scrolling = true
        originX = x; originY = y
        lastX = x; lastY = y
        samples.addLast(Sample(time, x, y))
    }

    /** The fingers moved; the report to send now, if this move completed a step. */
    fun move(x: Float, y: Float, time: Long): ScrollReport? {
        if (!scrolling) return null
        val dx = x - lastX
        val dy = y - lastY
        lastX = x; lastY = y
        addUnits(dx, dy)
        samples.addLast(Sample(time, x, y))
        while (samples.size > 2 && time - samples.first().time > VELOCITY_WINDOW_MILLIS) samples.removeFirst()
        // One report per frame at most: finer touch rates would only flood the link.
        if (time - lastReportAt < MIN_REPORT_INTERVAL_MILLIS) return null
        return takeReport()?.also { lastReportAt = time }
    }

    /** The fingers stopped scrolling (one lifted or both did); the report with any steps still owed. */
    fun end(time: Long): ScrollReport? {
        if (!scrolling) return null
        scrolling = false
        endedAt = time
        val (dxPerMs, dyPerMs) = releaseVelocity(time)
        releaseSpeedDp = hypot(dxPerMs, dyPerMs)
        val (wheel, pan) = units(dxPerMs, dyPerMs)
        releaseWheel = wheel
        releasePan = pan
        return takeReport()
    }

    /** Every finger is up. Starts a glide when the scroll ended moments ago while still moving. */
    fun liftAll(time: Long): ScrollReport? {
        val owed = if (scrolling) end(time) else null
        if (!settings.momentum || time - endedAt > MOMENTUM_LIFT_GRACE_MILLIS) return owed
        if (releaseSpeedDp < MIN_GLIDE_START_DP_PER_MS) return owed
        glideWheel = releaseWheel
        glidePan = releasePan
        releaseWheel = 0f
        releasePan = 0f
        releaseSpeedDp = 0f
        glideStartedAt = time
        lastTick = time
        gliding = true
        return owed
    }

    /** A remaining finger may move the pointer: no scroll is running and the last one ended a moment ago. */
    fun allowsPointing(time: Long): Boolean = !scrolling && time - endedAt >= POINTER_RESUME_MILLIS

    /** Stops scrolling at once, dropping anything not yet sent. */
    fun stop() {
        scrolling = false
        gliding = false
        accWheel = 0f
        accPan = 0f
        releaseWheel = 0f
        releasePan = 0f
        releaseSpeedDp = 0f
        samples.clear()
    }

    /** One glide frame: the report to send, if the glide completed a step. */
    fun tick(time: Long): ScrollReport? {
        if (!gliding) return null
        val dt = (time - lastTick).coerceIn(0L, MAX_TICK_MILLIS).toFloat()
        lastTick = time
        accWheel += glideWheel * dt
        accPan += glidePan * dt
        val decay = exp(-dt / glideTimeConstant())
        glideWheel *= decay
        glidePan *= decay
        if (hypot(glideWheel, glidePan) < MIN_GLIDE_UNITS_PER_MS || time - glideStartedAt >= MAX_GLIDE_DURATION_MILLIS) {
            gliding = false
        }
        val report = takeReport()
        if (!gliding) {
            accWheel = 0f
            accPan = 0f
        }
        return report
    }

    private fun takeReport(): ScrollReport? {
        val wheel = accWheel.roundToInt().coerceIn(-127, 127)
        val pan = accPan.roundToInt().coerceIn(-127, 127)
        accWheel -= wheel
        accPan -= pan
        return if (wheel != 0 || pan != 0) ScrollReport(wheel, pan) else null
    }

    /** Finger movement in dp to scroll units, with direction and axis lock applied. */
    private fun units(dx: Float, dy: Float): Pair<Float, Float> {
        val travelX = abs(lastX - originX)
        val travelY = abs(lastY - originY)
        val scale = UNITS_PER_DP * settings.speed * if (settings.inverted) -1f else 1f
        val wheel = if (travelX >= travelY * AXIS_LOCK_RATIO && travelX > 0f) 0f else dy * scale
        val pan = if (travelY >= travelX * AXIS_LOCK_RATIO && travelY > 0f) 0f else -dx * scale
        return wheel to pan
    }

    private fun addUnits(dx: Float, dy: Float) {
        val (wheel, pan) = units(dx, dy)
        accWheel += wheel
        accPan += pan
    }

    /** Finger velocity in dp per millisecond over the last moments of movement; zero if the fingers had stopped. */
    private fun releaseVelocity(time: Long): Pair<Float, Float> {
        val last = samples.lastOrNull() ?: return 0f to 0f
        val first = samples.firstOrNull { last.time - it.time <= VELOCITY_WINDOW_MILLIS } ?: return 0f to 0f
        val span = (last.time - first.time).toFloat()
        if (span < MIN_VELOCITY_SPAN_MILLIS || time - last.time > STOPPED_BEFORE_LIFT_MILLIS) return 0f to 0f
        return (last.x - first.x) / span to (last.y - first.y) / span
    }

    private fun glideTimeConstant(): Float = MIN_GLIDE_MILLIS + settings.glide * (MAX_GLIDE_MILLIS - MIN_GLIDE_MILLIS)

    companion object {
        /** One wheel step per ~6 mm of finger travel: with macOS's wheel boost that is ~3-7 px of
         *  page per mm when slow and ~14 when fast, close to a Mac trackpad. */
        const val UNITS_PER_MM = 0.17f
        const val UNITS_PER_DP = UNITS_PER_MM * 25.4f / 160f
        const val AXIS_LOCK_RATIO = 2f
        const val MIN_GLIDE_MILLIS = 80f
        const val MAX_GLIDE_MILLIS = 300f
        const val MAX_GLIDE_DURATION_MILLIS = 1000L
        const val POINTER_RESUME_MILLIS = 120L
        const val MOMENTUM_LIFT_GRACE_MILLIS = 120L
        const val MIN_REPORT_INTERVAL_MILLIS = 8L
        private const val MAX_TICK_MILLIS = 50L
        private const val VELOCITY_WINDOW_MILLIS = 80L
        private const val MIN_VELOCITY_SPAN_MILLIS = 10L
        private const val STOPPED_BEFORE_LIFT_MILLIS = 50L
        private const val MIN_GLIDE_START_DP_PER_MS = 0.3f // ~5 cm/s: slower releases are drags, not flicks
        private const val MIN_GLIDE_UNITS_PER_MS = 0.04f // one step per 25 ms
    }
}
