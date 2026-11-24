package dev.arnv.bluke.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.drawToBitmap
import org.junit.Assert.assertNotEquals
import android.content.SharedPreferences
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.arnv.bluke.bluetooth.BluetoothKeyboardManager
import dev.arnv.bluke.bluetooth.GamepadDpadOutputMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w914dp-h411dp-land-420dpi", sdk = [36])
class GamepadSurfaceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = BluetoothKeyboardManager(context)
    private val prefs: SharedPreferences =
        context.getSharedPreferences("gamepad-surface-test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
    private val haptics = RecordingHapticPerformer()

    @After
    fun closeManager() {
        manager.close()
    }

    private fun setSurface(shown: () -> Boolean = { true }) = compose.setContent {
        CompositionLocalProvider(LocalHapticPerformer provides haptics) {
            SurfaceTheme {
                if (shown()) {
                    GamepadView(
                        btManager = manager,
                        chrome = previewChrome(InputMode.GAMEPAD),
                        sharedPrefs = prefs,
                        caseBrush = SolidColor(Color.Unspecified),
                    )
                }
            }
        }
    }

    private fun pressA() = compose.onNodeWithTag("gamepad_face_A").performTouchInput {
        down(center)
        up()
    }

    @Test
    fun hapticsOffPlaysNothingOnAPress() {
        prefs.edit().putBoolean(HAPTICS_PREFERENCE, false).commit()
        setSurface()
        pressA()
        compose.waitForIdle()
        assertEquals(emptyList<HapticEvent>(), haptics.events)
    }

    @Test
    fun hapticsOnPlaysAPressOnTouchDown() {
        prefs.edit().putBoolean(HAPTICS_PREFERENCE, true).commit()
        setSurface()
        pressA()
        compose.waitForIdle()
        assertEquals(listOf(HapticEvent.PRESS), haptics.events)
    }

    @Test
    fun aStickClickPlaysOnePress() {
        prefs.edit().putBoolean(HAPTICS_PREFERENCE, true).commit()
        setSurface()
        compose.onNodeWithContentDescription("Left stick").performTouchInput {
            down(center)
            up()
        }
        compose.waitForIdle()
        assertEquals(listOf(HapticEvent.PRESS), haptics.events)
    }

    @Test
    fun faceButtonLooksPressedInTheFirstFrame() {
        setSurface()
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        val button = compose.onNodeWithTag("gamepad_face_A")
        val bounds = button.fetchSemanticsNode().boundsInWindow
        // A point on the cap, clear of the letter.
        val probeX = (bounds.left + bounds.width * 0.2f).toInt()
        val probeY = (bounds.top + bounds.height / 2).toInt()
        val before = drawWindow()

        button.performTouchInput { down(center) }
        // The handler ran on the down (it is synchronous); the test harness posts the snapshot's apply
        // notification to the main looper, which a device's Choreographer drains before the frame.
        // waitForIdle drains it without advancing the clock; then exactly one frame is drawn.
        val downTime = compose.mainClock.currentTime
        compose.waitForIdle()
        assertEquals("draining the looper must not advance the clock", downTime, compose.mainClock.currentTime)
        compose.mainClock.advanceTimeByFrame()

        val after = drawWindow()
        assertNotEquals("A should look pressed one frame after touch-down", before.getPixel(probeX, probeY), after.getPixel(probeX, probeY))
        button.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Pressed"))
        after.captureRoboImage(filePath = "src/test/screenshots/gamepad-pressed-frame1.png")
        button.performTouchInput { up() }
    }

    private fun drawWindow(): Bitmap = compose.runOnUiThread { compose.activity.window.decorView.drawToBitmap() }

    @Test
    fun profileChangeAppliesWithoutReopening() {
        setSurface()
        compose.onNodeWithText("Native profile").assertIsDisplayed()

        GamepadTuneState(GamepadDpadOutputMode.WEB_BUTTONS).write(prefs)
        compose.waitForIdle()

        compose.onNodeWithText("Web profile").assertIsDisplayed()
    }

    @Test
    fun consoleSurvivesReopeningTheSurface() {
        var shown by mutableStateOf(true)
        setSurface { shown }
        compose.onNodeWithText("Xbox-style").assertIsDisplayed()

        compose.onNodeWithTag("console_selector_pill").performClick()
        compose.onNodeWithText("PlayStation-style").assertIsDisplayed()

        shown = false
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithTagCount("console_selector_pill") == 0)
        shown = true
        compose.waitForIdle()

        compose.onNodeWithText("PlayStation-style").assertIsDisplayed()
        compose.onNodeWithTag("gamepad_face_Cross").assertIsDisplayed()
    }

    @Test
    fun unknownStoredConsoleReadsAsTheFirst() {
        prefs.edit().putString(GAMEPAD_CONSOLE_PREFERENCE, "dreamcast").commit()
        setSurface()
        compose.onNodeWithText("Xbox-style").assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithTagCount(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size
}
