package dev.arnv.bluke.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.test.core.app.ApplicationProvider
import dev.arnv.bluke.bluetooth.BluetoothKeyboardManager
import dev.arnv.bluke.data.KEYBOARD_GEOMETRY_PREFERENCE
import dev.arnv.bluke.sound.KeyboardSoundSynthesizer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The keyboard as TalkBack meets it: every key a named button that a double-tap presses. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w914dp-h411dp-land-420dpi", sdk = [36])
class KeyboardAccessibilityTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    private lateinit var manager: BluetoothKeyboardManager
    private lateinit var synth: KeyboardSoundSynthesizer
    private val haptics = RecordingHapticPerformer()

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
        prefs.edit().putString(KEYBOARD_GEOMETRY_PREFERENCE, KeyboardGeometry.CLASSIC_75.name).commit()
        manager = BluetoothKeyboardManager(context)
        synth = KeyboardSoundSynthesizer(context)
    }

    @After
    fun tearDown() {
        synth.release()
        manager.close()
        prefs.edit().clear().commit()
    }

    private fun showSurface(): KeyboardSurfaceState {
        lateinit var surfaceState: KeyboardSurfaceState
        compose.setContent {
            CompositionLocalProvider(LocalHapticPerformer provides haptics) {
                val state = rememberKeyboardSurfaceState(manager, synth, prefs, HomeUiState(), isConnected = false)
                surfaceState = state
                SurfaceTheme {
                    KeyboardSurface(
                        state = state,
                        btManager = manager,
                        soundSynth = synth,
                        sharedPrefs = prefs,
                        caseBrush = caseBrush(state.selectedKeyboardTheme),
                        chrome = previewChrome(InputMode.KEYBOARD, SurfaceHost(SurfaceHostStatus.Offline, null)),
                        isConnected = false,
                    )
                }
            }
        }
        compose.waitForIdle()
        return surfaceState
    }

    /** TalkBack's double-tap: the node's click action, not a touch, which the plate would take as a press. */
    private fun SemanticsNodeInteraction.accessibilityClick() = performSemanticsAction(SemanticsActions.OnClick)

    private fun SemanticsNodeInteraction.stateDescription(): String? =
        fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    private fun SemanticsNodeInteractionsProvider.keyNodes() =
        onAllNodes(SemanticsMatcher("is a key") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("key_") == true
        }).fetchSemanticsNodes()

    @Test
    fun accessibilityClickOnQSendsKeyDownThenKeyUp() {
        val sent = mutableListOf<Pair<Int, Boolean>>()
        compose.setContent {
            KeyboardView(
                geometry = KeyboardGeometry.CLASSIC_75,
                theme = KeyboardThemeCatalog.builtIns.first(),
                activePressedKeys = emptyList(),
                isCapsLockActive = false,
                isNumLockActive = true,
                isScrollLockActive = false,
                onKeyPressChange = { code, press -> sent += code to press },
            )
        }

        compose.onNodeWithContentDescription("q").accessibilityClick()

        assertEquals(listOf(KeyboardLayouts.KEY_Q to true, KeyboardLayouts.KEY_Q to false), sent)
    }

    @Test
    fun everyKeyIsANamedButtonWithAClickAction() {
        showSurface()
        val keys = compose.keyNodes()
        assertTrue("the keyboard should expose its keys", keys.size > 60)
        keys.forEach { node ->
            val tag = node.config[SemanticsProperties.TestTag]
            assertEquals(tag, Role.Button, node.config.getOrNull(SemanticsProperties.Role))
            val description = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
            assertTrue("$tag has no spoken name", !description.isNullOrBlank())
            assertTrue("$tag has no click action", node.config.contains(SemanticsActions.OnClick))
        }
        // Named keys are spoken in words, not their glyphs or abbreviations.
        listOf("Left Shift", "Right Shift", "Escape", "Up arrow", "Space", "Backspace", "F5", "Left Control")
            .forEach { compose.onNodeWithContentDescription(it).assertExists() }
    }

    @Test
    fun accessibilityClickGoesThroughTheTouchPath() {
        showSurface()
        compose.onNodeWithContentDescription("q").accessibilityClick()
        compose.waitForIdle()
        // The same single press haptic a tap plays, and nothing left held.
        assertEquals(listOf(HapticEvent.PRESS), haptics.events)
    }

    @Test
    fun capsLockReadsItsStateAndFlipsOnClick() {
        showSurface()
        val caps = compose.onNodeWithContentDescription("Caps Lock")
        assertEquals("Off", caps.stateDescription())

        caps.accessibilityClick()
        compose.waitForIdle()

        assertEquals("On", caps.stateDescription())
        compose.onNodeWithContentDescription("Q").assertExists()
    }

    @Test
    fun shiftLatchesForTheNextKeyThenLetsGo() {
        val state = showSurface()
        val shift = compose.onNodeWithContentDescription("Left Shift")

        shift.accessibilityClick()
        compose.waitForIdle()
        assertEquals("Latched", shift.stateDescription())
        assertEquals(listOf(KeyboardLayouts.MOD_LSHIFT), state.activePressedKeys.toList())

        // Shift held: the letter reads, and types, upper case.
        compose.onNodeWithContentDescription("Q").accessibilityClick()
        compose.waitForIdle()

        assertEquals(null, shift.stateDescription())
        assertEquals(emptyList<Int>(), state.activePressedKeys.toList())
        compose.onNodeWithContentDescription("q").assertExists()
    }

    @Test
    fun aSecondClickOnALatchedModifierReleasesIt() {
        val state = showSurface()
        val ctrl = compose.onNodeWithContentDescription("Left Control")
        ctrl.accessibilityClick()
        ctrl.accessibilityClick()
        compose.waitForIdle()
        assertEquals(null, ctrl.stateDescription())
        assertEquals(emptyList<Int>(), state.activePressedKeys.toList())
    }
}
