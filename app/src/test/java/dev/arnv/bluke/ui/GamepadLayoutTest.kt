package dev.arnv.bluke.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
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

    private fun setSurface() = compose.setContent {
        SurfaceTheme {
            GamepadView(
                btManager = manager,
                chrome = previewChrome(InputMode.GAMEPAD),
                sharedPrefs = prefs,
                caseBrush = SolidColor(Color.Unspecified),
            )
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
    @Config(qualifiers = "w640dp-h360dp-land-xhdpi", sdk = [36])
    fun noControlsOverlapOnASmallPhone() {
        setSurface()
        assertNoControlsOverlap()
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
