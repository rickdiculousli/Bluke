package dev.arnv.bluke.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runAndroidComposeUiTest
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.arnv.bluke.bluetooth.BluetoothKeyboardManager
import dev.arnv.bluke.data.KEYBOARD_GEOMETRY_PREFERENCE
import dev.arnv.bluke.sound.KeyboardSoundSynthesizer
import dev.arnv.bluke.testing.captureMatrix
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w914dp-h411dp-land-420dpi", sdk = [36])
class KeyboardScreenshotTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    private lateinit var manager: BluetoothKeyboardManager
    private lateinit var synth: KeyboardSoundSynthesizer

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
        manager = BluetoothKeyboardManager(context)
        synth = KeyboardSoundSynthesizer(context)
    }

    @After
    fun tearDown() {
        synth.release()
        manager.close()
        prefs.edit().clear().commit()
    }

    @Test
    fun defaultGeometryOffline() {
        captureMatrix(name = "keyboard-default-offline", landscape = true) {
            KeyboardSurfaceUnderTest(manager, synth, prefs)
        }
    }

    @Test
    fun connectedWithLongHostName() {
        val host = SurfaceHost(SurfaceHostStatus.Connected, "Living-room Mac mini (2024) — media and work desk")
        captureMatrix(name = "keyboard-connected-long-host", landscape = true) {
            KeyboardSurfaceUnderTest(manager, synth, prefs, host = host)
        }
    }

    @Test
    fun capsLockLitByHost() {
        val host = SurfaceHost(SurfaceHostStatus.Connected, "MacBook Pro")
        captureMatrix(name = "keyboard-caps-lock", landscape = true) {
            KeyboardSurfaceUnderTest(manager, synth, prefs, host = host, homeUiState = HomeUiState(capsLock = true))
        }
    }

    @Test
    fun hhkbUsesTheHeight() {
        prefs.edit().putString(KEYBOARD_GEOMETRY_PREFERENCE, KeyboardGeometry.HHKB_60.name).commit()
        captureMatrix(name = "keyboard-hhkb60", landscape = true, fontScales = listOf(1.0f)) {
            KeyboardSurfaceUnderTest(manager, synth, prefs)
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun tuneSheetOpen() = runAndroidComposeUiTest<ComponentActivity> {
        setContent { KeyboardSurfaceUnderTest(manager, synth, prefs) }
        onNodeWithTag("surface_tune").performClick()
        waitForIdle()
        onRoot().captureRoboImage(filePath = "src/test/screenshots/keyboard-tune-open.png")
        onNodeWithText("Edit themes").performScrollTo()
        waitForIdle()
        onRoot().captureRoboImage(filePath = "src/test/screenshots/keyboard-tune-theme.png")
    }
}
