package dev.arnv.bluke.ui

/** The keyboard's three lock lights. Num Lock starts lit, as on a PC keyboard. */
data class LockLeds(
    val capsLock: Boolean = false,
    val numLock: Boolean = true,
    val scrollLock: Boolean = false,
)

const val CAPS_LOCK_KEY = 0x39
const val SCROLL_LOCK_KEY = 0x47
const val NUM_LOCK_KEY = 0x53

/**
 * Whether the host's lock-light reports drive the lights: only once the host has sent one on the
 * current connection. Before that, and while disconnected, the phone toggles them itself, so a host
 * that never reports lock lights still gets working lights.
 */
fun lockLightsFollowHost(connected: Boolean, hostReportedThisConnection: Boolean): Boolean =
    connected && hostReportedThisConnection

/** The lights after the phone presses [keyCode]; unchanged when the host drives them or it isn't a lock key. */
fun LockLeds.afterLockKeyPress(keyCode: Int, followHost: Boolean): LockLeds = when {
    followHost -> this
    keyCode == CAPS_LOCK_KEY -> copy(capsLock = !capsLock)
    keyCode == NUM_LOCK_KEY -> copy(numLock = !numLock)
    keyCode == SCROLL_LOCK_KEY -> copy(scrollLock = !scrollLock)
    else -> this
}

/** The lights given the host's latest report: the host's when it drives them, else the phone's own. */
fun LockLeds.withHostReport(host: LockLeds, followHost: Boolean): LockLeds =
    if (followHost) host else this

private const val LEGACY_LOCK_SYNC_MODE_PREFERENCE = "lock_sync_mode"

/**
 * Drops the old "Caps Lock: host or local" choice. Its local mode let the lights disagree with the
 * host; the lights now follow [lockLightsFollowHost] for everyone, so the stored choice means nothing.
 */
fun migrateLockLightPreferences(preferences: android.content.SharedPreferences) {
    if (preferences.contains(LEGACY_LOCK_SYNC_MODE_PREFERENCE)) {
        preferences.edit().remove(LEGACY_LOCK_SYNC_MODE_PREFERENCE).apply()
    }
}
