package dev.arnv.bluke.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import dev.arnv.bluke.bluetooth.BluetoothKeyboardManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Where the gamepad's controls land in windows of different shapes, through the real surface. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w914dp-h411dp-land-420dpi", sdk = [36])
class GamepadLayoutTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = BluetoothKeyboardManager(context)
    private val prefs: SharedPreferences =
        context.getSharedPreferences("gamepad-layout-test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }

    @After
    fun closeManager() {
        manager.close()
    }

    private fun setSurface(editMode: Boolean = false) = compose.setContent {
        SurfaceTheme {
            GamepadView(
                btManager = manager,
                chrome = previewChrome(InputMode.GAMEPAD),
                sharedPrefs = prefs,
                caseBrush = SolidColor(Color.Unspecified),
                initialEditMode = editMode,
            )
        }
    }

    private fun boundsByTag(matches: (String) -> Boolean): Map<String, Rect> = compose.onAllNodes(
        SemanticsMatcher("tagged") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.let(matches) ?: false
        },
        useUnmergedTree = true,
    ).fetchSemanticsNodes().associate { it.config[SemanticsProperties.TestTag] to it.boundsInRoot }

    private fun forEachConsole(check: (String) -> Unit) {
        for (id in listOf("xbox_series", "playstation_5")) {
            prefs.edit().putString(GAMEPAD_CONSOLE_PREFERENCE, id).commit()
            compose.waitForIdle()
            check(id)
        }
    }

    /** Every part, and every target inside one that is a single button, is at least 48dp each way. */
    private fun assertTargetsAtLeast48dp() = forEachConsole { id ->
        val minPx = with(compose.density) { 48.dp.toPx() } - 0.5f
        val targets = boundsByTag {
            it.startsWith("gamepad_part_") || it.startsWith("gamepad_hold_") ||
                it.startsWith("gamepad_button_") || it == "gamepad_guide"
        }
        assertEquals("parts, two holds, four shoulders, three centre buttons, Guide ($id)", 12 + 2 + 4 + 3 + 1, targets.size)
        targets.forEach { (tag, rect) ->
            assertTrue("$tag is ${rect.width}x${rect.height}px, under 48dp ($id)", rect.width >= minPx && rect.height >= minPx)
        }
    }

    /** In edit mode every resize handle lies wholly in the controls area and on no other part's move frame. */
    private fun assertHandlesClear() = forEachConsole { id ->
        val area = compose.onNodeWithTag("gamepad_controls_area").fetchSemanticsNode().boundsInRoot
        val frames = boundsByTag { it.startsWith("gamepad_frame_") }.mapKeys { it.key.removePrefix("gamepad_frame_") }
        val handles = boundsByTag { it.startsWith("gamepad_resize_") }.mapKeys { it.key.removePrefix("gamepad_resize_") }
        assertEquals("a frame per part ($id)", 12, frames.size)
        assertEquals("a handle per part ($id)", 12, handles.size)
        handles.forEach { (part, handle) ->
            assertTrue("$part handle $handle inside $area ($id)", area.contains(handle.topLeft) && area.contains(handle.bottomRight - Offset(0.5f, 0.5f)))
            frames.filterKeys { it != part }.forEach { (other, frame) ->
                assertFalse("$part handle $handle on $other frame $frame ($id)", handle.deflate(0.5f).overlaps(frame))
            }
        }
    }

    /** Every control's touch box (each part, and the profile chip), by tag, in root pixels. */
    private fun controlBounds(): Map<String, Rect> = compose.onAllNodes(
        SemanticsMatcher("is a control") { node ->
            val tag = node.config.getOrNull(SemanticsProperties.TestTag) ?: return@SemanticsMatcher false
            tag.startsWith("gamepad_part_") || tag == "gamepad_profile"
        },
        useUnmergedTree = true,
    ).fetchSemanticsNodes().associate { it.config[SemanticsProperties.TestTag] to it.boundsInRoot }

    private fun assertNoControlsOverlap() {
        for (id in listOf("xbox_series", "playstation_5")) {
            prefs.edit().putString(GAMEPAD_CONSOLE_PREFERENCE, id).commit()
            compose.waitForIdle()
            val bounds = controlBounds()
            assertEquals("every part and the profile chip are on screen ($id)", 13, bounds.size)
            val area = compose.onNodeWithTag("gamepad_view_root").fetchSemanticsNode().boundsInRoot
            val entries = bounds.entries.toList()
            entries.forEachIndexed { i, (tag, rect) ->
                assertTrue("$tag $rect inside $area ($id)", area.contains(rect.topLeft) && area.contains(rect.bottomRight))
                entries.drop(i + 1).forEach { (other, otherRect) ->
                    assertFalse("$tag $rect overlaps $other $otherRect ($id)", rect.overlaps(otherRect))
                }
            }
        }
    }

    @Test
    fun noControlsOverlapOnALargePhone() {
        setSurface()
        assertNoControlsOverlap()
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land-xhdpi", sdk = [36])
    fun noControlsOverlapOnA360dpTallPhone() {
        setSurface()
        assertNoControlsOverlap()
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land-xhdpi", sdk = [36])
    fun noControlsOverlapOnASmallPhone() {
        setSurface()
        assertNoControlsOverlap()
    }

    @Test
    fun targetsAreAtLeast48dpOnALargePhone() {
        setSurface()
        assertTargetsAtLeast48dp()
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land-xhdpi", sdk = [36])
    fun aPhone360dpTallGetsTheFullSizeLayout() {
        setSurface()
        assertTargetsAtLeast48dp()
        val trigger = compose.onNodeWithTag("gamepad_part_left_trigger").fetchSemanticsNode().boundsInRoot
        assertEquals("the trigger is drawn at its full 112dp", with(compose.density) { 112.dp.toPx() }, trigger.width, 1f)
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land-xhdpi", sdk = [36])
    fun targetsAreAtLeast48dpOnASmallPhone() {
        setSurface()
        assertTargetsAtLeast48dp()
    }

    @Test
    fun resizeHandlesAreClearOnALargePhone() {
        setSurface(editMode = true)
        assertHandlesClear()
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land-xhdpi", sdk = [36])
    fun resizeHandlesAreClearOnA360dpTallPhone() {
        setSurface(editMode = true)
        assertHandlesClear()
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land-xhdpi", sdk = [36])
    fun resizeHandlesAreClearOnASmallPhone() {
        setSurface(editMode = true)
        assertHandlesClear()
    }

    @Test
    fun backFinishesEditingInsteadOfClosing() {
        setSurface(editMode = true)
        compose.onNodeWithTag("gamepad_edit_done").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("gamepad_edit_done").assertDoesNotExist()
        compose.onNodeWithTag("edit_layout_toggle_btn").assertIsDisplayed()
        assertFalse("the surface stays open", compose.activity.isFinishing)
    }

    @Test
    @Config(qualifiers = "w411dp-h914dp-port-420dpi", sdk = [36])
    fun aPortraitWindowAsksToTurnThePhone() {
        setSurface()
        compose.onNodeWithTag("gamepad_turn_sideways").assertIsDisplayed()
        assertTrue(controlBounds().isEmpty())
        compose.onNodeWithTag("console_selector_pill").assertIsDisplayed()
    }

    @Test
    fun editLayoutIsAPlainButton() {
        setSurface()
        compose.onNodeWithTag("edit_layout_toggle_btn")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ToggleableState))
    }
}
