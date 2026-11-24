package dev.arnv.bluke.ui

import androidx.compose.ui.test.hasTestTag

import androidx.compose.ui.test.filterToOne

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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import dev.arnv.bluke.bluetooth.BluetoothKeyboardManager
import org.junit.After
import org.junit.Before
import kotlinx.coroutines.runBlocking
import dev.arnv.bluke.data.LayoutRepository
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

    // The saved layout lives in one DataStore for the whole test process, so each test starts from none.
    @Before
    @After
    fun clearSavedLayouts() = runBlocking {
        val repository = LayoutRepository(context)
        listOf("xbox_series", "playstation_5").forEach { repository.clear(it) }
    }

    @After
    fun closeManager() {
        manager.close()
    }

    private fun setSurface(editMode: Boolean = false, tuneOpen: Boolean = false) = compose.setContent {
        SurfaceTheme {
            GamepadView(
                btManager = manager,
                chrome = previewChrome(InputMode.GAMEPAD),
                sharedPrefs = prefs,
                caseBrush = SolidColor(Color.Unspecified),
                initialEditMode = editMode,
                initialTuneOpen = tuneOpen,
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

    /**
     * The corner of [frame] that [handle] covers (within a pixel), nearest its centre, as outward signs from
     * the frame's centre, or null. A handle is centred on its corner unless the area's edge slides it in.
     */
    private fun cornerOf(frame: Rect, handle: Rect): Offset? = listOf(
        Offset(1f, 1f), Offset(-1f, 1f), Offset(1f, -1f), Offset(-1f, -1f),
    ).filter { handle.inflate(1f).contains(cornerAt(frame, it)) }
        .minByOrNull { (cornerAt(frame, it) - handle.center).getDistance() }

    private fun cornerAt(frame: Rect, signs: Offset) =
        Offset(if (signs.x > 0) frame.right else frame.left, if (signs.y > 0) frame.bottom else frame.top)

    private fun frames() = boundsByTag { it.startsWith("gamepad_frame_") }.mapKeys { it.key.removePrefix("gamepad_frame_") }
    private fun handles() = boundsByTag { it.startsWith("gamepad_resize_") }.mapKeys { it.key.removePrefix("gamepad_resize_") }

    /** In edit mode every resize handle is centred on a corner of its own part's frame, wholly in the controls area. */
    private fun assertHandlesOnTheirCorners() = forEachConsole { id ->
        val area = compose.onNodeWithTag("gamepad_controls_area").fetchSemanticsNode().boundsInRoot
        val frames = frames()
        val handles = handles()
        assertEquals("a frame per part ($id)", 12, frames.size)
        assertEquals("a handle per part ($id)", 12, handles.size)
        handles.forEach { (part, handle) ->
            assertTrue("$part handle $handle inside $area ($id)", area.contains(handle.topLeft) && area.contains(handle.bottomRight - Offset(0.5f, 0.5f)))
            assertTrue("$part handle $handle on a corner of ${frames.getValue(part)} ($id)", cornerOf(frames.getValue(part), handle) != null)
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
    fun resizeHandlesSitOnTheirCornersOnALargePhone() {
        setSurface(editMode = true)
        assertHandlesOnTheirCorners()
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land-xhdpi", sdk = [36])
    fun resizeHandlesSitOnTheirCornersOnA360dpTallPhone() {
        setSurface(editMode = true)
        assertHandlesOnTheirCorners()
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land-xhdpi", sdk = [36])
    fun resizeHandlesSitOnTheirCornersOnASmallPhone() {
        setSurface(editMode = true)
        assertHandlesOnTheirCorners()
    }

    /** Every control's touch box starts at least 12dp under the toolbar, so no reach for a trigger lands on it. */
    private fun assertShoulderRowClearsTheToolbar() = forEachConsole { id ->
        val toolbar = compose.onNodeWithTag("surface_toolbar").fetchSemanticsNode().boundsInRoot
        val clearPx = with(compose.density) { 12.dp.toPx() } - 1f
        controlBounds().forEach { (tag, rect) ->
            assertTrue("$tag starts ${rect.top - toolbar.bottom}px under the toolbar at ${toolbar.bottom} ($id)", rect.top - toolbar.bottom >= clearPx)
        }
    }

    @Test
    fun theShoulderRowClearsTheToolbarOnALargePhone() {
        setSurface()
        assertShoulderRowClearsTheToolbar()
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land-xhdpi", sdk = [36])
    fun theShoulderRowClearsTheToolbarOnA360dpTallPhone() {
        setSurface()
        assertShoulderRowClearsTheToolbar()
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land-xhdpi", sdk = [36])
    fun theShoulderRowClearsTheToolbarOnASmallPhone() {
        setSurface()
        assertShoulderRowClearsTheToolbar()
    }

    @Test
    fun theTriggersResizeFromTheirOuterCornersClearOfTheBumpers() {
        setSurface(editMode = true)
        forEachConsole { id ->
            val frames = frames()
            val handles = handles()
            for ((part, signs) in listOf("left_trigger" to Offset(-1f, 1f), "right_trigger" to Offset(1f, 1f))) {
                val handle = handles.getValue(part)
                assertEquals("$part resizes from its bottom outer corner ($id)", signs, cornerOf(frames.getValue(part), handle))
                frames.filterKeys { it != part }.forEach { (other, frame) ->
                    assertFalse("$part handle $handle covers $other $frame ($id)", handle.overlaps(frame))
                }
            }
        }
    }

    @Test
    fun singleControlTuneSectionsNameTheirControl() {
        setSurface(tuneOpen = true)
        compose.onNodeWithText("Console").assertExists()
        compose.onNodeWithText("Profile").assertExists()
        compose.onNodeWithText("Match the games on the host").assertExists()
        // The section title names the choice; the old second heading is gone.
        compose.onAllNodesWithText("Button layout").assertCountEquals(0)
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

    /** Drags [part]'s handle [distanceDp] away from the part's centre, along its corner; returns where the finger ends, in root px. */
    private fun dragHandleOutward(part: String, distanceDp: Float): Pair<Offset, Offset> {
        val frame = frames().getValue(part)
        val handle = handles().getValue(part)
        val signs = cornerOf(frame, handle) ?: error("$part handle $handle is on no corner of $frame")
        val corner = cornerAt(frame, signs)
        val diagonal = corner - frame.center
        val step = diagonal / diagonal.getDistance() * with(compose.density) { distanceDp.dp.toPx() } / 8f
        // The finger goes down on the corner itself, inside the handle.
        val start = Offset(
            corner.x.coerceIn(handle.left + 1f, handle.right - 1f),
            corner.y.coerceIn(handle.top + 1f, handle.bottom - 1f),
        )
        compose.onNodeWithTag("gamepad_resize_$part").performTouchInput {
            down(start - handle.topLeft)
            repeat(8) { moveBy(step) }
            up()
        }
        compose.waitForIdle()
        return signs to start + step * 8f
    }

    @Test
    fun draggingEachHandleOutwardGrowsItsPartUnderTheFinger() {
        setSurface(editMode = true)
        compose.waitForIdle()
        val tolerance = with(compose.density) { 8.dp.toPx() }
        for (part in frames().keys.sorted()) {
            val before = frames().getValue(part)
            val (signs, finger) = dragHandleOutward(part, 48f)
            val after = frames().getValue(part)
            assertTrue("$part grew: $before -> $after", after.width > before.width && after.height > before.height)
            val corner = cornerAt(after, signs)
            assertTrue("$part corner $corner within 8dp of the finger at $finger", (corner - finger).getDistance() <= tolerance)
            assertTrue("$part handle is still on a corner of its part", cornerOf(after, handles().getValue(part)) != null)
            // Dragging any of its handles back in by as much shrinks it to where it began.
            dragHandleOutward(part, -48f)
            assertEquals("$part shrinks back", before.width, frames().getValue(part).width, 1.5f)
        }
    }

    @Test
    fun resetLayoutIsOffUntilSomethingMoves() {
        setSurface(tuneOpen = true)
        compose.onNodeWithTag("gamepad_tune_reset").assertIsNotEnabled()
    }

    @Test
    fun resetLayoutOffersUndo() {
        setSurface(editMode = true)
        compose.waitForIdle()
        val original = frames().getValue("dpad")
        dragHandleOutward("dpad", 48f)
        val grown = frames().getValue("dpad")
        compose.onNodeWithTag("surface_tune").performClick()
        // Let the sheet finish sliding in, so the tap lands on the button and not the scrim it leaves.
        compose.waitForIdle()
        compose.onNodeWithTag("gamepad_tune_reset").performScrollTo().assertIsEnabled().performClick()
        assertEquals("reset puts the D-pad back", original.width, frames().getValue("dpad").width, 1f)
        compose.onNodeWithText("Layout reset").assertExists()
        compose.onNodeWithTag("gamepad_tune_reset").assertIsNotEnabled()

        compose.onNodeWithText("Undo").performClick()
        compose.waitForIdle()
        assertEquals("undo restores the resize", grown.width, frames().getValue("dpad").width, 1f)
        compose.onNodeWithTag("gamepad_tune_reset").assertIsEnabled()
    }

    @Test
    fun tuneSheetHidesTheControlsFromTalkBack() {
        setSurface(tuneOpen = true)
        compose.onAllNodesWithContentDescription("Left bumper").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Left stick").assertCountEquals(0)
        // The sheet's Close button; the scrim behind the sheet carries the same action.
        compose.onAllNodesWithContentDescription("Close Gamepad").filterToOne(!hasTestTag("surface_tune_scrim")).performClick()
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription("Left bumper").assertCountEquals(1)
        compose.onAllNodesWithContentDescription("Left stick").assertCountEquals(1)
    }

    @Test
    fun editLayoutIsAPlainButton() {
        setSurface()
        compose.onNodeWithTag("edit_layout_toggle_btn")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ToggleableState))
    }
}
