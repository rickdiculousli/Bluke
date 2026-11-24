package dev.arnv.bluke.ui

import dev.arnv.bluke.bluetooth.ConnectionFailure
import dev.arnv.bluke.testing.captureMatrix
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Home in every connection state, light and dark, at font scale 1.0 and 1.3, rendered straight from
 * [HomeContent] with fixture states. Baselines: `src/test/screenshots/home-<state>-*.png`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h914dp-port-xhdpi", sdk = [36])
class HomeScreenshotTest {
    private val mac = HostRef("A4:83:E7:11:22:33", "Rick's MacBook Pro", bonded = true, type = DeviceType.COMPUTER)
    private val pc = HostRef("3C:7C:3F:44:55:66", "DESKTOP-K16", bonded = true, type = DeviceType.COMPUTER)
    private val tv = HostRef("F0:B4:29:77:88:99", "Living Room Chromecast", bonded = true, type = DeviceType.TV)
    private val buds = HostRef("70:F0:87:12:34:56", "Galaxy Buds2 Pro", bonded = true, type = DeviceType.AUDIO)
    private val ipad = HostRef("8C:85:90:AA:BB:CC", "iPad Air", bonded = false, type = DeviceType.PHONE)
    private val tablet = HostRef("12:34:56:78:9A:BC", "Pixel Tablet", bonded = false, type = DeviceType.PHONE)
    private val longName = HostRef(
        "A4:83:E7:11:22:44", "Rick's MacBook Pro (Work) — 16-inch, Space Black, 2023", bonded = true, type = DeviceType.COMPUTER,
    )
    private val paired = listOf(mac, pc, tv, buds)

    private fun shoot(name: String, state: HomeUiState) =
        captureMatrix(name = "home-$name") { HomeContent(state, HomeCallbacks()) }

    @Test fun checking() = shoot("checking", HomeUiState(ConnectionUi.Checking, "Checking Bluetooth HID Device compatibility..."))

    @Test fun starting() = shoot("starting", HomeUiState(ConnectionUi.Starting(2), "Registering HID app (attempt 2)...", paired = paired))

    @Test fun permissionMissing() = shoot(
        "blocked-permission",
        HomeUiState(ConnectionUi.Blocked(BlockReason.PERMISSION_MISSING), "Bluetooth Connect, Advertise & Scan permissions are required."),
    )

    @Test fun bluetoothOff() = shoot(
        "blocked-bluetooth-off",
        HomeUiState(ConnectionUi.Blocked(BlockReason.BLUETOOTH_OFF), "Bluetooth is currently turned off.", paired = paired),
    )

    @Test fun noHidRole() = shoot(
        "blocked-no-hid-role",
        HomeUiState(ConnectionUi.Blocked(BlockReason.NO_HID_ROLE), "This device appears incompatible with the Bluetooth HID Device role."),
    )

    @Test fun noAdapter() = shoot(
        "blocked-no-adapter",
        HomeUiState(ConnectionUi.Blocked(BlockReason.NO_ADAPTER), "Bluetooth is not supported on this device's hardware."),
    )

    @Test fun firstRun() = shoot("ready-no-hosts", HomeUiState(ConnectionUi.Ready(null, scanning = false), "Custom HID Deck is ready and advertising."))

    @Test fun reconnectPending() = shoot(
        "ready-reconnect",
        HomeUiState(ConnectionUi.Ready(mac, scanning = false), "Custom HID Deck is ready and advertising.", paired = paired),
    )

    @Test fun scanning() = shoot(
        "scanning",
        HomeUiState(
            ConnectionUi.Ready(mac, scanning = true), "Scanning for other Bluetooth hosts...",
            paired = paired, nearby = listOf(ipad, tablet), isScanning = true,
        ),
    )

    @Test fun pairing() = shoot(
        "pairing",
        HomeUiState(ConnectionUi.Pairing(ipad), "Pairing with 'iPad Air'... Please accept the pairing prompt.", paired = paired, nearby = listOf(ipad)),
    )

    @Test fun connecting() = shoot(
        "connecting",
        HomeUiState(ConnectionUi.Connecting(mac), "Connecting to 'Rick's MacBook Pro' (attempt 1)...", paired = paired),
    )

    @Test fun stalled() = shoot(
        "stalled",
        HomeUiState(ConnectionUi.Failed(FailReason.STALLED, mac), "Connecting to 'Rick's MacBook Pro' (attempt 3)...", paired = paired),
    )

    @Test fun connected() = shoot(
        "connected",
        HomeUiState(ConnectionUi.Connected(mac), "Link established with 'Rick's MacBook Pro'! Keyboard active.", paired = paired),
    )

    @Test fun connectedLongName() = shoot(
        "connected-long-name",
        HomeUiState(ConnectionUi.Connected(longName), "Link established.", paired = listOf(longName) + paired),
    )

    @Test fun disconnecting() = shoot(
        "disconnecting",
        HomeUiState(ConnectionUi.Disconnecting(mac), "Disconnecting physical link...", paired = paired),
    )

    @Test fun hostRefused() = shoot(
        "failed-host-refused",
        HomeUiState(
            ConnectionUi.Failed(FailReason.HOST_REFUSED, ipad, ConnectionFailure.HOST_REFUSED_PAIRING),
            "Pairing with 'iPad Air' refused or failed.", paired = paired, nearby = listOf(ipad),
        ),
    )

    @Test fun connectionRejected() = shoot(
        "failed-connection-rejected",
        HomeUiState(
            ConnectionUi.Failed(FailReason.HOST_REFUSED, pc, ConnectionFailure.CONNECTION_REJECTED),
            "Connection did not complete after repeated attempts. Retry connecting.", paired = paired,
        ),
    )

    @Test fun serviceFailed() = shoot(
        "failed-service",
        HomeUiState(
            ConnectionUi.Failed(FailReason.SERVICE_FAILED, null),
            "HID registration was lost. Tap Restart HID Service to recover.", paired = paired,
        ),
    )
}
