package dev.arnv.bluke.ui

import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.core.content.edit
import kotlin.math.roundToInt

/** The speeds pointer and scroll sliders stop at, slowest first. */
val POINTER_SPEED_STOPS = listOf(0.25f, 0.5f, 1f, 1.5f, 2f, 2.5f)

/** Pointer and two-finger scroll speed, which the touchpad and the multimedia pad share. */
data class PointerTuneState(
    val pointerSpeed: Float = DEFAULT_TOUCHPAD_POINTER_SPEED,
    val scrollSpeed: Float = TouchpadScrollSettings().speed,
) {
    fun write(prefs: SharedPreferences) = prefs.edit {
        putFloat(TOUCHPAD_POINTER_SPEED_PREFERENCE, pointerSpeed)
        putFloat(TOUCHPAD_SCROLL_SPEED_PREFERENCE, scrollSpeed)
    }

    companion object {
        val KEYS = arrayOf(TOUCHPAD_POINTER_SPEED_PREFERENCE, TOUCHPAD_SCROLL_SPEED_PREFERENCE)

        fun read(prefs: SharedPreferences) = PointerTuneState(
            pointerSpeed = prefs.getFloat(TOUCHPAD_POINTER_SPEED_PREFERENCE, DEFAULT_TOUCHPAD_POINTER_SPEED),
            scrollSpeed = prefs.getFloat(TOUCHPAD_SCROLL_SPEED_PREFERENCE, TouchpadScrollSettings().speed),
        )
    }
}

/** The two speed sliders, each stepping through [POINTER_SPEED_STOPS] with a tick per stop. */
@Composable
fun PointerSpeedTune(state: PointerTuneState, onChange: (PointerTuneState) -> Unit, haptics: SurfaceHaptics? = null) {
    SpeedSlider("Pointer speed", state.pointerSpeed, haptics) { onChange(state.copy(pointerSpeed = it)) }
    SpeedSlider("Scroll speed", state.scrollSpeed, haptics) { onChange(state.copy(scrollSpeed = it)) }
}

@Composable
private fun SpeedSlider(label: String, speed: Float, haptics: SurfaceHaptics?, onSpeed: (Float) -> Unit) {
    val index = POINTER_SPEED_STOPS.indexOf(snapTo(speed, POINTER_SPEED_STOPS))
    TuneSlider(
        label = label,
        value = index.toFloat(),
        valueRange = 0f..(POINTER_SPEED_STOPS.size - 1).toFloat(),
        steps = POINTER_SPEED_STOPS.size - 2,
        valueLabel = speedLabel(POINTER_SPEED_STOPS[index]),
        onValueChange = { position ->
            val next = POINTER_SPEED_STOPS[position.roundToInt().coerceIn(POINTER_SPEED_STOPS.indices)]
            if (next != POINTER_SPEED_STOPS[index]) {
                haptics?.perform(HapticEvent.TICK)
                onSpeed(next)
            }
        },
    )
}
