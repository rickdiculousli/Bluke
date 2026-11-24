package dev.arnv.bluke.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runAndroidComposeUiTest
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w914dp-h411dp-land-420dpi", sdk = [36])
class TouchpadScreenshotTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = BluetoothKeyboardManager(context)
    private val connected = SurfaceHost(SurfaceHostStatus.Connected, "Studio MacBook Pro")

    @After
    fun closeManager() {
        manager.close()
    }

    private fun prefs(name: String, setup: SharedPreferences.Editor.() -> Unit = {}): SharedPreferences =
        context.getSharedPreferences("touchpad-screenshot-$name", Context.MODE_PRIVATE).also {
            it.edit().clear().apply(setup).commit()
        }

    private fun captureTouchpad(name: String, prefs: SharedPreferences, host: SurfaceHost = connected) =
        captureMatrix(name = name, landscape = true) {
            SurfaceTheme {
                TouchpadView(
                    btManager = manager,
                    chrome = previewChrome(InputMode.TOUCHPAD, host),
                    sharedPrefs = prefs,
                    caseBrush = SolidColor(Color(0xFF1E1E20)),
                )
            }
        }

    @Test
    fun clickpadWithItsGestureHint() {
        captureTouchpad("touchpad-clickpad", prefs("clickpad"))
    }

    @Test
    fun threeButtonsWithTheNumpadOn() {
        captureTouchpad(
            "touchpad-threebutton-numpad",
            prefs("numpad") {
                putString(TOUCHPAD_BUTTON_MODE_PREFERENCE, TrackpadButtonMode.THREE_BUTTONS.preferenceValue)
                putBoolean(TOUCHPAD_NUMPAD_PREFERENCE, true)
            },
        )
    }

    @Test
    fun modifierRailOnTheLeftWithNoHost() {
        captureTouchpad(
            "touchpad-rail-left",
            prefs("rail") {
                putString(TOUCHPAD_MODIFIER_POSITION_PREFERENCE, TouchpadModifierPosition.LEFT.preferenceValue)
                putString(TOUCHPAD_BUTTON_MODE_PREFERENCE, TrackpadButtonMode.TWO_BUTTONS.preferenceValue)
            },
            host = SurfaceHost(SurfaceHostStatus.Offline, "Studio MacBook Pro", onReconnect = {}),
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun tuneSheetOpen() {
        val prefs = prefs("tune")
        runAndroidComposeUiTest<ComponentActivity> {
            setContent {
                SurfaceTheme {
                    TouchpadView(
                        btManager = manager,
                        chrome = previewChrome(InputMode.TOUCHPAD, connected),
                        sharedPrefs = prefs,
                        caseBrush = SolidColor(Color(0xFF1E1E20)),
                    )
                }
            }
            onNodeWithTag("surface_tune").performClick()
            waitForIdle()
            onRoot().captureRoboImage(filePath = "src/test/screenshots/touchpad-tune-dark.png")
        }
    }
}
