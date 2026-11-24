package dev.arnv.bluke.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockLedsTest {
    /** One connection, replayed: what the keyboard surface does with each event. */
    private class Session(var leds: LockLeds = LockLeds(), var connected: Boolean = true, var hostReported: Boolean = false) {
        fun pressOnPhone(key: Int) {
            leds = leds.afterLockKeyPress(key, lockLightsFollowHost(connected, hostReported))
        }
        fun hostReports(host: LockLeds) {
            hostReported = true
            leds = leds.withHostReport(host, lockLightsFollowHost(connected, hostReported))
        }
    }

    @Test
    fun beforeTheHostsFirstReportThePhoneTogglesItsOwnLights() {
        val session = Session()
        session.pressOnPhone(CAPS_LOCK_KEY)
        assertTrue(session.leds.capsLock)
        session.pressOnPhone(NUM_LOCK_KEY)
        assertFalse(session.leds.numLock)
        session.pressOnPhone(SCROLL_LOCK_KEY)
        assertTrue(session.leds.scrollLock)
    }

    @Test
    fun afterTheHostsFirstReportTheLightsFollowTheHost() {
        val session = Session()
        session.pressOnPhone(CAPS_LOCK_KEY) // phone guesses on
        session.hostReports(LockLeds(capsLock = false, numLock = true, scrollLock = false))
        assertEquals(LockLeds(capsLock = false), session.leds)

        session.pressOnPhone(CAPS_LOCK_KEY) // the host answers with its own report
        assertFalse(session.leds.capsLock)
        session.hostReports(LockLeds(capsLock = true))
        assertTrue(session.leds.capsLock)
    }

    @Test
    fun aCapsLockPressedOnTheHostsOwnKeyboardShowsOnThePhone() {
        val session = Session()
        session.hostReports(LockLeds(capsLock = true))
        assertTrue(session.leds.capsLock)
    }

    @Test
    fun aNewConnectionStartsLocalAgainUntilItsHostReports() {
        assertFalse(lockLightsFollowHost(connected = true, hostReportedThisConnection = false))
        assertTrue(lockLightsFollowHost(connected = true, hostReportedThisConnection = true))
        assertFalse(lockLightsFollowHost(connected = false, hostReportedThisConnection = true))
    }

    @Test
    fun otherKeysLeaveTheLightsAlone() {
        assertEquals(LockLeds(), LockLeds().afterLockKeyPress(0x04, followHost = false))
    }
}
