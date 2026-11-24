package dev.arnv.bluke.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.arnv.bluke.mirror.MirrorNotice
import dev.arnv.bluke.mirror.MirrorPhase
import dev.arnv.bluke.mirror.MirrorTouchMode
import dev.arnv.bluke.mirror.MirrorTouchPolicy
import dev.arnv.bluke.mirror.MirrorUiState
import dev.arnv.bluke.mirror.update
import dev.arnv.bluke.ui.theme.MyApplicationTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Mirror surface in each state it can show, in the landscape the input surfaces run in, on a
 * 360dp-high phone, in the dark scheme the surface host wraps every input surface in, at font scale
 * 1.0, 1.3 and 2.0, with long and emoji computer names; the left-hand overlay over a light and a
 * dark picture, in Direct and Hover, with the key held, with 0, 3 and 8 hotkeys, with them disabled,
 * and the hotkey editor, at 1.0 and 1.3; plus the touch ring on the first frame of a press.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w800dp-h360dp-land-xhdpi", sdk = [36])
class MirrorScreenshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun staticColors() {
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit(commit = true) {
            putBoolean("dynamic_color", false)
        }
    }

    private val address = MirrorUiState(address = "192.168.1.23")

    private val states = linkedMapOf(
        "unpaired" to address.copy(phase = MirrorPhase.Waiting(pairedName = null)),
        "waiting" to address.copy(phase = MirrorPhase.Waiting("Rick's MacBook Pro"), pairedName = "Rick's MacBook Pro"),
        "pairing" to address.copy(phase = MirrorPhase.Pairing("482913", "Rick's MacBook Pro", attemptsLeft = 3)),
        "pairing-last-try" to address.copy(
            phase = MirrorPhase.Pairing("057221", "Rick's MacBook Pro", attemptsLeft = 1),
            notice = MirrorNotice.WrongCode(attemptsLeft = 1),
        ),
        "locked" to address.copy(phase = MirrorPhase.PairingLocked),
        "no-wifi" to MirrorUiState(phase = MirrorPhase.NoWifi),
        "connecting" to address.copy(
            phase = MirrorPhase.Connected("Rick's MacBook Pro", "Built-in Retina Display", hasFrame = false),
            pairedName = "Rick's MacBook Pro",
        ),
        "connected-no-picture" to address.copy(
            phase = MirrorPhase.Connected("Rick's MacBook Pro", null, hasFrame = false, pictureOverdue = true),
            pairedName = "Rick's MacBook Pro",
            latencyMs = 6,
        ),
        "streaming-first-picture" to address.copy(
            phase = MirrorPhase.Connected(LONG_NAME, "Built-in Retina Display", hasFrame = true),
            pairedName = LONG_NAME,
            fps = 20,
            latencyMs = 6,
            notice = MirrorNotice.Streaming(LONG_NAME),
        ),
        "streaming" to address.copy(
            phase = MirrorPhase.Connected("Rick's MacBook Pro", "Built-in Retina Display", hasFrame = true),
            pairedName = "Rick's MacBook Pro",
            fps = 20,
            latencyMs = 6,
        ),
        "streaming-hover" to address.copy(
            phase = MirrorPhase.Connected("Rick's MacBook Pro", "Built-in Retina Display", hasFrame = true),
            pairedName = "Rick's MacBook Pro",
            fps = 14,
            latencyMs = 9,
            touchMode = MirrorTouchMode.HOVER,
            notice = MirrorNotice.Paired("Rick's MacBook Pro"),
        ),
        "streaming-idle" to address.copy(
            phase = MirrorPhase.Connected("Rick's MacBook Pro", "Built-in Retina Display", hasFrame = true),
            pairedName = "Rick's MacBook Pro",
            fps = 0,
            latencyMs = 6,
        ),
        "stale-link" to address.copy(
            phase = MirrorPhase.Connected("Rick's MacBook Pro", "Built-in Retina Display", hasFrame = true),
            pairedName = "Rick's MacBook Pro",
            fps = 0,
            latencyMs = 6,
            linkQuiet = true,
        ),
        "pairing-while-streaming" to address.copy(
            phase = MirrorPhase.Connected(
                "Rick's MacBook Pro",
                "Built-in Retina Display",
                hasFrame = true,
                pairing = MirrorPhase.Pairing("590317", "Studio iMac", attemptsLeft = 3),
            ),
            pairedName = "Rick's MacBook Pro",
            fps = 18,
            latencyMs = 7,
        ),
        "pairing-while-streaming-code-shown" to address.copy(
            phase = MirrorPhase.Connected(
                "Rick's MacBook Pro",
                "Built-in Retina Display",
                hasFrame = true,
                pairing = MirrorPhase.Pairing("590317", "Studio iMac", attemptsLeft = 3),
            ),
            pairedName = "Rick's MacBook Pro",
            fps = 18,
            latencyMs = 7,
        ),
        "declined" to address.copy(
            phase = MirrorPhase.Waiting("Rick's MacBook Pro"),
            pairedName = "Rick's MacBook Pro",
            notice = MirrorNotice.Declined("Studio iMac"),
        ),
        "forget-pending" to address.copy(
            phase = MirrorPhase.Connected("Rick's MacBook Pro", "Built-in Retina Display", hasFrame = true),
            pairedName = "Rick's MacBook Pro",
            fps = 20,
            latencyMs = 6,
        ),
        "link-lost" to address.copy(
            phase = MirrorPhase.Waiting("Rick's MacBook Pro"),
            pairedName = "Rick's MacBook Pro",
        ),
        "off" to MirrorUiState(),
        "failed" to address.copy(phase = MirrorPhase.Failed("Couldn't open a network port (Address already in use).")),
        "waiting-long-name" to address.copy(phase = MirrorPhase.Waiting(LONG_NAME), pairedName = LONG_NAME),
        "streaming-long-name" to address.copy(
            phase = MirrorPhase.Connected(LONG_NAME, "Built-in Retina Display", hasFrame = true),
            pairedName = LONG_NAME,
            fps = 20,
            latencyMs = 128,
        ),
        "forget-pending-long-name" to address.copy(
            phase = MirrorPhase.Connected(LONG_NAME, "Built-in Retina Display", hasFrame = true),
            pairedName = LONG_NAME,
            fps = 20,
            latencyMs = 6,
        ),
        "pairing-emoji-name" to address.copy(phase = MirrorPhase.Pairing("730146", EMOJI_NAME, attemptsLeft = 2)),
        "streaming-emoji-name" to address.copy(
            phase = MirrorPhase.Connected(EMOJI_NAME, "DELL U2720Q", hasFrame = true),
            pairedName = EMOJI_NAME,
            fps = 60,
            latencyMs = 3,
            touchMode = MirrorTouchMode.HOVER,
        ),
    )

    private val streamingHover = address.copy(
        phase = MirrorPhase.Connected("Rick's MacBook Pro", "Built-in Retina Display", hasFrame = true),
        pairedName = "Rick's MacBook Pro",
        fps = 20,
        latencyMs = 6,
        touchMode = MirrorTouchMode.HOVER,
    )
    private val three = listOf(
        MirrorHotkey(KeyboardLayouts.KEY_E, label = "End turn"),
        MirrorHotkey(KeyboardLayouts.KEY_1),
        MirrorHotkey(KeyboardLayouts.KEY_TAB, HotkeyModifiers.SHIFT),
    )
    private val eight = three + listOf(
        MirrorHotkey(KeyboardLayouts.KEY_2),
        MirrorHotkey(KeyboardLayouts.KEY_3),
        MirrorHotkey(KeyboardLayouts.KEY_ESC),
        MirrorHotkey(KeyboardLayouts.KEY_F5, HotkeyModifiers.CTRL or HotkeyModifiers.SHIFT),
        MirrorHotkey(KeyboardLayouts.KEY_SPACE),
    )
    private fun ready(list: List<MirrorHotkey>) = MirrorHotkeysUi(list, enabled = true, hostName = "Rick's MacBook Pro")

    /** The overlay's own cases, at font scale 1.0 and 1.3. */
    private val overlay = linkedMapOf(
        "overlay-hover-3-dark-picture" to Case(streamingHover, ready(three)),
        "overlay-hover-3-light-picture" to Case(streamingHover, ready(three), lightPicture = true),
        "overlay-direct-3-light-picture" to Case(streamingHover.copy(touchMode = MirrorTouchMode.DIRECT), ready(three), lightPicture = true),
        "overlay-hover-key-held" to Case(streamingHover.copy(keyHeld = true), ready(three)),
        "overlay-hover-key-held-light-picture" to Case(streamingHover.copy(keyHeld = true), ready(three), lightPicture = true),
        "overlay-hover-0" to Case(streamingHover, ready(emptyList())),
        "overlay-hover-8" to Case(streamingHover, ready(eight)),
        "overlay-direct-8" to Case(streamingHover.copy(touchMode = MirrorTouchMode.DIRECT), ready(eight)),
        "overlay-hotkeys-disabled" to Case(streamingHover, MirrorHotkeysUi(three, enabled = false, hostName = "Rick's MacBook Pro")),
        "overlay-waiting-3" to Case(
            address.copy(phase = MirrorPhase.Waiting("Rick's MacBook Pro"), pairedName = "Rick's MacBook Pro", touchMode = MirrorTouchMode.HOVER),
            ready(three),
        ),
        "editor-3" to Case(streamingHover, ready(three), editing = true),
        "editor-0" to Case(streamingHover, ready(emptyList()), editing = true),
        "editor-8" to Case(streamingHover, ready(eight), editing = true),
    )

    /** One overlay case: the state, its hotkeys, which picture, and whether the editor is open. */
    private data class Case(
        val state: MirrorUiState,
        val hotkeys: MirrorHotkeysUi = MirrorHotkeysUi(),
        val lightPicture: Boolean = false,
        val editing: Boolean = false,
    )

    private companion object {
        const val SETTLE_MS = 1_000L
        const val LONG_NAME = "Rick's MacBook Pro (Work) in the upstairs office"
        const val EMOJI_NAME = "🎮 Gaming PC 🖥️"
    }

    private data class Shot(val name: String, val case: Case, val fontScale: Float)

    private fun shots(fontScale: Float, withOverlay: Boolean): List<Shot> =
        states.map { (name, state) -> Shot(name, Case(state), fontScale) } +
            if (withOverlay) overlay.map { (name, case) -> Shot(name, case, fontScale) } else emptyList()

    @Test
    fun fontScale1() = captureAll(shots(1f, withOverlay = true))

    @Test
    fun fontScale1_3() = captureAll(shots(1.3f, withOverlay = true))

    @Test
    fun fontScale2() = captureAll(shots(2f, withOverlay = false))

    /** The first frame of a press: filled ring in Direct (the button is held), hollow in Hover. */
    @Test
    fun touchRingDirect() = captureFirstFrameOfPress("streaming", "mirror-touch-direct-dark-fs1-land.png")

    @Test
    fun touchRingHover() = captureFirstFrameOfPress("streaming-hover", "mirror-touch-hover-dark-fs1-land.png")

    /** One composition per test (the rule allows one setContent), switched shot by shot. */
    private fun captureAll(shots: List<Shot>) {
        val current = mutableStateOf(shots.first())
        // The test rule cancels infinite animations (the spinners) while its clock runs on its own;
        // stepping it by hand lets them run, so each shot shows the arc a person sees, not frame 0.
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            val shot = current.value
            HostSurfaceTheme(shot.fontScale) {
                key(shot) { Surface(shot.name, shot.case) }
            }
        }
        shots.forEach { shot ->
            current.value = shot
            // With the clock stepped by hand, the change must be announced before the next frame reads it.
            Snapshot.sendApplyNotifications()
            // Long enough for the cards' springs to settle and the spinners to open their arc.
            composeTestRule.mainClock.advanceTimeBy(SETTLE_MS)
            composeTestRule.waitForIdle()
            val scale = if (shot.fontScale == 1f) "1" else if (shot.fontScale == 2f) "2" else shot.fontScale.toString()
            composeTestRule.onRoot().captureRoboImage(
                filePath = "src/test/screenshots/mirror/mirror-${shot.name}-dark-fs$scale-land.png",
            )
        }
    }

    private fun captureFirstFrameOfPress(stateName: String, fileName: String) {
        val state = states.getValue(stateName).copy(notice = null)
        var root: android.view.View? = null
        composeTestRule.setContent {
            root = LocalView.current
            HostSurfaceTheme(fontScale = 1f) { Surface(stateName, Case(state)) }
        }
        composeTestRule.waitForIdle()
        // Robolectric draws only when asked, and the touch layer learns the image's place from a draw.
        composeTestRule.runOnIdle {
            val view = checkNotNull(root)
            view.draw(Canvas(Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)))
        }
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.autoAdvance = false
        // Over the second card of the hand, where a player would reach to drag it.
        composeTestRule.onNodeWithTag("mirror_image").performTouchInput {
            down(Offset(width * 0.42f, height * 0.85f))
        }
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.waitForIdle()
        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/mirror/$fileName")
    }

    @Composable
    private fun Surface(name: String, case: Case) {
        val state = case.state
        // The real touch policy, so the ring the press draws is the one the app would.
        val policy = androidx.compose.runtime.remember(state.touchMode) { MirrorTouchPolicy(mode = state.touchMode, tapSlopPx = 24f) }
        val hasPicture = (state.phase as? MirrorPhase.Connected)?.hasFrame == true || name == "link-lost" || name.startsWith("overlay-waiting")
        MirrorSurface(
            state = state,
            // A picture once one was decoded this session, or one left from before the link dropped.
            frames = when {
                !hasPicture -> withoutPicture
                case.lightPicture -> withLightPicture
                else -> withPicture
            },
            hotkeys = case.hotkeys,
            editingHotkeys = case.editing,
            onClose = {},
            onNextMode = {},
            onConfigureModes = {},
            onTouchModeChange = {},
            onForgetCompanion = {},
            onNoticeShown = {},
            onTouch = { policy.update(it) },
            forgetPending = name.startsWith("forget-pending"),
            shownPairingCode = if (name.endsWith("code-shown")) "590317" else null,
        )
    }

    /**
     * The scheme the surface host wraps every input surface in: InputSurface.kt's
     * `val darkScheme = darkColorScheme(` over the app theme's primary. Keep the two in step.
     */
    @Composable
    private fun HostSurfaceTheme(fontScale: Float, content: @Composable () -> Unit) {
        MyApplicationTheme(darkTheme = true) {
            val hostScheme = darkColorScheme(
                primary = MaterialTheme.colorScheme.primary,
                background = Color(0xFF141218),
                surface = Color(0xFF141218),
                surfaceVariant = Color(0xFF2B2930),
                onBackground = Color(0xFFE6E0E9),
                onSurface = Color(0xFFE6E0E9),
                onSurfaceVariant = Color(0xFFCBC4D0),
            )
            MaterialTheme(colorScheme = hostScheme) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale), content = content)
            }
        }
    }

    private val withPicture by lazy { FakeFrames(hasPicture = true) }
    private val withLightPicture by lazy { FakeFrames(hasPicture = true, light = true) }
    private val withoutPicture by lazy { FakeFrames(hasPicture = false) }

    /**
     * A 480x300 stand-in for a card-game frame (a backdrop, two creatures and a hand of cards), or
     * with [light] a white document window, the hardest picture for translucent controls.
     */
    private class FakeFrames(hasPicture: Boolean, light: Boolean = false) : MirrorFrameSource {
        override val frameSeq: State<Long> = mutableLongStateOf(1L)
        private val image: ImageBitmap? = if (hasPicture) (if (light) lightFrame() else gameFrame()).asImageBitmap() else null
        override fun currentFrame(): ImageBitmap? = image

        private fun lightFrame(): Bitmap {
            val bitmap = Bitmap.createBitmap(480, 300, Bitmap.Config.RGB_565)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = 0xFFFFFFFF.toInt(); canvas.drawRect(0f, 0f, 480f, 300f, paint)
            paint.color = 0xFFECECEC.toInt(); canvas.drawRect(0f, 0f, 480f, 22f, paint)
            paint.color = 0xFFD0D0D0.toInt()
            for (row in 0 until 12) canvas.drawRect(24f, 40f + row * 20f, 456f, 41f + row * 20f, paint)
            paint.color = 0xFF9AA0A6.toInt()
            for (row in 0 until 12) canvas.drawRect(28f, 46f + row * 20f, 28f + (row * 37 % 260) + 80f, 52f + row * 20f, paint)
            return bitmap
        }

        private fun gameFrame(): Bitmap {
            val bitmap = Bitmap.createBitmap(480, 300, Bitmap.Config.RGB_565)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = 0xFF2B3A4A.toInt(); canvas.drawRect(0f, 0f, 480f, 300f, paint)
            paint.color = 0xFF3F5A3A.toInt(); canvas.drawRect(0f, 170f, 480f, 300f, paint)
            paint.color = 0xFFB0413E.toInt(); canvas.drawOval(RectF(70f, 90f, 150f, 180f), paint)
            paint.color = 0xFF6B4FA0.toInt(); canvas.drawOval(RectF(300f, 70f, 400f, 180f), paint)
            paint.color = 0xFF8A1C1C.toInt(); canvas.drawRect(300f, 60f, 400f, 66f, paint)
            val cardColors = intArrayOf(0xFFC9A227.toInt(), 0xFF4A90C2.toInt(), 0xFFC9A227.toInt(), 0xFF7A9E3B.toInt(), 0xFF4A90C2.toInt())
            cardColors.forEachIndexed { i, color ->
                paint.color = color
                canvas.drawRoundRect(RectF(120f + i * 50f, 220f, 165f + i * 50f, 290f), 6f, 6f, paint)
            }
            return bitmap
        }
    }
}
