package dev.arnv.bluke.sound

import android.content.SharedPreferences
import androidx.core.content.edit

private const val SOUND_PREFERENCES_SCHEMA = 1
private const val SOUND_PREFERENCES_SCHEMA_KEY = "sound_preferences_schema"
const val KEY_SOUND_ENABLED_PREFERENCE = "key_sound_enabled"
private const val LEGACY_SOUND_TOGGLE_PREFERENCE = "sound_toggle"

/** Normalizes legacy sound settings once without overriding an explicit mute choice. */
fun migrateSoundPreferences(preferences: SharedPreferences) {
    if (preferences.getInt(SOUND_PREFERENCES_SCHEMA_KEY, 0) >= SOUND_PREFERENCES_SCHEMA) return

    val soundEnabled = when {
        preferences.contains(KEY_SOUND_ENABLED_PREFERENCE) ->
            preferences.getBoolean(KEY_SOUND_ENABLED_PREFERENCE, true)
        preferences.contains(LEGACY_SOUND_TOGGLE_PREFERENCE) ->
            preferences.getBoolean(LEGACY_SOUND_TOGGLE_PREFERENCE, true)
        else -> true
    }
    preferences.edit {
        putBoolean(KEY_SOUND_ENABLED_PREFERENCE, soundEnabled)
        putBoolean(LEGACY_SOUND_TOGGLE_PREFERENCE, soundEnabled)
        putInt(SOUND_PREFERENCES_SCHEMA_KEY, SOUND_PREFERENCES_SCHEMA)
    }
}

private const val LEGACY_SOUND_CYCLE_PREFERENCE = "cycle_key_sounds"
private const val LEGACY_SOUND_CYCLE_SCHEMA_KEY = "sound_cycle_schema"

/** Drops the sound pill's old cycle list: the toolbar no longer cycles key sounds, so nothing reads it. Safe to run twice. */
fun migrateSoundCyclePreferences(preferences: SharedPreferences) {
    if (preferences.contains(LEGACY_SOUND_CYCLE_PREFERENCE) || preferences.contains(LEGACY_SOUND_CYCLE_SCHEMA_KEY)) {
        preferences.edit {
            remove(LEGACY_SOUND_CYCLE_PREFERENCE)
            remove(LEGACY_SOUND_CYCLE_SCHEMA_KEY)
        }
    }
}

/**
 * Makes [profileId] (a [builtInSoundProfileId] or [customSoundProfileId]) the key sound and turns key
 * sounds on: choosing a sound is asking to hear it, wherever it's chosen. Returns false, changing
 * nothing, for an id that names no sound. A live [KeyboardSoundSynthesizer] picks the choice up with
 * `reloadSelectedSoundPack()` and `setMute(false)`.
 */
fun selectKeySound(preferences: SharedPreferences, profileId: String): Boolean {
    val builtIn = SwitchType.entries.firstOrNull { builtInSoundProfileId(it) == profileId }
    val customPrefix = customSoundProfileId("")
    val customPackId = profileId.takeIf { builtIn == null && it.startsWith(customPrefix) }
        ?.removePrefix(customPrefix)
        ?.takeIf { it.isNotEmpty() }
    if (builtIn == null && customPackId == null) return false
    preferences.edit {
        if (builtIn != null) {
            remove(CUSTOM_SOUND_PACK_PREFERENCE)
            putString(SELECTED_BUILT_IN_SOUND_PREFERENCE, builtIn.name)
        } else {
            putString(CUSTOM_SOUND_PACK_PREFERENCE, customPackId)
        }
        putBoolean(KEY_SOUND_ENABLED_PREFERENCE, true)
    }
    return true
}

/** Turns key sounds off, keeping the chosen sound for when they come back on. */
fun turnKeySoundsOff(preferences: SharedPreferences) {
    preferences.edit { putBoolean(KEY_SOUND_ENABLED_PREFERENCE, false) }
}
