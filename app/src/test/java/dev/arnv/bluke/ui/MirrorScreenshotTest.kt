package dev.arnv.bluke.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.arnv.bluke.mirror.MirrorNotice
import dev.arnv.bluke.mirror.MirrorPhase
import dev.arnv.bluke.mirror.MirrorTouchMode
import dev.arnv.bluke.mirror.MirrorUiState
import dev.arnv.bluke.ui.theme.MyApplicationTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Mirror surface in each state it can show, in the landscape the input surfaces run in, dark
 * (as the surface host draws it) at font scale 1.0 and 1.3, and light for the main states.
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
        "link-lost" to address.copy(
            phase = MirrorPhase.Waiting("Rick's MacBook Pro"),
            pairedName = "Rick's MacBook Pro",
        ),
    )

    private data class Shot(val name: String, val state: MirrorUiState, val dark: Boolean, val fontScale: Float)

    @Test
    fun darkAtFontScale1() = captureAll(states.map { (name, state) -> Shot(name, state, dark = true, fontScale = 1f) })

    @Test
    fun darkAtFontScale1_3() = captureAll(states.map { (name, state) -> Shot(name, state, dark = true, fontScale = 1.3f) })

    @Test
    fun lightMainStates() = captureAll(
        listOf("unpaired", "pairing", "streaming").map { Shot(it, states.getValue(it), dark = false, fontScale = 1f) },
    )

    /** One composition per test (the rule allows one setContent), switched shot by shot. */
    private fun captureAll(shots: List<Shot>) {
        val current = mutableStateOf(shots.first())
        val withPicture = FakeFrames(hasPicture = true)
        val withoutPicture = FakeFrames(hasPicture = false)
        composeTestRule.setContent {
            val shot = current.value
            MyApplicationTheme(darkTheme = shot.dark) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, shot.fontScale)) {
                    key(shot) {
                        MirrorSurface(
                            state = shot.state,
                            frames = if (shot.state.phase is MirrorPhase.Connected || shot.name == "link-lost") withPicture else withoutPicture,
                            onClose = {},
                            onNextMode = {},
                            onConfigureModes = {},
                            onTouchModeChange = {},
                            onForgetCompanion = {},
                            onNoticeShown = {},
                            onTouch = {},
                        )
                    }
                }
            }
        }
        shots.forEach { shot ->
            current.value = shot
            composeTestRule.waitForIdle()
            val theme = if (shot.dark) "dark" else "light"
            val scale = if (shot.fontScale == 1f) "1" else shot.fontScale.toString()
            composeTestRule.onRoot().captureRoboImage(
                filePath = "src/test/screenshots/mirror/mirror-${shot.name}-$theme-fs$scale-land.png",
            )
        }
    }

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
