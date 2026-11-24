package dev.arnv.bluke.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.test.core.app.ApplicationProvider
import dev.arnv.bluke.testing.captureMatrix
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The shared toolbar rendering each [SurfaceHostStatus], one row per status, as every surface shows it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w914dp-h411dp-land-420dpi", sdk = [36])
class SurfaceToolbarScreenshotTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("toolbar-screenshot", Context.MODE_PRIVATE)

    @Test
    fun everyHostStatus() {
        val hosts = listOf(
            SurfaceHost(SurfaceHostStatus.Connected, "Rick's MacBook Pro (Work, 14-inch, 2024)"),
            SurfaceHost(SurfaceHostStatus.Connecting, "Desk PC"),
            SurfaceHost(SurfaceHostStatus.Offline, "Desk PC", onReconnect = {}),
            SurfaceHost(SurfaceHostStatus.Offline, null),
            SurfaceHost(SurfaceHostStatus.Blocked, null),
        )
        captureMatrix(name = "surface-toolbar-host-status", landscape = true) {
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
                                SurfaceToolbarIconButton(Icons.Default.Dialpad, "Numpad", onClick = {}, selected = index % 2 == 0)
                            }
                        }
                    }
                }
            }
        }
    }
}
