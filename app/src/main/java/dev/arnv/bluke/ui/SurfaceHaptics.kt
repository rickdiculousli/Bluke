package dev.arnv.bluke.ui

import android.content.SharedPreferences
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView

/** The one haptics switch: every surface and the home screen read it. */
const val HAPTICS_PREFERENCE = "haptics_enabled"

// The per-surface switches it replaced; only the migration reads them.
private const val LEGACY_TOUCHPAD_VIBRATION_PREFERENCE = "touchpad_vibration_enabled"
private const val LEGACY_GAMEPAD_VIBRATION_PREFERENCE = "gamepad_vibration_enabled"

/**
 * Folds the old touchpad and gamepad vibration switches into [HAPTICS_PREFERENCE]: haptics stay on only
 * if every switch the user had was on, so an explicit off anywhere wins. Safe to run twice.
 */
fun migrateHapticsPreference(prefs: SharedPreferences) {
    val legacy = listOf(LEGACY_TOUCHPAD_VIBRATION_PREFERENCE, LEGACY_GAMEPAD_VIBRATION_PREFERENCE)
    if (legacy.none(prefs::contains)) return
    val enabled = prefs.getBoolean(HAPTICS_PREFERENCE, true) && legacy.all { prefs.getBoolean(it, true) }
    prefs.edit().apply {
        putBoolean(HAPTICS_PREFERENCE, enabled)
        legacy.forEach(::remove)
    }.apply()
}

/** What a haptic marks. Each fires on its causal event only, in the frame the visual changes. */
enum class HapticEvent {
    /** A key, button or pad touched down. */
    PRESS,
    /** A touchpad click or tap-to-click landing. */
    CLICK,
    /** A stepped value moved one step (a slider detent, a cycle). */
    TICK,
    /** The surface or a mode changed. */
    MODE_SWITCH,
    /** An action was refused (nothing to reconnect to, an edit limit). */
    REJECT,
}

/** Plays a [HapticEvent]. The app uses [ViewHapticPerformer]; tests provide a recorder through [LocalHapticPerformer]. */
fun interface HapticPerformer {
    fun perform(event: HapticEvent)
}

/** Plays haptics through the view, so they follow the system's touch-feedback strength. */
class ViewHapticPerformer(private val view: View) : HapticPerformer {
    override fun perform(event: HapticEvent) {
        val constant = when (event) {
            HapticEvent.PRESS -> HapticFeedbackConstants.KEYBOARD_TAP
            HapticEvent.CLICK -> HapticFeedbackConstants.VIRTUAL_KEY
            HapticEvent.TICK -> HapticFeedbackConstants.CLOCK_TICK
            HapticEvent.MODE_SWITCH ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
                else HapticFeedbackConstants.LONG_PRESS
            HapticEvent.REJECT ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT
                else HapticFeedbackConstants.LONG_PRESS
        }
        @Suppress("DEPRECATION")
        view.performHapticFeedback(constant, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
    }
}

/** Replaces the view-based performer; tests set it to count what a surface plays. */
val LocalHapticPerformer = staticCompositionLocalOf<HapticPerformer?> { null }

/** The one haptics helper. It plays nothing while [HAPTICS_PREFERENCE] is off. */
@Stable
class SurfaceHaptics internal constructor(
    private val enabled: () -> Boolean,
    private val performer: HapticPerformer,
) {
    fun perform(event: HapticEvent) {
        if (enabled()) performer.perform(event)
    }
}

/** The haptics helper for a surface, following [HAPTICS_PREFERENCE] as it changes. */
@Composable
fun rememberSurfaceHaptics(prefs: SharedPreferences): SurfaceHaptics {
    val enabled by rememberPreference(prefs, HAPTICS_PREFERENCE) { it.getBoolean(HAPTICS_PREFERENCE, true) }
    val view = LocalView.current
    val performer = LocalHapticPerformer.current ?: remember(view) { ViewHapticPerformer(view) }
    return remember(performer) { SurfaceHaptics({ enabled }, performer) }
}
