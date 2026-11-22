package dev.arnv.bluke.bluetooth

/**
 * Keeps the Bluetooth link responsive while someone is using Bluke.
 *
 * After ~100 ms with nothing to send, the link drops into sniff subrating and the host polls it only
 * every few hundred ms, so the next report lands 100-450 ms late (measured against a Mac). Sending
 * something at least every [INTERVAL_MILLIS] holds the delay at ~20-30 ms; a 100 ms interval does not.
 * The keepalive is the keyboard's unchanged state, which no host acts on.
 */
object LinkKeepAlive {
    const val INTERVAL_MILLIS = 40L
    /** Checked this often, so the longest silence is about [INTERVAL_MILLIS] + [CHECK_MILLIS]. */
    const val CHECK_MILLIS = 10L
    /** How long after the last input the link is held awake. */
    const val WINDOW_MILLIS = 60_000L

    fun active(now: Long, lastInputAt: Long): Boolean = now - lastInputAt <= WINDOW_MILLIS

    fun due(now: Long, lastInputAt: Long, lastSentAt: Long): Boolean =
        active(now, lastInputAt) && now - lastSentAt >= INTERVAL_MILLIS
}
