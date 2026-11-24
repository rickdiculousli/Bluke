package dev.arnv.bluke.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.test.core.app.ApplicationProvider
import dev.arnv.bluke.testing.captureMatrix
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The shared toolbar rendering each [SurfaceHostStatus], one row per status, with the keyboard's controls. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w914dp-h411dp-land-420dpi", sdk = [36])
class SurfaceToolbarScreenshotTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("toolbar-screenshot", Context.MODE_PRIVATE)

    @Test
    fun everyHostStatus() = captureHosts("surface-toolbar-host-status")

    /** A small landscape phone: the host name gives way first, and Tune stays on screen. */
    @Test
    @Config(qualifiers = "w640dp-h360dp-land-xhdpi")
    fun everyHostStatusOnANarrowPhone() = captureHosts("surface-toolbar-host-status-narrow")

    /** A portrait phone: the keyboard's three LEDs and sound toggle give way before the host chip does. */
    @Test
    @Config(qualifiers = "w411dp-h500dp-port-420dpi")
    fun everyHostStatusOnAPortraitPhone() = captureHosts("surface-toolbar-host-status-portrait", landscape = false)

    private fun captureHosts(name: String, landscape: Boolean = true) {
        val hosts = listOf(
            SurfaceHost(SurfaceHostStatus.Connected, "Rick's MacBook Pro (Work, 14-inch, 2024)"),
            SurfaceHost(SurfaceHostStatus.Connecting, "Desk PC"),
            SurfaceHost(SurfaceHostStatus.Offline, "Desk PC", onReconnect = {}),
            SurfaceHost(SurfaceHostStatus.Offline, null),
            SurfaceHost(
                SurfaceHostStatus.Blocked, null, reason = "Bluetooth off",
                onFix = {}, fixLabel = "Turn on", fixClickLabel = "Turn on Bluetooth", fixIcon = Icons.Default.Bluetooth,
            ),
            SurfaceHost(SurfaceHostStatus.Blocked, null, reason = BlockReason.NO_HID_ROLE.chipReason),
        )
        captureMatrix(name = name, landscape = landscape) {
            SurfaceTheme {
                val haptics = rememberSurfaceHaptics(prefs)
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Column {
                        hosts.forEachIndexed { index, host ->
                            SurfaceToolbar(
                                chrome = previewChrome(InputMode.entries[index % InputMode.entries.size], host),
                                haptics = haptics,
                                onTune = {},
                            ) {
                                SurfaceToolbarLeds(listOf("CAPS" to (index == 0), "NUM" to true, "SCR" to false), onToggle = {})
                                SurfaceToolbarIconButton(Icons.AutoMirrored.Filled.VolumeUp, "Key sound", onClick = {}, selected = index % 2 == 0)
                            }
                        }
                    }
                }
            }
        }
    }
}
