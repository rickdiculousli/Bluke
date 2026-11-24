package dev.arnv.bluke.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runAndroidComposeUiTest
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.arnv.bluke.bluetooth.BluetoothKeyboardManager
import dev.arnv.bluke.testing.captureMatrix
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w914dp-h411dp-land-420dpi", sdk = [36])
class MultimediaScreenshotTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = BluetoothKeyboardManager(context)
    private val prefs = context.getSharedPreferences("multimedia-screenshot", Context.MODE_PRIVATE)

    @After
    fun closeManager() {
        manager.close()
    }

    private val offline = SurfaceHost(SurfaceHostStatus.Offline, "Studio MacBook Pro", onReconnect = {})
    private val connected = SurfaceHost(SurfaceHostStatus.Connected, "Living Room Media Center PC (Windows 11)")

    private fun capture(name: String, posture: MultimediaPosture, host: SurfaceHost) =
        captureMatrix(name = name, landscape = true) {
            SurfaceTheme {
                MediaPresentationView(
                    btManager = manager,
                    chrome = previewChrome(InputMode.MEDIA_PRESENTATION, host),
                    sharedPrefs = prefs,
                    postureOverride = posture,
                )
            }
        }

    @Test
    fun landscapeOffline() = capture("multimedia-landscape-offline", MultimediaPosture.LANDSCAPE, offline)

    @Test
    fun landscapeConnected() = capture("multimedia-landscape-connected", MultimediaPosture.LANDSCAPE, connected)

    @Test
    fun uprightOffline() = capture("multimedia-upright-offline", MultimediaPosture.PORTRAIT_HELD, offline)

    @Test
    fun uprightConnected() = capture("multimedia-upright-connected", MultimediaPosture.PORTRAIT_HELD, connected)

    @Test
    fun tuneSheetOpen() = runAndroidComposeUiTest<ComponentActivity> {
        // Not through captureMatrix: the sheet opens from a tap, and the surface is always dark.
        setContent {
            SurfaceTheme {
                MediaPresentationView(
                    btManager = manager,
                    chrome = previewChrome(InputMode.MEDIA_PRESENTATION, connected),
                    sharedPrefs = prefs,
                    postureOverride = MultimediaPosture.LANDSCAPE,
                )
            }
        }
        onNodeWithTag("surface_tune").performClick()
        waitForIdle()
        onNodeWithTag("surface_tune_sheet").assertExists()
        onRoot().captureRoboImage(filePath = "src/test/screenshots/multimedia-tune-open.png")
    }
}
