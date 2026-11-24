package dev.arnv.bluke.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.ViewSidebar
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The shared toolbar on narrow windows at font scale 1.3, with the keyboard's controls (three LEDs
 * and a sound toggle): the host chip always shows its dot and whole status words, a long name keeps
 * at least eight characters where there's room, and Tune stays on screen. At font scale 1.0 and 1.3
 * every LED shown carries a visible label and no two targets sit closer than 8dp.
 */
@RunWith(RobolectricTestRunner::class)
// Native graphics, so text measures with real fonts.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class SurfaceToolbarFitTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("toolbar-fit", Context.MODE_PRIVATE)

    private val fontScale = mutableFloatStateOf(1.3f)

    private val hosts = listOf(
        SurfaceHost(SurfaceHostStatus.Connected, "Rick's MacBook Pro (Work, 14-inch, 2024)"),
        SurfaceHost(SurfaceHostStatus.Connecting, "Desk PC"),
        SurfaceHost(SurfaceHostStatus.Offline, "Desk PC", onReconnect = {}),
        SurfaceHost(SurfaceHostStatus.Offline, null),
        SurfaceHost(SurfaceHostStatus.Blocked, null, reason = "Bluetooth off"),
        SurfaceHost(SurfaceHostStatus.Blocked, null, reason = "Bluetooth off", onFix = {}, fixLabel = "Turn on", fixClickLabel = "Turn on Bluetooth"),
        SurfaceHost(SurfaceHostStatus.Blocked, null, reason = BlockReason.NO_HID_ROLE.chipReason),
    )

    /** A portrait phone: the keyboard's toolbar used to give the host chip no width at all. */
    @Test
    @Config(qualifiers = "w411dp-h914dp-port-420dpi")
    fun aPortraitPhoneShowsEveryStatus() {
        val layouts = showToolbars()
        hosts.indices.forEach { assertStatusWhole(it, layouts[it]) }
        assertChipsAndTuneOnScreen()
        assertLedsLabelledAndTargetsSpacedAtBothScales()
    }

    /** A small landscape phone: names used to collapse to two letters beside "Reconnect" and the LEDs. */
    @Test
    @Config(qualifiers = "w640dp-h360dp-land-xhdpi")
    fun aSmallLandscapePhoneKeepsTheName() {
        val layouts = showToolbars()
        hosts.indices.forEach { assertStatusWhole(it, layouts[it]) }
        assertChipsAndTuneOnScreen()
        assertLedsLabelledAndTargetsSpacedAtBothScales()

        val long = layouts[0]["name"] ?: error("Connected shows no name")
        assertTrue(
            "a long name keeps at least $HostNameFloorChars characters",
            long.size.width >= long.getBoundingBox(HostNameFloorChars - 1).right + long.getBoundingBox(HostNameFloorChars - 1).width / 2,
        )
        for (index in listOf(1, 2)) {
            val short = layouts[index]["name"]!!
            assertFalse("\"Desk PC\" shows whole in row $index", short.isLineEllipsized(0))
        }
    }

    /** A large landscape phone: a long name used to stop at 180dp with room to spare beside it. */
    @Test
    @Config(qualifiers = "w914dp-h411dp-land-420dpi")
    fun aWideToolbarGivesTheNameItsRoom() {
        val layouts = showToolbars()
        hosts.indices.forEach { assertStatusWhole(it, layouts[it]) }
        assertChipsAndTuneOnScreen()

        val long = layouts[0]["name"] ?: error("Connected shows no name")
        // Characters an ellipsis hides lay out with no width. A 180dp cap showed about 18; the
        // room that's free here holds 30 and more.
        val shown = (0 until long.layoutInput.text.length).count { long.getBoundingBox(it).width > 0f }
        assertTrue("a 40-character name shows at least 30 characters, not $shown", shown >= 30)
        assertLedsLabelledAndTargetsSpacedAtBothScales()
    }

    /** A Blocked chip with a fix is a button that says what it does; one without stays a status. */
    @Test
    @Config(qualifiers = "w411dp-h914dp-port-420dpi")
    fun aBlockedChipWithAFixIsAButton() {
        showToolbars()
        val chips = compose.onAllNodesWithTag("surface_host").fetchSemanticsNodes()
        val fix = chips[hosts.indexOfFirst { it.onFix != null }].config
        assertEquals(Role.Button, fix.getOrNull(SemanticsProperties.Role))
        assertEquals("Turn on Bluetooth", fix.getOrNull(SemanticsActions.OnClick)?.label)
        val noFix = chips[hosts.indexOfFirst { it.status == SurfaceHostStatus.Blocked && it.onFix == null }].config
        assertNull(noFix.getOrNull(SemanticsActions.OnClick))
    }

    /** The touchpad's two labelled toggles hold the same rules at every width, at both font scales. */
    @Test
    @Config(qualifiers = "w411dp-h914dp-port-420dpi")
    fun touchpadTogglesFitAPortraitPhone() = assertTouchpadTogglesFit()

    @Test
    @Config(qualifiers = "w640dp-h360dp-land-xhdpi")
    fun touchpadTogglesFitASmallLandscapePhone() = assertTouchpadTogglesFit()

    @Test
    @Config(qualifiers = "w914dp-h411dp-land-420dpi")
    fun touchpadTogglesFitAWideToolbar() = assertTouchpadTogglesFit()

    private fun assertTouchpadTogglesFit() {
        val layouts = showToolbars(InputMode.TOUCHPAD) {
            SurfaceToolbarToggles(
                listOf(
                    SurfaceToolbarToggle("Numpad", Icons.Default.Dialpad, on = true, onToggle = {}, testTag = "fit_toggle"),
                    SurfaceToolbarToggle("Modifiers", Icons.Default.ViewSidebar, on = false, onToggle = {}, testTag = "fit_toggle"),
                ),
            )
        }
        hosts.indices.forEach { assertStatusWhole(it, layouts[it]) }
        assertChipsAndTuneOnScreen()
        for (scale in listOf(1.3f, 1f)) {
            fontScale.floatValue = scale
            compose.waitForIdle()
            assertTargetsSpaced(scale)
            // Each toggle shown says what it is: its words, or its name for TalkBack beside the icon.
            val toggles = compose.onAllNodesWithTag("fit_toggle").fetchSemanticsNodes().filter { it.layoutInfo.isPlaced }
            // A row whose status words need the room drops its controls whole (both live in Tune too), never one of the pair.
            assertTrue("toggles show in pairs: ${toggles.size}", toggles.isNotEmpty() && toggles.size % 2 == 0)
            toggles.forEach { toggle ->
                val name = toggle.config.getOrNull(SemanticsProperties.Text)?.joinToString("")
                    ?: toggle.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString("")
                assertTrue("a toggle at $scale is named: ${toggle.config}", !name.isNullOrBlank())
                assertEquals(Role.Switch, toggle.config.getOrNull(SemanticsProperties.Role))
            }
        }
    }

    private fun showToolbars(
        mode: InputMode = InputMode.KEYBOARD,
        controls: @Composable RowScope.(row: Int) -> Unit = { row ->
            SurfaceToolbarLeds(listOf("CAPS" to (row == 0), "NUM" to true, "SCR" to false), onToggle = {})
            SurfaceToolbarIconButton(Icons.AutoMirrored.Filled.VolumeUp, "Key sound", onClick = {}, selected = true)
        },
    ): List<Map<String, TextLayoutResult>> {
        val layouts = hosts.map { mutableMapOf<String, TextLayoutResult>() }
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale.floatValue)) {
                SurfaceTheme {
                    val haptics = rememberSurfaceHaptics(prefs)
                    Column {
                        hosts.forEachIndexed { index, host ->
                            CompositionLocalProvider(LocalHostChipTextLayout provides { role, layout -> layouts[index][role] = layout }) {
                                SurfaceToolbar(previewChrome(mode, host), haptics, onTune = {}) { controls(index) }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        return layouts
    }

    /** In the tightest room that keeps unlit LEDs, each shows its initial and TalkBack still hears its name. */
    @Test
    @Config(qualifiers = "w411dp-h914dp-port-420dpi")
    fun unlitLedsInATightToolbarKeepAnInitial() {
        compose.setContent {
            SurfaceTheme {
                CompositionLocalProvider(LocalSurfaceToolbarRoom provides SurfaceToolbarRoom.UnlitInitials) {
                    SurfaceToolbarLeds(listOf("CAPS" to false, "NUM" to true, "SCR" to false), onToggle = {})
                }
            }
        }
        val leds = compose.onAllNodesWithTag("surface_led").fetchSemanticsNodes()
        assertEquals(
            listOf("C", "NUM", "S"),
            leds.map { it.config.getOrNull(SemanticsProperties.Text)?.joinToString("") },
        )
        assertEquals(
            listOf("CAPS", null, "SCR"),
            leds.map { it.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString("") },
        )
        assertLedsLabelled(1f)
    }

    /** The current font scale first, then the other one: the layout must hold at both. */
    private fun assertLedsLabelledAndTargetsSpacedAtBothScales() {
        for (scale in listOf(fontScale.floatValue, if (fontScale.floatValue == 1.3f) 1f else 1.3f)) {
            fontScale.floatValue = scale
            compose.waitForIdle()
            assertLedsLabelled(scale)
            assertTargetsSpaced(scale)
        }
    }

    /** An LED is a live toggle on the host: one shown with no visible label is a button nobody can name. */
    private fun assertLedsLabelled(scale: Float) {
        val leds = compose.onAllNodesWithTag("surface_led").fetchSemanticsNodes().filter { it.layoutInfo.isPlaced }
        leds.forEach { led ->
            val text = led.config.getOrNull(SemanticsProperties.Text)?.joinToString("").orEmpty()
            assertTrue("an LED at $scale shows a label, not a bare dot: ${led.config}", text.isNotBlank())
        }
    }

    /** Each target's 48dp touch area, then the space to the next one along the row: never under 8dp. */
    private fun assertTargetsSpaced(scale: Float) {
        val density = compose.density
        val min = with(density) { 48.dp.toPx() }
        val gap = with(density) { 8.dp.toPx() }
        compose.onAllNodesWithTag("surface_toolbar", useUnmergedTree = true).fetchSemanticsNodes().forEachIndexed { row, toolbar ->
            val targets = toolbar.descendants()
                .filter { it.layoutInfo.isPlaced && it.config.contains(SemanticsActions.OnClick) }
                .map { node ->
                    val bounds = node.boundsInRoot
                    val width = maxOf(bounds.width, min)
                    bounds.center.x - width / 2 to bounds.center.x + width / 2
                }
                .sortedBy { it.first }
                .toList()
            assertTrue("row $row at $scale has its targets: ${targets.size}", targets.size >= 3)
            targets.zipWithNext().forEach { (a, b) ->
                assertTrue(
                    "row $row at $scale: targets ${(b.first - a.second) / density.density}dp apart, under 8dp",
                    b.first - a.second >= gap - 0.5f,
                )
            }
        }
    }

    private fun SemanticsNode.descendants(): Sequence<SemanticsNode> =
        sequence { children.forEach { yield(it); yieldAll(it.descendants()) } }

    private fun assertStatusWhole(index: Int, layouts: Map<String, TextLayoutResult>) {
        // A name shown at all shows some of itself, never a lone ellipsis.
        layouts["name"]?.let { name ->
            val visible = (0 until name.layoutInput.text.length).count { name.getBoundingBox(it).width > 0f }
            assertTrue("row $index's name shows at least two characters, not $visible", visible >= 2)
        }
        val content = hostChipContent(hosts[index], compact = true)
        // A Connected host's name is its status words; every other status has words of its own.
        val role = if (content.status != null) "status" else "name"
        val layout = layouts[role] ?: error("row $index shows no $role")
        if (role == "status") {
            val whole = !layout.isLineEllipsized(0) && !layout.hasVisualOverflow &&
                layout.size.width >= layout.multiParagraph.intrinsics.maxIntrinsicWidth - 0.5f
            assertTrue("row $index's status \"${content.status}\" is whole: ${layout.size.width}px of ${layout.multiParagraph.intrinsics.maxIntrinsicWidth}px", whole)
        }
    }

    private fun assertChipsAndTuneOnScreen() {
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val density = compose.density
        val chips = compose.onAllNodesWithTag("surface_host").fetchSemanticsNodes()
        val tunes = compose.onAllNodesWithTag("surface_tune").fetchSemanticsNodes()
        assertTrue("one chip and one Tune per row: ${chips.size} chips, ${tunes.size} Tunes", chips.size == hosts.size && tunes.size == hosts.size)
        chips.forEachIndexed { index, chip ->
            val bounds = chip.boundsInRoot
            assertTrue("row $index's chip keeps its dot: ${bounds.width}px", bounds.width >= with(density) { 48.dp.toPx() } - 0.5f)
            assertTrue("row $index's chip is on screen", bounds.left >= root.left && bounds.right <= root.right)
        }
        tunes.forEachIndexed { index, tune ->
            val bounds = tune.boundsInRoot
            assertTrue("row $index's Tune is whole: $bounds", bounds.left >= root.left && bounds.right <= root.right + 0.5f)
            // The button draws at 40dp; its 48dp touch target reaches past it.
            assertTrue("row $index's Tune is whole: ${bounds.width}px", bounds.width >= with(density) { 40.dp.toPx() } - 0.5f)
            val chip = chips[index].boundsInRoot
            assertTrue("row $index's chip doesn't run under the controls", chip.right <= bounds.left)
        }
    }
}
