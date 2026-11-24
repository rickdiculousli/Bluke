package dev.arnv.bluke.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.runtime.CompositionLocalProvider
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
import dev.arnv.bluke.bluetooth.BluetoothState
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
 * at least eight characters where there's room, and Tune stays on screen.
 */
@RunWith(RobolectricTestRunner::class)
// Native graphics, so text measures with real fonts.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class SurfaceToolbarFitTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("toolbar-fit", Context.MODE_PRIVATE)

    private val hosts = listOf(
        SurfaceHost(SurfaceHostStatus.Connected, "Rick's MacBook Pro (Work, 14-inch, 2024)"),
        SurfaceHost(SurfaceHostStatus.Connecting, "Desk PC"),
        SurfaceHost(SurfaceHostStatus.Offline, "Desk PC", onReconnect = {}),
        SurfaceHost(SurfaceHostStatus.Offline, null),
        SurfaceHost(SurfaceHostStatus.Blocked, null, reason = "Bluetooth off"),
        SurfaceHost(SurfaceHostStatus.Blocked, null, reason = "Bluetooth off", onFix = {}, fixLabel = "Turn on", fixClickLabel = "Turn on Bluetooth"),
        SurfaceHost(SurfaceHostStatus.Blocked, null, reason = blockedReason(BluetoothState.ProfileNotSupported)),
    )

    /** A portrait phone: the keyboard's toolbar used to give the host chip no width at all. */
    @Test
    @Config(qualifiers = "w411dp-h914dp-port-420dpi")
    fun aPortraitPhoneShowsEveryStatus() {
        val layouts = showToolbars()
        hosts.indices.forEach { assertStatusWhole(it, layouts[it]) }
        assertChipsAndTuneOnScreen()
    }

    /** A small landscape phone: names used to collapse to two letters beside "Reconnect" and the LEDs. */
    @Test
    @Config(qualifiers = "w640dp-h360dp-land-xhdpi")
    fun aSmallLandscapePhoneKeepsTheName() {
        val layouts = showToolbars()
        hosts.indices.forEach { assertStatusWhole(it, layouts[it]) }
        assertChipsAndTuneOnScreen()

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

    private fun showToolbars(): List<Map<String, TextLayoutResult>> {
        val layouts = hosts.map { mutableMapOf<String, TextLayoutResult>() }
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, 1.3f)) {
                SurfaceTheme {
                    val haptics = rememberSurfaceHaptics(prefs)
                    Column {
                        hosts.forEachIndexed { index, host ->
                            CompositionLocalProvider(LocalHostChipTextLayout provides { role, layout -> layouts[index][role] = layout }) {
                                SurfaceToolbar(previewChrome(InputMode.KEYBOARD, host), haptics, onTune = {}) {
                                    SurfaceToolbarLeds(listOf("CAPS" to (index == 0), "NUM" to true, "SCR" to false), onToggle = {})
                                    SurfaceToolbarIconButton(Icons.AutoMirrored.Filled.VolumeUp, "Key sound", onClick = {}, selected = true)
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        return layouts
    }

    private fun assertStatusWhole(index: Int, layouts: Map<String, TextLayoutResult>) {
        val content = hostChipContent(hosts[index], compact = true)
        // A Connected host's name is its status words; every other status has words of its own.
        val role = if (content.status != null) "status" else "name"
        val layout = layouts[role] ?: error("row $index shows no $role")
        if (role == "status") {
            val whole = !layout.isLineEllipsized(0) && !layout.hasVisualOverflow &&
                layout.size.width >= layout.multiParagraph.intrinsics.maxIntrinsicWidth - 0.5f
            assertTrue("row $index's status \"${content.status}\" is whole: ${layout.size.width}px", whole)
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
