package dev.arnv.bluke.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SurfaceHostTest {
    private val mac = HostRef("A4:83:E7:11:22:33", "Rick's MacBook Pro", bonded = true)
    private val ipad = HostRef("8C:85:90:AA:BB:CC", "iPad Air", bonded = false)

    @Test
    fun theChipReadsTheConnectionFromTheSameStateAsTheCard() {
        assertEquals(SurfaceHost(SurfaceHostStatus.Connected, mac.name), ConnectionUi.Connected(mac).surfaceHost())
        assertEquals(SurfaceHost(SurfaceHostStatus.Connecting, mac.name), ConnectionUi.Connecting(mac).surfaceHost())
        assertEquals(SurfaceHost(SurfaceHostStatus.Connecting, ipad.name), ConnectionUi.Pairing(ipad).surfaceHost())
        // Checking and Starting leave the surface open (C1) and say so on the chip.
        assertEquals(SurfaceHost(SurfaceHostStatus.Connecting, null), ConnectionUi.Checking.surfaceHost())
        assertEquals(SurfaceHost(SurfaceHostStatus.Connecting, null), ConnectionUi.Starting(2).surfaceHost())
        val disconnecting = ConnectionUi.Disconnecting(mac).surfaceHost()
        assertEquals(SurfaceHostStatus.Offline, disconnecting.status)
        assertNull(disconnecting.onReconnect)
        for (reason in BlockReason.entries) {
            assertEquals(SurfaceHostStatus.Blocked, ConnectionUi.Blocked(reason).surfaceHost().status)
        }
    }

    @Test
    fun offlineReconnectsToABondedHostUnlessTheAttemptStalled() {
        val reconnected = mutableListOf<HostRef>()
        val ready = ConnectionUi.Ready(reconnectHost = mac, scanning = false).surfaceHost(onReconnect = { reconnected += it })
        assertEquals(SurfaceHostStatus.Offline, ready.status)
        assertEquals(mac.name, ready.name)
        ready.onReconnect!!.invoke()
        assertEquals(listOf(mac), reconnected)
        assertEquals("Reconnect", hostChipContent(ready).action)

        assertNull(ConnectionUi.Ready(reconnectHost = null, scanning = false).surfaceHost().onReconnect)
        assertNotNull(ConnectionUi.Failed(FailReason.HOST_REFUSED, mac).surfaceHost().onReconnect)
        assertNull(ConnectionUi.Failed(FailReason.HOST_REFUSED, ipad).surfaceHost().onReconnect)
        assertNull(ConnectionUi.Failed(FailReason.STALLED, mac).surfaceHost().onReconnect)
    }

    @Test
    fun everyStatusSaysWhatItIs() {
        assertEquals("Rick's MacBook", hostChipContent(SurfaceHost(SurfaceHostStatus.Connected, "Rick's MacBook")).label)
        assertEquals("Connecting to Desk PC", hostChipContent(SurfaceHost(SurfaceHostStatus.Connecting, "Desk PC")).label)
        assertEquals("Connecting · Desk PC", hostChipContent(SurfaceHost(SurfaceHostStatus.Connecting, "Desk PC"), compact = true).label)
        assertEquals("Connecting…", hostChipContent(SurfaceHost(SurfaceHostStatus.Connecting, null)).label)
        // With no room for the name, the status stands alone rather than beside a bare ellipsis.
        val nameless = hostChipContent(SurfaceHost(SurfaceHostStatus.Connecting, "Desk PC"), compact = true, withName = false)
        assertEquals("Connecting…", nameless.label)
        assertEquals("Connecting to Desk PC", nameless.description)
        val offline = hostChipContent(SurfaceHost(SurfaceHostStatus.Offline, "Desk PC", onReconnect = {}))
        assertEquals("Desk PC", offline.label)
        assertEquals("Reconnect", offline.action)
        assertNull(hostChipContent(SurfaceHost(SurfaceHostStatus.Offline, "Desk PC")).action)
        assertEquals("No host", hostChipContent(SurfaceHost(SurfaceHostStatus.Offline, null)).label)
        assertEquals("Bluetooth unavailable", hostChipContent(SurfaceHost(SurfaceHostStatus.Blocked, null)).label)
        assertEquals("Bluetooth off", hostChipContent(SurfaceHost(SurfaceHostStatus.Blocked, null, reason = BlockReason.BLUETOOTH_OFF.chipReason)).label)
    }

    @Test
    fun aBlockedChipNamesTheCauseAndLeavesTheFixToHome() {
        for (reason in BlockReason.entries) {
            val content = hostChipContent(ConnectionUi.Blocked(reason).surfaceHost())
            assertEquals(reason.chipReason, content.label)
            assertNull("$reason offers no tap; the home card has the fix", content.action)
        }
    }

    @Test
    fun aPhoneWithoutTheHidRoleSaysSoOnEverySurface() {
        val content = hostChipContent(ConnectionUi.Blocked(BlockReason.NO_HID_ROLE).surfaceHost())
        assertEquals("Phone not supported", content.label)
        assertNull(content.action)
    }

    @Test
    fun theModeButtonWalksTheUsersCycle() {
        val cycle = listOf(InputMode.KEYBOARD, InputMode.GAMEPAD, InputMode.MEDIA_PRESENTATION)
        assertEquals(InputMode.GAMEPAD, nextInputMode(cycle, InputMode.KEYBOARD))
        assertEquals(InputMode.KEYBOARD, nextInputMode(cycle, InputMode.MEDIA_PRESENTATION))
        assertEquals(InputMode.KEYBOARD, nextInputMode(cycle, InputMode.TOUCHPAD))
        assertEquals(InputMode.KEYBOARD, nextInputMode(listOf(InputMode.KEYBOARD), InputMode.KEYBOARD))
    }
}
