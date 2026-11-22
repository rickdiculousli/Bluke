package dev.arnv.bluke.bluetooth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkKeepAliveTest {
    private val input = 100_000L

    @Test fun aQuietLinkInUseGetsAKeepaliveWithinFiftyMilliseconds() {
        assertTrue(LinkKeepAlive.due(now = input + 40, lastInputAt = input, lastSentAt = input))
        assertTrue(LinkKeepAlive.INTERVAL_MILLIS + LinkKeepAlive.CHECK_MILLIS <= 50)
    }

    @Test fun recentTrafficNeedsNoKeepalive() {
        assertFalse(LinkKeepAlive.due(now = input + 39, lastInputAt = input, lastSentAt = input))
        assertFalse(LinkKeepAlive.due(now = input + 500, lastInputAt = input, lastSentAt = input + 480))
    }

    @Test fun aMinuteWithoutInputLetsTheLinkSleep() {
        val later = input + LinkKeepAlive.WINDOW_MILLIS + 1
        assertFalse(LinkKeepAlive.active(later, input))
        assertFalse(LinkKeepAlive.due(later, lastInputAt = input, lastSentAt = input))
    }
}
