package dev.arnv.bluke.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.arnv.bluke.ui.theme.MyApplicationTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Filmstrips of home's motion with the clock held still: a press on touch-down, Paired stepping aside
 * when a host connects, and Details opening. Each frame is `src/test/screenshots/home-motion-<what>-<ms>ms.png`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h914dp-port-notnight-xhdpi", sdk = [36])
class HomeMotionScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val mac = HostRef("A4:83:E7:11:22:33", "Rick's MacBook Pro", bonded = true, type = DeviceType.COMPUTER)
    private val paired = listOf(
        mac,
        HostRef("3C:7C:3F:44:55:66", "DESKTOP-K16", bonded = true, type = DeviceType.COMPUTER),
        HostRef("F0:B4:29:77:88:99", "Living Room Chromecast", bonded = true, type = DeviceType.TV),
    )
    private val ready = HomeUiState(ConnectionUi.Ready(mac, scanning = false), "Ready", paired = paired)
    private val connected = HomeUiState(ConnectionUi.Connected(mac), "Link established", paired = paired)

    @Before
    fun staticLightTheme() {
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .edit().putInt("theme_mode", 1).putBoolean("dynamic_color", false).commit()
    }

    private fun frames(name: String, steps: List<Long>) {
        var elapsed = 0L
        for (step in steps) {
            compose.mainClock.advanceTimeBy(step - elapsed)
            elapsed = step
            compose.onRoot().captureRoboImage(
                filePath = "src/test/screenshots/home-motion-$name-${step}ms.png",
                // A frame mid-motion anti-aliases a rotating chevron a few pixels differently depending on where the
                // click lands against the frame clock; a real change moves far more than 0.05% of the screen.
                roborazziOptions = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.0005f)),
            )
        }
    }

    @Test
    fun aTilePressShowsOnTouchDown() {
        compose.setContent { MyApplicationTheme { HomeContent(ready, HomeCallbacks()) } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("mode_tile_keyboard").performTouchInput { down(center) }
        frames("tile-press", listOf(0, 16, 48))
        compose.onNodeWithTag("mode_tile_keyboard").performTouchInput { up() }
    }

    @Test
    fun pairedStepsAsideWhenAHostConnects() {
        var state by mutableStateOf(ready)
        compose.setContent { MyApplicationTheme { HomeContent(state, HomeCallbacks()) } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        state = connected
        frames("connect", listOf(0, 50, 100, 200, 400))
    }

    @Test
    fun detailsOpen() {
        compose.setContent { MyApplicationTheme { HomeContent(connected, HomeCallbacks()) } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("connection_details_toggle").performClick()
        frames("details", listOf(0, 50, 100, 200, 400))
    }
}
