package dev.arnv.bluke.ui

import dev.arnv.bluke.bluetooth.BluetoothState
import dev.arnv.bluke.bluetooth.HidLifecycleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SurfaceHostTest {
    @Test
    fun theChipReadsTheConnection() {
        assertEquals(SurfaceHostStatus.Connected, surfaceHostStatus(BluetoothState.Connected("Mac"), HidLifecycleState.Connected("AA")))
        assertEquals(SurfaceHostStatus.Connecting, surfaceHostStatus(BluetoothState.ReadyDisconnected, HidLifecycleState.Connecting("AA")))
        assertEquals(SurfaceHostStatus.Connecting, surfaceHostStatus(BluetoothState.CheckingCapabilities, HidLifecycleState.Idle))
        assertEquals(SurfaceHostStatus.Connecting, surfaceHostStatus(BluetoothState.ReadyDisconnected, HidLifecycleState.Registering(1), hasTarget = true))
        assertEquals(SurfaceHostStatus.Offline, surfaceHostStatus(BluetoothState.ReadyDisconnected, HidLifecycleState.Registering(1), hasTarget = false))
        assertEquals(SurfaceHostStatus.Offline, surfaceHostStatus(BluetoothState.ReadyDisconnected, HidLifecycleState.Registered))
        for (blocked in listOf(BluetoothState.BluetoothOff, BluetoothState.Unsupported, BluetoothState.ProfileNotSupported, BluetoothState.PermissionRequired)) {
            assertEquals(SurfaceHostStatus.Blocked, surfaceHostStatus(blocked, HidLifecycleState.Idle))
        }
    }

    @Test
    fun everyStatusSaysWhatItIs() {
        assertEquals("Rick's MacBook", hostChipContent(SurfaceHost(SurfaceHostStatus.Connected, "Rick's MacBook")).label)
        assertEquals("Connecting to Desk PC", hostChipContent(SurfaceHost(SurfaceHostStatus.Connecting, "Desk PC")).label)
        assertEquals("Connecting Desk PC", hostChipContent(SurfaceHost(SurfaceHostStatus.Connecting, "Desk PC"), compact = true).label)
        assertEquals("Connecting…", hostChipContent(SurfaceHost(SurfaceHostStatus.Connecting, null)).label)
        val offline = hostChipContent(SurfaceHost(SurfaceHostStatus.Offline, "Desk PC", onReconnect = {}))
        assertEquals("Desk PC", offline.label)
        assertEquals("Reconnect", offline.action)
        assertNull(hostChipContent(SurfaceHost(SurfaceHostStatus.Offline, "Desk PC")).action)
        assertEquals("No host", hostChipContent(SurfaceHost(SurfaceHostStatus.Offline, null)).label)
        assertEquals("Bluetooth unavailable", hostChipContent(SurfaceHost(SurfaceHostStatus.Blocked, null)).label)
        assertEquals("Bluetooth off", hostChipContent(SurfaceHost(SurfaceHostStatus.Blocked, null, reason = blockedReason(BluetoothState.BluetoothOff))).label)
    }

    @Test
    fun aBlockedChipOffersTheFixATapCanMake() {
        assertEquals(BlockedFix.TurnOnBluetooth, blockedFix(BluetoothState.BluetoothOff))
        assertEquals(BlockedFix.OpenAppSettings, blockedFix(BluetoothState.PermissionRequired))
        assertNull(blockedFix(BluetoothState.ProfileNotSupported))
        assertNull(blockedFix(BluetoothState.Unsupported))
        assertNull(blockedFix(BluetoothState.ReadyDisconnected))

        val fix = BlockedFix.TurnOnBluetooth
        val off = hostChipContent(
            SurfaceHost(SurfaceHostStatus.Blocked, null, reason = "Bluetooth off", onFix = {}, fixLabel = fix.label, fixClickLabel = fix.clickLabel),
        )
        assertEquals("Turn on", off.action)
        assertEquals("Turn on Bluetooth", off.clickLabel)
        // A label with nothing to run is no action.
        assertNull(hostChipContent(SurfaceHost(SurfaceHostStatus.Blocked, null, reason = "Bluetooth off", fixLabel = "Turn on")).action)
    }

    @Test
    fun aPhoneWithoutTheHidRoleSaysSoOnEverySurface() {
        val reason = blockedReason(BluetoothState.ProfileNotSupported)
        assertEquals("Phone not supported", reason)
        val content = hostChipContent(SurfaceHost(SurfaceHostStatus.Blocked, null, reason = reason))
        assertEquals(reason, content.label)
        assertNull(content.action)
        assertNull(content.clickLabel)
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
