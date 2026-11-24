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
 * 1.0, 1.3 and 2.0, with long and emoji computer names; plus the touch ring on the first frame of a press.
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

    private companion object {
        const val SETTLE_MS = 1_000L
        const val LONG_NAME = "Rick's MacBook Pro (Work) in the upstairs office"
        const val EMOJI_NAME = "🎮 Gaming PC 🖥️"
    }

    private data class Shot(val name: String, val state: MirrorUiState, val fontScale: Float)

    @Test
    fun fontScale1() = captureAll(states.map { (name, state) -> Shot(name, state, fontScale = 1f) })

    @Test
    fun fontScale1_3() = captureAll(states.map { (name, state) -> Shot(name, state, fontScale = 1.3f) })

    @Test
    fun fontScale2() = captureAll(states.map { (name, state) -> Shot(name, state, fontScale = 2f) })

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
                key(shot) { Surface(shot.name, shot.state) }
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
            HostSurfaceTheme(fontScale = 1f) { Surface(stateName, state) }
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
    private fun Surface(name: String, state: MirrorUiState) {
        // The real touch policy, so the ring the press draws is the one the app would.
        val policy = androidx.compose.runtime.remember(state.touchMode) { MirrorTouchPolicy(mode = state.touchMode, tapSlopPx = 24f) }
        MirrorSurface(
            state = state,
            // A picture once one was decoded this session, or one left from before the link dropped.
            frames = if ((state.phase as? MirrorPhase.Connected)?.hasFrame == true || name == "link-lost") withPicture else withoutPicture,
            onClose = {},
            onNextMode = {},
            onConfigureModes = {},
            onTouchModeChange = {},
            onForgetCompanion = {},
            onNoticeShown = {},
            onTouch = { policy.update(it) },
            forgetPending = name.startsWith("forget-pending"),
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
    private val withoutPicture by lazy { FakeFrames(hasPicture = false) }

    /** A 480x300 stand-in for a card-game frame: a backdrop, two creatures and a hand of cards. */
    private class FakeFrames(hasPicture: Boolean) : MirrorFrameSource {
        override val frameSeq: State<Long> = mutableLongStateOf(1L)
        private val image: ImageBitmap? = if (hasPicture) gameFrame().asImageBitmap() else null
        override fun currentFrame(): ImageBitmap? = image

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
