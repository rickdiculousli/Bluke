package dev.arnv.bluke.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import android.graphics.Bitmap
import androidx.core.view.drawToBitmap
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.arnv.bluke.RemoteVolumeKeyHost
import dev.arnv.bluke.bluetooth.BluetoothKeyboardManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
class MultimediaSurfaceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = BluetoothKeyboardManager(context)
    private val prefs: SharedPreferences =
        context.getSharedPreferences("multimedia-surface-test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }

    @After
    fun closeManager() {
        manager.close()
    }

    private fun setRemote(
        recorder: RecordingHapticPerformer = RecordingHapticPerformer(),
        posture: MultimediaPosture = MultimediaPosture.LANDSCAPE,
        hostContext: Context? = null,
    ) {
        compose.setContent {
            val base = LocalContext.current
            CompositionLocalProvider(
                LocalHapticPerformer provides recorder,
                LocalContext provides (hostContext ?: base),
            ) {
                SurfaceTheme {
                    MediaPresentationView(
                        btManager = manager,
                        chrome = previewChrome(InputMode.MEDIA_PRESENTATION),
                        sharedPrefs = prefs,
                        postureOverride = posture,
                    )
                }
            }
        }
    }

    private fun pressPlayPause() {
        compose.onNodeWithTag("multimedia_play_pause").performTouchInput {
            down(center)
            up()
        }
        compose.waitForIdle()
    }

    @Test
    fun pointerPadIsNamedAndHeadedFlat() = assertPointerPadIsLabelled(MultimediaPosture.LANDSCAPE)

    @Test
    fun pointerPadIsNamedAndHeadedUpright() = assertPointerPadIsLabelled(MultimediaPosture.PORTRAIT_HELD)

    private fun assertPointerPadIsLabelled(posture: MultimediaPosture) {
        setRemote(posture = posture)

        compose.onNodeWithContentDescription("Pointer pad").assertExists()
            .assert(hasText("Tap to click · Two fingers to scroll"))
        compose.onNode(hasText("Pointer") and isHeading()).assertExists()
        compose.onNode(hasText("Slides") and isHeading()).assertExists()
    }

    @Test
    fun hapticsOffPlaysNothingOnAPress() {
        prefs.edit().putBoolean(HAPTICS_PREFERENCE, false).commit()
        val recorder = RecordingHapticPerformer()
        setRemote(recorder)

        pressPlayPause()

        assertEquals(emptyList<HapticEvent>(), recorder.events)
    }

    @Test
    fun hapticsOnPlaysAPressOnTouchDown() {
        prefs.edit().putBoolean(HAPTICS_PREFERENCE, true).commit()
        val recorder = RecordingHapticPerformer()
        setRemote(recorder)

        pressPlayPause()

        assertEquals(listOf(HapticEvent.PRESS), recorder.events)
    }

    @Test
    fun hapticsReachButtonsUpright() {
        val recorder = RecordingHapticPerformer()
        setRemote(recorder, MultimediaPosture.PORTRAIT_HELD)

        pressPlayPause()

        assertEquals(listOf(HapticEvent.PRESS), recorder.events)
    }

    @Test
    fun playPauseLooksPressedInTheFrameTheFingerLands() {
        setRemote()
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        val node = compose.onNodeWithTag("multimedia_play_pause")
        val bounds = node.fetchSemanticsNode().boundsInWindow
        // A point on the button's container, clear of its glyphs.
        val probeX = (bounds.left + 12).toInt()
        val probeY = (bounds.top + bounds.height / 2).toInt()
        val before = drawWindow()

        node.performTouchInput { down(center) }
        compose.mainClock.advanceTimeByFrame()

        val after = drawWindow()
        assertNotEquals(
            "play/pause should look pressed one frame after touch-down",
            before.getPixel(probeX, probeY),
            after.getPixel(probeX, probeY),
        )
        after.captureRoboImage(filePath = "src/test/screenshots/multimedia-pressed-frame1.png")
        node.performTouchInput { up() }
    }

    private fun drawWindow(): Bitmap = compose.runOnUiThread { compose.activity.window.decorView.drawToBitmap() }

    @Test
    fun volumeButtonsSwitchAppliesWithoutReopening() {
        val host = RecordingVolumeKeyHost(context)
        setRemote(hostContext = host)
        compose.waitForIdle()
        assertNull(host.handler)

        MultimediaTuneState.read(prefs).copy(hardwareVolumeButtons = true).write(prefs)
        compose.waitForIdle()

        assertNotNull("the phone's volume keys should drive the host at once", host.handler)
        compose.onNodeWithText("Volume · phone buttons on").assertExists()

        MultimediaTuneState.read(prefs).copy(hardwareVolumeButtons = false).write(prefs)
        compose.waitForIdle()
        assertNull(host.handler)
        compose.onNodeWithText("Volume").assertExists()
    }

    @Test
    fun tuneStateRoundTripsThroughPreferences() {
        val state = MultimediaTuneState(PointerTuneState(pointerSpeed = 2f, scrollSpeed = 0.5f), hardwareVolumeButtons = true)
        state.write(prefs)
        assertEquals(state, MultimediaTuneState.read(prefs))
        assertTrue(prefs.getBoolean(HARDWARE_VOLUME_REMOTE_PREFERENCE, false))
    }

    private class RecordingVolumeKeyHost(base: Context) : ContextWrapper(base), RemoteVolumeKeyHost {
        var handler: ((Int, Boolean) -> Unit)? = null
        override fun setRemoteVolumeKeyHandler(handler: ((keyCode: Int, isPressed: Boolean) -> Unit)?) {
            this.handler = handler
        }
    }
}
