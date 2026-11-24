package dev.arnv.bluke.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.arnv.bluke.mirror.MirrorPhase
import dev.arnv.bluke.mirror.MirrorTouchMode
import dev.arnv.bluke.mirror.MirrorTouchPolicy
import dev.arnv.bluke.mirror.MirrorUiState
import dev.arnv.bluke.mirror.update
import dev.arnv.bluke.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Mirror surface's own controls, driven as a person would: Forget and its Undo window, Decline
 * on the card and on the chip, Show code, and the Direct/Hover choice. The callbacks stand in for
 * the controller; the forget wiring is MirrorView's own [rememberPendingForget].
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w800dp-h360dp-land-xhdpi", sdk = [36])
class MirrorControlsTest {
    @get:Rule
    val rule = createComposeRule()

    private var forgets = 0
    private var declines = 0
    private val modes = mutableListOf<MirrorTouchMode>()
    private var onScreen by mutableStateOf(true)

    private val streaming = MirrorUiState(
        phase = MirrorPhase.Connected("Mac", null, hasFrame = true),
        pairedName = "Mac",
        fps = 20,
        latencyMs = 6,
    )
    private val request = MirrorPhase.Pairing("590317", "Studio iMac", attemptsLeft = 3)

    private fun show(state: MirrorUiState) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            MyApplicationTheme(darkTheme = true) {
                if (onScreen) Host(state)
            }
        }
        settle()
    }

    @Composable
    private fun Host(state: MirrorUiState) {
        val forget = rememberPendingForget { forgets++ }
        var shownCode by remember { mutableStateOf<String?>(null) }
        val policy = remember { MirrorTouchPolicy(mode = state.touchMode, tapSlopPx = 24f) }
        MirrorSurface(
            state = state,
            frames = NoFrames,
            onClose = {},
            onNextMode = {},
            onConfigureModes = {},
            onTouchModeChange = { modes += it },
            onForgetCompanion = forget::request,
            onNoticeShown = {},
            onTouch = { policy.update(it) },
            forgetPending = forget.pending,
            onUndoForget = forget::undo,
            onDeclinePairing = { declines++ },
            shownPairingCode = shownCode,
            onShowPairingCode = { shownCode = it },
        )
    }

    private fun settle(ms: Long = 500) {
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun tapForget() {
        rule.onNodeWithTag("mirror_menu_btn").performClick()
        settle()
        rule.onNodeWithText("Forget Mac").performClick()
        settle()
    }

    @Test
    fun forgetThenUndoForgetsNothing() {
        show(streaming)
        tapForget()
        rule.onNodeWithTag("mirror_undo_forget").assertIsDisplayed().performClick()
        settle(UNDO_WINDOW_PLUS)
        assertEquals(0, forgets)
    }

    @Test
    fun forgetWithoutUndoForgetsOnceTheWindowPasses() {
        show(streaming)
        tapForget()
        assertEquals(0, forgets)
        settle(UNDO_WINDOW_PLUS)
        assertEquals(1, forgets)
        settle(UNDO_WINDOW_PLUS)
        assertEquals(1, forgets)
    }

    @Test
    fun leavingInsideTheWindowStillForgetsOnce() {
        show(streaming)
        tapForget()
        onScreen = false
        settle()
        assertEquals(1, forgets)
        settle(UNDO_WINDOW_PLUS)
        assertEquals(1, forgets)
    }

    @Test
    fun declineOnThePairingCardTurnsTheRequestDown() {
        show(MirrorUiState(phase = request))
        rule.onNodeWithTag("mirror_pairing_code").assertIsDisplayed()
        rule.onNodeWithTag("mirror_pairing_decline").performClick()
        assertEquals(1, declines)
    }

    @Test
    fun aRequestOverTheLivePictureIsAChipUntilShowCode() {
        show(streaming.copy(phase = (streaming.phase as MirrorPhase.Connected).copy(pairing = request)))
        rule.onNodeWithTag("mirror_pairing_chip").assertIsDisplayed()
        rule.onNodeWithTag("mirror_pairing_code").assertDoesNotExist()
        rule.onNodeWithTag("mirror_pairing_show_code").performClick()
        settle(1_000)
        rule.onNodeWithTag("mirror_pairing_code").assertIsDisplayed()
        rule.onNodeWithTag("mirror_pairing_chip").assertDoesNotExist()
        rule.onNodeWithTag("mirror_pairing_decline").performClick()
        assertEquals(1, declines)
    }

    @Test
    fun declineOnTheChipTurnsTheRequestDown() {
        show(streaming.copy(phase = (streaming.phase as MirrorPhase.Connected).copy(pairing = request)))
        rule.onNodeWithTag("mirror_pairing_chip_decline").performClick()
        assertEquals(1, declines)
    }

    @Test
    fun tappingHoverAndDirectChoosesThem() {
        show(streaming)
        rule.onNodeWithTag("mirror_mode_hover").performClick()
        rule.onNodeWithTag("mirror_mode_direct").performClick()
        assertEquals(listOf(MirrorTouchMode.HOVER, MirrorTouchMode.DIRECT), modes)
    }

    private object NoFrames : MirrorFrameSource {
        override val frameSeq: State<Long> = mutableLongStateOf(0L)
        override fun currentFrame(): ImageBitmap? = null
    }

    private companion object {
        /** Past the 3 s undo window the notice gives. */
        const val UNDO_WINDOW_PLUS = 4_000L
    }
}
