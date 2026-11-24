package dev.arnv.bluke.ui

import dev.arnv.bluke.testing.captureMatrix
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Home in a short window. Phones keep home portrait, but split screen and large screens can ignore that,
 * so the mode dock drops to one row and the card keeps its room. Baselines: `home-land-<state>-*-land.png`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w914dp-h411dp-land-xhdpi", sdk = [36])
class HomeLandscapeScreenshotTest {
    private val mac = HostRef("A4:83:E7:11:22:33", "Rick's MacBook Pro", bonded = true, type = DeviceType.COMPUTER)
    private val paired = listOf(mac, HostRef("3C:7C:3F:44:55:66", "DESKTOP-K16", bonded = true, type = DeviceType.COMPUTER))

    private fun shoot(name: String, state: HomeUiState) =
        captureMatrix(name = "home-land-$name", landscape = true) { HomeContent(state, HomeCallbacks()) }

    @Test fun connected() = shoot("connected", HomeUiState(ConnectionUi.Connected(mac), "Link established", paired = paired))

    @Test fun reconnect() = shoot("ready-reconnect", HomeUiState(ConnectionUi.Ready(mac, scanning = false), "Ready", paired = paired))
}
