package dev.arnv.bluke.ui

import dev.arnv.bluke.bluetooth.BluetoothState
import dev.arnv.bluke.bluetooth.ConnectionFailure
import dev.arnv.bluke.bluetooth.HidFailure
import dev.arnv.bluke.bluetooth.HidLifecycleState
import dev.arnv.bluke.bluetooth.LastConnectionFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionUiPolicyTest {
    private val mac = HostRef("A4:83:E7:11:22:33", "Rick's MacBook Pro", bonded = true, type = DeviceType.COMPUTER)
    private val ipad = HostRef("8C:85:90:AA:BB:CC", "iPad Air", bonded = false, type = DeviceType.PHONE)
    private val known = listOf(mac, ipad).associateBy { it.address }

    private fun ui(
        bt: BluetoothState = BluetoothState.PairingMode("Bluke"),
        lifecycle: HidLifecycleState = HidLifecycleState.Registered,
        connected: String? = null,
        target: String? = null,
        scanning: Boolean = false,
        disconnecting: Boolean = false,
        failure: LastConnectionFailure? = null,
        reconnect: String? = null,
        startedAt: Long? = null,
        now: Long = 0L,
    ) = connectionUi(
        ConnectionInputs(bt, lifecycle, connected, target, scanning, disconnecting, failure, reconnect, known, startedAt, now),
    )

    // ---- the four behaviours the spec names, each failing on the code it replaces ----

    @Test
    fun connectedSaysConnectedToTheHostNotReadyToScan() {
        val state = ui(bt = BluetoothState.Connected(mac.name!!), lifecycle = HidLifecycleState.Connected(mac.address), connected = mac.address)
        assertEquals(ConnectionUi.Connected(mac), state)
        assertEquals("Connected to Rick's MacBook Pro", state.card().headline)
        assertEquals(ConnectionAction.Disconnect, state.card().primary)
    }

    @Test
    fun permissionMissingBlocksLaunchAndOffersGrant() {
        // C10: Launch used to stay enabled with no way to grant.
        val state = ui(bt = BluetoothState.PermissionRequired, lifecycle = HidLifecycleState.Idle)
        assertEquals(ConnectionUi.Blocked(BlockReason.PERMISSION_MISSING), state)
        assertFalse(state.allowsLaunch)
        assertEquals(ConnectionAction.GrantPermission, state.card().primary)
    }

    @Test
    fun aSurfaceGoesHomeWhenBluetoothTurnsOffButStaysOpenWhileChecking() {
        // C1: the surface used to stay open with Bluetooth off.
        assertTrue(ui(bt = BluetoothState.BluetoothOff, lifecycle = HidLifecycleState.Idle).closesSurface)
        assertTrue(ui(bt = BluetoothState.ProfileNotSupported).closesSurface)
        assertFalse(ui(bt = BluetoothState.CheckingCapabilities, lifecycle = HidLifecycleState.Idle).closesSurface)
        assertFalse(ui(bt = BluetoothState.CheckingCapabilities, lifecycle = HidLifecycleState.Registering(2)).closesSurface)
        assertFalse(ui(bt = BluetoothState.InitializingCapabilities, lifecycle = HidLifecycleState.Idle).closesSurface)
    }

    // ---- the attempt, its failures and its stall ----

    @Test
    fun anUnpairedTargetIsPairingAndAPairedOneIsConnecting() {
        assertEquals(ConnectionUi.Pairing(ipad), ui(target = ipad.address))
        assertEquals(ConnectionUi.Connecting(mac), ui(target = mac.address, lifecycle = HidLifecycleState.Connecting(mac.address)))
        assertEquals(ConnectionAction.Cancel, ui(target = ipad.address).card().primary)
    }

    @Test
    fun pairingThatTurnsIntoConnectingRestartsTheStallClock() {
        val pairing = attemptKey(null, ipad.address, HidLifecycleState.Registered, bondedAddresses = emptySet())
        val connecting = attemptKey(null, ipad.address, HidLifecycleState.Connecting(ipad.address), bondedAddresses = setOf(ipad.address))
        assertTrue(pairing != null && connecting != null && pairing != connecting)
        assertNull(attemptKey(mac.address, null, HidLifecycleState.Connected(mac.address), setOf(mac.address)))
    }

    @Test
    fun aNewScanReplacesAStaleFailure() {
        val refused = LastConnectionFailure(ConnectionFailure.HOST_REFUSED_PAIRING, ipad.address)
        assertEquals(ConnectionUi.Ready(null, scanning = true), ui(failure = refused, scanning = true))
    }

    @Test
    fun pairingWaitsOnAPersonSoTheCardHoldsStill() {
        // A spinner under Android's pairing prompt never lets the screen go idle, and the prompt is the next step.
        assertFalse(ui(target = ipad.address).card().busy)
        assertFalse(ui(target = ipad.address, startedAt = 0, now = CONNECTION_STALL_MILLIS).card().busy)
        assertTrue(ui(target = mac.address).card().busy)
    }

    @Test
    fun anIncomingConnectionWithoutATargetStillReadsConnecting() {
        assertEquals(ConnectionUi.Connecting(mac), ui(lifecycle = HidLifecycleState.Connecting(mac.address)))
    }

    @Test
    fun anAttemptStallsAtTwelveSecondsAndCarriesHelp() {
        assertEquals(ConnectionUi.Connecting(mac), ui(target = mac.address, startedAt = 1_000, now = 12_999))
        val stalled = ui(target = mac.address, startedAt = 1_000, now = 13_000)
        assertEquals(ConnectionUi.Failed(FailReason.STALLED, mac), stalled)
        assertEquals(ConnectionAction.Help, stalled.card().secondary)
        val stalledPairing = ui(target = ipad.address, startedAt = 0, now = CONNECTION_STALL_MILLIS)
        assertEquals(ConnectionUi.Failed(FailReason.STALLED, ipad, whilePairing = true), stalledPairing)
    }

    @Test
    fun aRefusedPairingSaysSoAndOffersRetry() {
        val state = ui(failure = LastConnectionFailure(ConnectionFailure.HOST_REFUSED_PAIRING, ipad.address))
        assertEquals(ConnectionUi.Failed(FailReason.HOST_REFUSED, ipad, ConnectionFailure.HOST_REFUSED_PAIRING), state)
        assertEquals("Host refused pairing", state.card().headline)
        assertEquals(ConnectionAction.Retry(ipad), state.card().primary)
        assertEquals(ConnectionAction.Help, state.card().secondary)
    }

    @Test
    fun theNextAttemptReplacesAFailure() {
        // The manager clears the failure when it starts the attempt; the target alone also wins.
        val failure = LastConnectionFailure(ConnectionFailure.CONNECTION_REJECTED, mac.address)
        assertEquals(ConnectionUi.Connecting(mac), ui(target = mac.address, failure = failure))
    }

    @Test
    fun aRegistrationFailureOffersRestartHid() {
        for (failure in HidFailure.entries - HidFailure.CONNECTION_REJECTED) {
            val state = ui(bt = BluetoothState.ReadyDisconnected, lifecycle = HidLifecycleState.Error(failure))
            assertEquals(ConnectionUi.Failed(FailReason.SERVICE_FAILED, null), state)
            assertEquals(ConnectionAction.RestartHid, state.card().primary)
            assertFalse(state.offersRestartInDetails)
        }
    }

    @Test
    fun readyOffersTheLastHostOrAScan() {
        assertEquals(ConnectionAction.Reconnect(mac), ui(reconnect = mac.address).card().primary)
        assertEquals("Reconnect Rick's MacBook Pro", ConnectionAction.Reconnect(mac).label)
        assertEquals(ConnectionAction.Scan, ui().card().primary)
        assertEquals(ConnectionAction.StopScan, ui(scanning = true, reconnect = mac.address).card().primary)
        // A reconnect target that is no longer paired isn't offered.
        assertEquals(ConnectionAction.Scan, ui(reconnect = ipad.address).card().primary)
    }

    @Test
    fun disconnectingHasNoAction() {
        val state = ui(connected = mac.address, disconnecting = true)
        assertEquals(ConnectionUi.Disconnecting(mac), state)
        assertNull(state.card().primary)
    }

    @Test
    fun theStartupCheckAndHidStartAreDistinct() {
        assertEquals(ConnectionUi.Checking, ui(bt = BluetoothState.InitializingCapabilities, lifecycle = HidLifecycleState.Idle))
        assertEquals(ConnectionUi.Starting(2), ui(bt = BluetoothState.CheckingCapabilities, lifecycle = HidLifecycleState.Registering(2)))
        assertEquals(ConnectionUi.Starting(1), ui(lifecycle = HidLifecycleState.BindingProxy(1)))
    }

    // ---- what the rows and the surfaces read from the same state ----

    @Test
    fun rowsReadTheConnectionNotText() {
        assertEquals(HostRowState.CONNECTED, ui(connected = mac.address).rowState(mac.address))
        assertEquals(HostRowState.IDLE, ui(connected = mac.address).rowState(ipad.address))
        assertEquals(HostRowState.WORKING, ui(target = ipad.address).rowState(ipad.address))
        val refused = ui(failure = LastConnectionFailure(ConnectionFailure.CONNECTION_REJECTED, mac.address))
        assertEquals(HostRowState.FAILED, refused.rowState(mac.address))
        // The card offers Reconnect for the last host, so its row doesn't repeat it.
        assertEquals(HostRowState.LAST_USED, ui(reconnect = mac.address).rowState(mac.address))
    }

    @Test
    fun aDisconnectingHostsRowSaysSoNotConnecting() {
        val disconnecting = ui(connected = mac.address, disconnecting = true)
        assertEquals(HostRowState.DISCONNECTING, disconnecting.rowState(mac.address))
        assertFalse(disconnecting.rowState(mac.address).acceptsTap)
    }

    @Test
    fun rowsTakeNoTapsWhileATapCouldShowNothing() {
        val busy = listOf(
            ui(bt = BluetoothState.CheckingCapabilities, lifecycle = HidLifecycleState.Idle),
            ui(lifecycle = HidLifecycleState.BindingProxy(1)),
            ui(lifecycle = HidLifecycleState.Registering(2)),
            ui(lifecycle = HidLifecycleState.Error(HidFailure.entries.first { it != HidFailure.CONNECTION_REJECTED })),
            ui(connected = mac.address, disconnecting = true),
            ui(disconnecting = true),
        )
        busy.forEach { state ->
            assertEquals("$state", HostRowState.INERT, state.rowState(ipad.address))
            assertFalse("$state", state.rowState(ipad.address).acceptsTap)
        }
        // Where a tap does show (a new target, a retry), rows still take it.
        assertTrue(ui().rowState(ipad.address).acceptsTap)
        assertTrue(ui(target = mac.address).rowState(ipad.address).acceptsTap)
        val refused = ui(failure = LastConnectionFailure(ConnectionFailure.CONNECTION_REJECTED, mac.address))
        assertTrue(refused.rowState(mac.address).acceptsTap)
    }

    // ---- the generated table: every input shape yields one state, one headline, at most one primary ----

    @Test
    fun everyInputYieldsOneHeadlineAndAtMostOnePrimaryAction() {
        val bluetoothStates = listOf(
            BluetoothState.InitializingCapabilities, BluetoothState.CheckingCapabilities, BluetoothState.Unsupported,
            BluetoothState.PermissionRequired, BluetoothState.BluetoothOff, BluetoothState.ProfileNotSupported,
            BluetoothState.ReadyDisconnected, BluetoothState.PairingMode("Bluke"), BluetoothState.Connected("Rick's MacBook Pro"),
        )
        val lifecycles = listOf(
            HidLifecycleState.Idle, HidLifecycleState.BindingProxy(1), HidLifecycleState.Registering(3),
            HidLifecycleState.Registered, HidLifecycleState.Connecting(mac.address), HidLifecycleState.Connected(mac.address),
        ) + HidFailure.entries.map { HidLifecycleState.Error(it) }
        data class Link(val name: String, val target: String?, val connected: String?)
        val links = listOf(
            Link("no target", null, null),
            Link("unbonded target", ipad.address, null),
            Link("bonded target", mac.address, null),
            Link("connected", null, mac.address),
        )
        val failures = listOf(null) + ConnectionFailure.entries.map { LastConnectionFailure(it, mac.address) }
        val blockedStates = setOf(
            BluetoothState.Unsupported, BluetoothState.PermissionRequired, BluetoothState.BluetoothOff, BluetoothState.ProfileNotSupported,
        )
        var cases = 0
        for (bt in bluetoothStates) for (lifecycle in lifecycles) for (link in links) for (scanning in listOf(false, true))
            for (disconnecting in listOf(false, true)) for (failure in failures) for (elapsed in listOf(null, 20_000L)) {
                val state = ui(
                    bt = bt, lifecycle = lifecycle, connected = link.connected, target = link.target, scanning = scanning,
                    disconnecting = disconnecting, failure = failure, reconnect = mac.address,
                    startedAt = elapsed?.let { 0L }, now = elapsed ?: 0L,
                )
                val card = state.card()
                val case = "$bt / $lifecycle / ${link.name} / scanning=$scanning / disconnecting=$disconnecting / $failure / $elapsed -> $state"
                assertTrue(case, card.headline.isNotBlank())
                assertFalse(case, card.headline.contains("Ready to Scan"))
                assertTrue(case, card.secondary == null || card.secondary == ConnectionAction.Help)
                assertTrue(case, card.primary != card.secondary || card.primary == null)
                assertEquals(case, bt in blockedStates, state is ConnectionUi.Blocked)
                assertEquals(case, state !is ConnectionUi.Blocked, state.allowsLaunch)
                if (bt !in blockedStates && link.connected != null) {
                    assertEquals(case, if (disconnecting) ConnectionUi.Disconnecting(mac) else ConnectionUi.Connected(mac), state)
                }
                if (state is ConnectionUi.Failed && state.reason == FailReason.STALLED) assertEquals(case, 20_000L, elapsed)
                cases++
            }
        assertEquals(9 * 11 * 4 * 2 * 2 * 4 * 2, cases)
    }
}
