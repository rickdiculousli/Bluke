package dev.arnv.bluke.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import android.graphics.Bitmap
import androidx.core.view.drawToBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.arnv.bluke.bluetooth.BluetoothKeyboardManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w914dp-h411dp-land-420dpi", sdk = [36])
class TouchpadSurfaceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = BluetoothKeyboardManager(context)
    private val prefs: SharedPreferences = context.getSharedPreferences("touchpad-surface-test", Context.MODE_PRIVATE)
    private val haptics = RecordingHapticPerformer()
    private val reports = mutableListOf<ByteArray>()
    private val sink = MouseReportSink { b, x, y, w, p -> reports += byteArrayOf(b, x, y, w, p) }

    @Before
    fun clearPrefs() {
        prefs.edit().clear().commit()
    }

    @After
    fun closeManager() {
        manager.close()
    }

    private fun showTouchpad(modifierStyle: KeyboardModifierStyle = KeyboardModifierStyle.PC, visible: () -> Boolean = { true }) {
        compose.setContent {
            CompositionLocalProvider(LocalHapticPerformer provides haptics, LocalMouseReportSink provides sink) {
                SurfaceTheme {
                    if (visible()) {
                        TouchpadView(
                            btManager = manager,
                            chrome = previewChrome(InputMode.TOUCHPAD),
                            sharedPrefs = prefs,
                            caseBrush = SolidColor(Color(0xFF1E1E20)),
                            modifierStyle = modifierStyle,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun tapPad() {
        compose.onNodeWithTag("touchpad_gesture_layer").performTouchInput {
            down(center)
            up()
        }
        compose.waitForIdle()
    }

    private fun pressNumpadKey(legend: String) {
        compose.onNodeWithTag("numpad_key_$legend").performTouchInput {
            down(center)
            up()
        }
        compose.waitForIdle()
    }

    @Test
    fun thePadIsNamedWithItsHint() {
        showTouchpad()
        compose.onNodeWithContentDescription("Pointer pad").assertExists()
            .assert(hasText("Tap to click · Two fingers to scroll"))
    }

    @Test
    fun anOpenTuneSheetHidesThePadFromTalkBack() {
        showTouchpad()
        prefs.edit().putBoolean(TOUCHPAD_NUMPAD_PREFERENCE, true).commit()
        compose.waitForIdle()
        fun padNodes() = compose.onAllNodesWithTag("touchpad_gesture_layer").fetchSemanticsNodes().size +
            compose.onAllNodesWithTag("numpad_key_5").fetchSemanticsNodes().size
        assertEquals(2, padNodes())

        compose.onNodeWithTag("surface_tune").performClick()
        compose.waitForIdle()
        assertEquals("no pad or numpad nodes under the open sheet", 0, padNodes())
        // The toolbar stays reachable, and the scrim is one node that names and does its action.
        compose.onNodeWithTag("touchpad_numpad_toggle").assertExists()
        val scrim = compose.onNodeWithTag("surface_tune_scrim").fetchSemanticsNode().config
        assertEquals(listOf("Close Touchpad"), scrim.getOrNull(SemanticsProperties.ContentDescription))
        assertEquals("Close Touchpad", scrim.getOrNull(SemanticsActions.OnClick)?.label)

        compose.onNodeWithTag("surface_tune_scrim").performClick()
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithTag("surface_tune_scrim").fetchSemanticsNodes().size)
        assertEquals(2, padNodes())
    }

    @Test
    fun aPredictiveBackSlidesTheSheetAndCancellingPutsItBack() {
        showTouchpad()
        compose.onNodeWithTag("surface_tune").performClick()
        compose.waitForIdle()
        fun sheetLeft() = compose.onNodeWithTag("surface_tune_sheet").fetchSemanticsNode().boundsInRoot.left
        val open = sheetLeft()
        val dispatcher = compose.activity.onBackPressedDispatcher
        fun back(progress: Float) = androidx.activity.BackEventCompat(0f, 0f, progress, androidx.activity.BackEventCompat.EDGE_RIGHT)

        compose.runOnUiThread {
            dispatcher.dispatchOnBackStarted(back(0f))
            dispatcher.dispatchOnBackProgressed(back(0.5f))
        }
        compose.waitForIdle()
        assertTrue("the sheet moves toward its edge with the gesture", sheetLeft() > open + 1f)

        compose.runOnUiThread { dispatcher.dispatchOnBackCancelled() }
        compose.waitForIdle()
        assertEquals(open, sheetLeft(), 0.5f)

        compose.runOnUiThread {
            dispatcher.dispatchOnBackStarted(back(0f))
            dispatcher.dispatchOnBackProgressed(back(0.8f))
            dispatcher.onBackPressed()
        }
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithTag("surface_tune_sheet").fetchSemanticsNodes().size)
    }

    @Test
    fun withHapticsOffATapOnThePadPlaysNothing() {
        prefs.edit().putBoolean(HAPTICS_PREFERENCE, false).commit()
        showTouchpad()
        tapPad()
        assertEquals(emptyList<HapticEvent>(), haptics.events)
    }

    @Test
    fun withHapticsOnATapOnThePadClicks() {
        prefs.edit().putBoolean(HAPTICS_PREFERENCE, true).commit()
        showTouchpad()
        tapPad()
        assertEquals(listOf(HapticEvent.CLICK), haptics.events)
    }

    @Test
    fun withHapticsOffANumpadKeyPlaysNothing() {
        prefs.edit().putBoolean(HAPTICS_PREFERENCE, false).putBoolean(TOUCHPAD_NUMPAD_PREFERENCE, true).commit()
        showTouchpad()
        pressNumpadKey("7")
        assertEquals(emptyList<HapticEvent>(), haptics.events)
    }

    @Test
    fun withHapticsOnANumpadKeyPressesOnTouchDown() {
        prefs.edit().putBoolean(HAPTICS_PREFERENCE, true).putBoolean(TOUCHPAD_NUMPAD_PREFERENCE, true).commit()
        showTouchpad()
        compose.onNodeWithTag("numpad_key_7").performTouchInput { down(center) }
        compose.waitForIdle()
        assertEquals(listOf(HapticEvent.PRESS), haptics.events)
        compose.onNodeWithTag("numpad_key_7").performTouchInput { up() }
        compose.waitForIdle()
        assertEquals("release plays nothing", listOf(HapticEvent.PRESS), haptics.events)
    }

    /** The window as drawn now, without waiting for a frame (the clock may be paused). */
    private fun drawWindow(): Bitmap = compose.runOnUiThread { compose.activity.window.decorView.drawToBitmap() }

    /** The pixel at the centre of [tag]'s node. */
    private fun centrePixel(image: Bitmap, tag: String): Int {
        val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow
        // Off-centre, so the legend's strokes don't decide it.
        return image.getPixel((bounds.left + bounds.width * 0.15f).toInt(), (bounds.top + bounds.height * 0.15f).toInt())
    }

    @Test
    fun aNumpadKeyLooksPressedOnTheFrameItIsTouched() {
        prefs.edit().putBoolean(TOUCHPAD_NUMPAD_PREFERENCE, true).commit()
        showTouchpad()
        compose.mainClock.autoAdvance = false
        val key = compose.onNodeWithTag("numpad_key_5")
        val before = centrePixel(drawWindow(), "numpad_key_5")

        key.performTouchInput { down(center) }
        compose.mainClock.advanceTimeByFrame()

        key.assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        val after = centrePixel(drawWindow(), "numpad_key_5")
        assertNotEquals("the key should draw pressed on the first frame", before, after)
        compose.mainClock.advanceTimeBy(500)
        val settled = centrePixel(drawWindow(), "numpad_key_5")
        assertEquals("frame 1 should already be the full pressed look, not a fade toward it", settled, after)
        compose.onNodeWithTag("touchpad_view_root").captureRoboImage(filePath = "src/test/screenshots/touchpad-pressed-frame1.png")

        key.performTouchInput { up() }
        compose.mainClock.autoAdvance = true
    }

    private fun dragPointerRight(): Int {
        reports.clear()
        compose.onNodeWithTag("touchpad_gesture_layer").performTouchInput {
            down(center)
            moveBy(Offset(12f, 0f))
            moveBy(Offset(12f, 0f))
            up()
        }
        compose.waitForIdle()
        return reports.sumOf { it[1].toInt() }
    }

    @Test
    fun aPointerSpeedWrittenFromTuneAppliesWithoutReopening() {
        TouchpadTuneState(speeds = PointerTuneState(pointerSpeed = 0.5f)).write(prefs)
        showTouchpad()
        val slow = dragPointerRight()

        TouchpadTuneState.read(prefs).let { it.copy(speeds = it.speeds.copy(pointerSpeed = 2.5f)) }.write(prefs)
        compose.waitForIdle()
        val fast = dragPointerRight()

        assertTrue("slow drag moved the pointer ($slow)", slow > 0)
        assertTrue("fast $fast should be about five times slow $slow", fast >= slow * 4)
    }

    @Test
    fun aModifierRailWrittenFromTuneAppearsWithoutReopening() {
        showTouchpad()
        compose.onNodeWithTag("touchpad_rail_left").assertDoesNotExist()
        TouchpadTuneState.read(prefs).copy(modifierPosition = TouchpadModifierPosition.LEFT).write(prefs)
        compose.waitForIdle()
        compose.onNodeWithTag("touchpad_rail_left").assertIsDisplayed()
    }

    private fun railToggle() = compose.onNodeWithTag("touchpad_modifier_position")

    @Test
    fun theRailToggleIsASwitchShowingWhetherTheRailIsOn() {
        showTouchpad()
        railToggle()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Off"))

        railToggle().performClick()
        compose.waitForIdle()

        railToggle()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "On, left"))
        compose.onNodeWithTag("touchpad_rail_left").assertIsDisplayed()
    }

    @Test
    fun turningTheRailBackOnReturnsItToTheLastSideChosen() {
        TouchpadTuneState(modifierPosition = TouchpadModifierPosition.RIGHT).write(prefs)
        showTouchpad()
        compose.onNodeWithTag("touchpad_rail_right").assertIsDisplayed()

        railToggle().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("touchpad_rail_right").assertDoesNotExist()
        assertEquals("off", prefs.getString(TOUCHPAD_MODIFIER_POSITION_PREFERENCE, null))

        railToggle().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("touchpad_rail_right").assertIsDisplayed()
        compose.onNodeWithTag("touchpad_rail_left").assertDoesNotExist()
        assertEquals("right", prefs.getString(TOUCHPAD_MODIFIER_POSITION_PREFERENCE, null))
    }

    private val held = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Held")
    private val released = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Released")

    @Test
    fun noRailKeyLooksHeldUntilItIsHeld() {
        TouchpadTuneState(modifierPosition = TouchpadModifierPosition.LEFT).write(prefs)
        showTouchpad()
        val labels = listOf("Ctrl", "Shift", "Alt", "Win")
        labels.forEach { compose.onNodeWithTag("touchpad_rail_key_$it").assert(released) }
        val idle = drawWindow()
        val colours = labels.map { centrePixel(idle, "touchpad_rail_key_$it") }
        assertEquals("every unheld rail key draws alike: $colours", 1, colours.toSet().size)

        val win = compose.onNodeWithTag("touchpad_rail_key_Win")
        win.performTouchInput { down(center) }
        compose.waitForIdle()
        win.assert(held)
        assertNotEquals("a held key draws differently", colours.first(), centrePixel(drawWindow(), "touchpad_rail_key_Win"))
        win.performTouchInput { up() }
        compose.waitForIdle()
        win.assert(released)
    }

    @Test
    fun railKeysUseTheKeyboardsNamesForTheHostStyle() {
        TouchpadTuneState(modifierPosition = TouchpadModifierPosition.LEFT).write(prefs)
        showTouchpad(KeyboardModifierStyle.PC)
        compose.onNodeWithTag("touchpad_rail_key_Win").assertExists()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Windows")))
        compose.onNodeWithTag("touchpad_rail_key_Meta").assertDoesNotExist()
    }

    @Test
    fun railKeysUseMacNamesOnAMacHost() {
        TouchpadTuneState(modifierPosition = TouchpadModifierPosition.LEFT).write(prefs)
        showTouchpad(KeyboardModifierStyle.MAC)
        compose.onNodeWithTag("touchpad_rail_key_Cmd").assertExists()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Command")))
        compose.onNodeWithTag("touchpad_rail_key_Opt").assertExists()
    }

    @Test
    fun talkBackLatchesARailKeyUntilItIsActivatedAgain() {
        TouchpadTuneState(modifierPosition = TouchpadModifierPosition.LEFT).write(prefs)
        showTouchpad()
        val ctrl = compose.onNodeWithTag("touchpad_rail_key_Ctrl")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
            .assert(released)

        ctrl.performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        ctrl.assert(held)
        compose.onNodeWithTag("touchpad_rail_key_Shift").assert(released)

        ctrl.performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        ctrl.assert(released)
    }

    @Test
    fun aTouchOnALatchedRailKeyReleasesItWhenTheFingerLifts() {
        TouchpadTuneState(modifierPosition = TouchpadModifierPosition.LEFT).write(prefs)
        showTouchpad()
        val shift = compose.onNodeWithTag("touchpad_rail_key_Shift")
        shift.performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        shift.assert(held)
        shift.performTouchInput { down(center); up() }
        compose.waitForIdle()
        shift.assert(released)
    }

    @Test
    fun theTuneSheetNamesEachSingleChoiceOnce() {
        showTouchpad()
        compose.onNodeWithTag("surface_tune").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Buttons").assertExists()
        compose.onNodeWithText("This pad").assertDoesNotExist()
        compose.onNodeWithText("Click buttons").assertDoesNotExist()
        compose.onNodeWithText("Modifier keys").assertExists()
        compose.onNodeWithText("Ctrl, Shift, Alt and Meta beside the pad").assertDoesNotExist()
    }

    @Test
    fun buttonModeAndNumpadSurviveLeavingTheSurface() {
        var visible by mutableStateOf(true)
        showTouchpad(visible = { visible })

        compose.onNodeWithTag("surface_tune").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("3-button").performClick()
        compose.onNodeWithText("Numpad").performClick()
        compose.waitForIdle()

        visible = false
        compose.waitForIdle()
        compose.onNodeWithTag("touchpad_view_root").assertDoesNotExist()
        visible = true
        compose.waitForIdle()

        compose.onNodeWithTag("touchpad_button_middle").assertExists()
        compose.onNodeWithTag("numpad_key_7").assertExists()
        assertEquals("three_buttons", prefs.getString(TOUCHPAD_BUTTON_MODE_PREFERENCE, null))
        assertTrue(prefs.getBoolean(TOUCHPAD_NUMPAD_PREFERENCE, false))
    }

    @Test
    fun anUnknownButtonModeReadsAsClickpad() {
        assertEquals(TrackpadButtonMode.CLICKPAD, TrackpadButtonMode.fromPreference("four_buttons"))
        assertEquals(TrackpadButtonMode.CLICKPAD, TrackpadButtonMode.fromPreference(null))
        assertEquals(TrackpadButtonMode.TWO_BUTTONS, TrackpadButtonMode.fromPreference("two_buttons"))
    }
}
