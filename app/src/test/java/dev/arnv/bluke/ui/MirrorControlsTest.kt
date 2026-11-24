package dev.arnv.bluke.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import dev.arnv.bluke.mirror.MirrorMessage
import dev.arnv.bluke.mirror.MirrorMessage.Pointer
import dev.arnv.bluke.mirror.MirrorPhase
import dev.arnv.bluke.mirror.MirrorTouchMode
import dev.arnv.bluke.mirror.MirrorTouchPolicy
import dev.arnv.bluke.mirror.MirrorUiState
import dev.arnv.bluke.mirror.update
import dev.arnv.bluke.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Mirror surface's own controls, driven as a person would: Forget and its Undo window, Decline
 * on the card and on the chip, Show code, the touch-mode toggle, the left-button key with a finger
 * on the picture at the same time, the hotkey buttons and their editor. The callbacks stand in for
 * the controller, around the real touch policy; the forget wiring is MirrorView's own [rememberPendingForget].
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
    /** Everything the touch policy sent the computer, picture and key alike, in order. */
    private val sent = mutableListOf<MirrorMessage>()
    private val hotkeyEvents = mutableListOf<Pair<MirrorHotkey, Boolean>>()
    private var savedHotkeys: List<MirrorHotkey>? = null
    private var hotkeysUi by mutableStateOf(MirrorHotkeysUi())

    private val streaming = MirrorUiState(
        phase = MirrorPhase.Connected("Mac", null, hasFrame = true),
        pairedName = "Mac",
        fps = 20,
        latencyMs = 6,
    )
    private val request = MirrorPhase.Pairing("590317", "Studio iMac", attemptsLeft = 3)

    private fun show(state: MirrorUiState, editing: Boolean = false) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            MyApplicationTheme(darkTheme = true) {
                if (onScreen) Host(state, editing)
            }
        }
        settle()
    }

    @Composable
    private fun Host(state: MirrorUiState, editing: Boolean) {
        val forget = rememberPendingForget { forgets++ }
        var shownCode by remember { mutableStateOf<String?>(null) }
        val policy = remember { MirrorTouchPolicy(mode = state.touchMode, tapSlopPx = 24f) }
        var keyHeld by remember { mutableStateOf(false) }
        MirrorSurface(
            state = state.copy(keyHeld = keyHeld),
            frames = if ((state.phase as? MirrorPhase.Connected)?.hasFrame == true) Picture else NoFrames,
            onClose = {},
            onNextMode = {},
            onConfigureModes = {},
            onTouchModeChange = { modes += it },
            onForgetCompanion = forget::request,
            onNoticeShown = {},
            onTouch = { policy.update(it).also { u -> sent += u.messages } },
            onKey = { down ->
                (if (down) policy.pressKey() else policy.liftKey()).also { u ->
                    sent += u.messages
                    keyHeld = policy.keyHeld
                }
            },
            hotkeys = hotkeysUi,
            onHotkey = { hotkey, down -> hotkeyEvents += hotkey to down },
            onHotkeysChange = { savedHotkeys = it },
            editingHotkeys = editing,
            forgetPending = forget.pending,
            onUndoForget = forget::undo,
            onDeclinePairing = { declines++ },
            shownPairingCode = shownCode,
            onShowPairingCode = { shownCode = it },
        )
    }

    private fun settle(ms: Long = 500) {
        // With the clock stepped by hand, a state written from a touch handler must be announced
        // before the next frame reads it.
        Snapshot.sendApplyNotifications()
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
    fun aTapDismissesTheSlowLinkChip() {
        show(streaming.copy(latencyMs = 140))
        rule.onNodeWithTag("mirror_slow_link").assertIsDisplayed().performClick()
        settle(1_000)
        rule.onNodeWithTag("mirror_slow_link").assertDoesNotExist()
    }

    @Test
    fun theTouchModeButtonSwitchesToTheOtherMode() {
        show(streaming)
        rule.onNodeWithTag("mirror_touch_mode_btn").performClick()
        assertEquals(listOf(MirrorTouchMode.HOVER), modes)
    }

    @Test
    fun theLeftButtonKeyShowsOnlyInHover() {
        show(streaming)
        rule.onNodeWithTag("mirror_left_button").assertDoesNotExist()
    }

    private val hover = streaming.copy(touchMode = MirrorTouchMode.HOVER)

    private fun center(tag: String): Offset = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.center

    /** A point on the picture, well right of the controls, on the 800x360dp test screen at xhdpi. */
    private fun onPicture(fx: Float, fy: Float): Offset = Offset(1600f * fx, 720f * fy)

    @Test
    fun aThumbOnTheKeyAndAFingerOnThePictureDragHeldNeverScroll() {
        show(hover)
        val key = center("mirror_left_button")
        rule.onRoot().performTouchInput {
            // The right finger points first, so the key has a point to press at.
            down(1, onPicture(0.5f, 0.5f))
            moveTo(1, onPicture(0.55f, 0.5f))
            // The left thumb lands on the key while the finger rests: press there and hold.
            down(0, key)
            moveTo(1, onPicture(0.6f, 0.6f))
            moveTo(1, onPicture(0.65f, 0.65f))
            // The finger lifts: still held.
            up(1)
            // A new finger lands elsewhere: straight there, still held.
            down(2, onPicture(0.8f, 0.3f))
            moveTo(2, onPicture(0.82f, 0.32f))
            up(2)
        }
        settle()
        assertTrue(sent.none { it is MirrorMessage.Wheel })
        val buttons = sent.map { (it as Pointer).buttons }
        // Hover moves, then the press, then nothing but held moves.
        val press = buttons.indexOf(1)
        assertTrue("$sent", press > 0)
        assertTrue("$sent", buttons.take(press).all { it == 0 })
        assertTrue("$sent", buttons.drop(press).all { it == 1 })
        assertTrue(sent.size - press >= 4)
        rule.onNodeWithTag("mirror_left_button").assertIsDisplayed()
        // Only the key's own lift lets go, where the pointer last went.
        val last = sent.last() as Pointer
        rule.onRoot().performTouchInput { up(0) }
        settle()
        assertEquals(Pointer(last.x, last.y, 0), sent.last())
    }

    @Test
    fun aThumbAlreadyOnTheKeyIsNeverTheSecondFingerOfAScroll() {
        show(hover)
        rule.onRoot().performTouchInput {
            down(1, onPicture(0.5f, 0.5f))
            moveTo(1, onPicture(0.52f, 0.5f))
            up(1)
        }
        val before = sent.size
        val key = center("mirror_left_button")
        rule.onRoot().performTouchInput {
            down(0, key)
            down(1, onPicture(0.5f, 0.5f))
            moveTo(1, onPicture(0.6f, 0.7f))
            moveTo(1, onPicture(0.7f, 0.8f))
            up(1)
            up(0)
        }
        settle()
        val during = sent.drop(before)
        assertTrue("$during", during.none { it is MirrorMessage.Wheel })
        assertEquals(listOf(1, 1, 1, 1, 0), during.map { (it as Pointer).buttons })
    }

    @Test
    fun theKeyBeforeAnyPointSaysToTouchThePictureFirst() {
        show(hover)
        val key = center("mirror_left_button")
        rule.onRoot().performTouchInput { down(0, key) }
        settle()
        assertTrue(sent.isEmpty())
        rule.onNodeWithText("Touch the picture first, so the button knows where to press").assertIsDisplayed()
        rule.onRoot().performTouchInput { up(0) }
        assertTrue(sent.isEmpty())
    }

    private val endTurn = MirrorHotkey(KeyboardLayouts.KEY_E, label = "End turn")
    private val shiftTab = MirrorHotkey(KeyboardLayouts.KEY_TAB, HotkeyModifiers.SHIFT)

    @Test
    fun aHotkeyGoesDownOnPressAndUpOnLift() {
        hotkeysUi = MirrorHotkeysUi(listOf(endTurn, shiftTab), enabled = true, hostName = "Rick's MacBook")
        show(streaming)
        rule.onNodeWithContentDescription("Shift Tab").assertIsDisplayed()
        val cell = center("mirror_hotkey_0")
        rule.onRoot().performTouchInput { down(0, cell) }
        settle()
        assertEquals(listOf(endTurn to true), hotkeyEvents)
        rule.onRoot().performTouchInput { up(0) }
        settle()
        assertEquals(listOf(endTurn to true, endTurn to false), hotkeyEvents)
    }

    @Test
    fun aButtonMovedIntoACellSendsItsOwnKeyThere() {
        hotkeysUi = MirrorHotkeysUi(listOf(endTurn, shiftTab), enabled = true)
        show(streaming)
        val cell = center("mirror_hotkey_0")
        rule.onRoot().performTouchInput { down(0, cell) }
        settle()
        rule.onRoot().performTouchInput { up(0) }
        settle()
        // End turn removed: Shift Tab moves into the first cell.
        hotkeysUi = hotkeysUi.copy(list = listOf(shiftTab))
        settle()
        rule.onRoot().performTouchInput { down(0, cell) }
        settle()
        rule.onRoot().performTouchInput { up(0) }
        settle()
        assertEquals(listOf(endTurn to true, endTurn to false, shiftTab to true, shiftTab to false), hotkeyEvents)
    }

    @Test
    fun hotkeysPastWhatFitsAreOnTheNextPage() {
        val eight = (0 until 8).map { MirrorHotkey(KeyboardLayouts.KEY_1 + it) }
        hotkeysUi = MirrorHotkeysUi(eight, enabled = true)
        show(streaming)
        // A 360dp-high phone holds three rows: five hotkeys and the pager.
        rule.onNodeWithTag("mirror_hotkey_4").assertIsDisplayed()
        rule.onNodeWithTag("mirror_hotkey_5").assertDoesNotExist()
        rule.onNodeWithTag("mirror_hotkey_pager").performClick()
        settle()
        rule.onNodeWithTag("mirror_hotkey_0").assertDoesNotExist()
        rule.onNodeWithTag("mirror_hotkey_7").assertIsDisplayed()
        val cell = center("mirror_hotkey_5")
        rule.onRoot().performTouchInput { down(0, cell) }
        settle()
        rule.onRoot().performTouchInput { up(0) }
        settle()
        assertEquals(listOf(eight[5] to true, eight[5] to false), hotkeyEvents)
    }

    @Test
    fun hotkeysWithoutABluetoothHostReadDisabledAndSayWhichHost() {
        hotkeysUi = MirrorHotkeysUi(listOf(endTurn), enabled = false, hostName = "Rick's MacBook")
        show(streaming)
        rule.onNodeWithTag("mirror_hotkey_0").assertIsNotEnabled().performClick()
        settle()
        assertTrue(hotkeyEvents.isEmpty())
        rule.onNodeWithText("Connect Bluetooth to Rick's MacBook to use hotkeys").assertIsDisplayed()
    }

    @Test
    fun theEditorAddsAChosenKeyWithItsModifiers() {
        show(streaming)
        rule.onNodeWithTag("mirror_add_hotkey").performClick()
        settle()
        rule.onNodeWithTag("mirror_hotkey_editor").assertIsDisplayed()
        rule.onNodeWithTag("mirror_modifier_Shift").performClick()
        // Below the fold on a 360dp-high screen: chosen through its click action, as TalkBack would.
        rule.onNodeWithTag("mirror_key_${KeyboardLayouts.KEY_TAB}").performSemanticsAction(SemanticsActions.OnClick)
        settle()
        rule.onNodeWithTag("mirror_hotkey_add").assertTextEquals("Add ⇧Tab").performSemanticsAction(SemanticsActions.OnClick)
        settle()
        assertEquals(listOf(shiftTab), savedHotkeys)
        rule.onNodeWithTag("mirror_hotkey_editor_done").performClick()
        settle()
        rule.onNodeWithTag("mirror_hotkey_editor").assertDoesNotExist()
    }

    @Test
    fun theEditorRemovesAndReordersButtons() {
        hotkeysUi = MirrorHotkeysUi(listOf(endTurn, shiftTab), enabled = true)
        show(streaming, editing = true)
        rule.onNodeWithContentDescription("Move Shift Tab up").performClick()
        assertEquals(listOf(shiftTab, endTurn), savedHotkeys)
        rule.onNodeWithTag("mirror_hotkey_remove_0").performClick()
        assertEquals(listOf(shiftTab), savedHotkeys)
    }

    private object NoFrames : MirrorFrameSource {
        override val frameSeq: State<Long> = mutableLongStateOf(0L)
        override fun currentFrame(): ImageBitmap? = null
    }

    /** A plain 480x300 picture, so touches on the image reach the policy. */
    private object Picture : MirrorFrameSource {
        override val frameSeq: State<Long> = mutableLongStateOf(1L)
        private val image = Bitmap.createBitmap(480, 300, Bitmap.Config.RGB_565).asImageBitmap()
        override fun currentFrame(): ImageBitmap = image
    }

    private companion object {
        /** Past the 3 s undo window the notice gives. */
        const val UNDO_WINDOW_PLUS = 4_000L
    }
}
