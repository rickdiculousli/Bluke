package dev.arnv.bluke.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.activity.ComponentActivity
import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.drawToBitmap
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.arnv.bluke.bluetooth.BluetoothKeyboardManager
import dev.arnv.bluke.data.KEYBOARD_GEOMETRY_PREFERENCE
import dev.arnv.bluke.sound.KEY_SOUND_ENABLED_PREFERENCE
import dev.arnv.bluke.sound.KeyboardSoundSynthesizer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The keyboard surface as the home screen composes it: its remembered state, in the surface theme. */
@Composable
internal fun KeyboardSurfaceUnderTest(
    manager: BluetoothKeyboardManager,
    synth: KeyboardSoundSynthesizer,
    prefs: SharedPreferences,
    host: SurfaceHost = SurfaceHost(SurfaceHostStatus.Offline, null),
    homeUiState: HomeUiState = HomeUiState(),
) {
    val isConnected = host.status == SurfaceHostStatus.Connected
    val state = rememberKeyboardSurfaceState(manager, synth, prefs, homeUiState, isConnected)
    SurfaceTheme {
        KeyboardSurface(
            state = state,
            btManager = manager,
            soundSynth = synth,
            sharedPrefs = prefs,
            caseBrush = caseBrush(state.selectedKeyboardTheme),
            chrome = previewChrome(InputMode.KEYBOARD, host),
            isConnected = isConnected,
        )
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w914dp-h411dp-land-420dpi", sdk = [36])
class KeyboardSurfaceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    // The theme and sound stores read app_prefs, so the surface's preferences are that file too.
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

    private fun showSurface() {
        compose.setContent {
            CompositionLocalProvider(LocalHapticPerformer provides haptics) {
                KeyboardSurfaceUnderTest(manager, synth, prefs)
            }
        }
        compose.waitForIdle()
    }

    /** The node's pixels, cut from the window as drawn now (captureToImage waits for a redraw that never comes here). */
    private fun pixelsOf(node: androidx.compose.ui.test.SemanticsNodeInteraction): Bitmap {
        val bounds = node.fetchSemanticsNode().boundsInWindow
        val window = compose.runOnUiThread { compose.activity.window.decorView.drawToBitmap() }
        return Bitmap.createBitmap(
            window,
            bounds.left.toInt(), bounds.top.toInt(),
            bounds.width.toInt(), bounds.height.toInt(),
        )
    }

    private fun tapKey(tag: String) {
        compose.onNodeWithTag(tag).performTouchInput { down(center); up() }
        compose.waitForIdle()
    }

    @Test
    fun keyPressHapticFollowsTheHapticsSwitch() {
        prefs.edit().putBoolean(HAPTICS_PREFERENCE, false).commit()
        showSurface()

        tapKey("key_q")
        assertEquals(emptyList<HapticEvent>(), haptics.events)

        prefs.edit().putBoolean(HAPTICS_PREFERENCE, true).commit()
        compose.waitForIdle()
        tapKey("key_q")
        // One press haptic, on touch-down only: the release plays nothing.
        assertEquals(listOf(HapticEvent.PRESS), haptics.events)
    }

    @Test
    fun keyLooksPressedInTheFrameTheFingerLands() {
        showSurface()
        compose.mainClock.autoAdvance = false
        val key = compose.onNodeWithTag("key_q")
        val before = key.let(::pixelsOf)

        key.performTouchInput { down(center) }
        compose.mainClock.advanceTimeByFrame()

        val after = key.let(::pixelsOf)
        assertFalse("the key should draw pressed on the first frame", before.sameAs(after))
        compose.onNodeWithTag("surface_toolbar").assertExists()
        key.captureRoboImage(filePath = "src/test/screenshots/keyboard-pressed-frame1.png")
        key.performTouchInput { up() }
    }

    @Test
    fun tuneGeometryChangeAppliesWithoutReopening() {
        showSurface()
        compose.onNodeWithTag("key_F1").assertExists()

        KeyboardTuneState.read(prefs).copy(geometry = KeyboardGeometry.HHKB_60).write(prefs)
        compose.waitForIdle()

        compose.onNodeWithTag("key_F1").assertDoesNotExist()
        compose.onNodeWithTag("key_Backspace").assertExists()
    }

    @Test
    fun tuneThemeChangeRepaintsTheKeys() {
        showSurface()
        val before = compose.onNodeWithTag("key_q").let(::pixelsOf)

        val other = KeyboardThemeCatalog.builtIns.first { it.id != KeyboardTuneState.read(prefs).themeId }
        KeyboardTuneState.read(prefs).copy(themeId = other.id).write(prefs)
        compose.waitForIdle()

        val after = compose.onNodeWithTag("key_q").let(::pixelsOf)
        assertFalse(before.sameAs(after))
    }

    @Test
    fun toolbarSoundToggleWritesKeySound() {
        showSurface()
        val toggle = compose.onNodeWithTag("keyboard_sound_toggle")
        toggle.assertIsOn()

        toggle.performClick()
        compose.waitForIdle()

        assertFalse(prefs.getBoolean(KEY_SOUND_ENABLED_PREFERENCE, true))
        toggle.assertIsOff()
    }

    @Test
    fun offlineLockLightTogglesLocally() {
        showSurface()
        compose.onNodeWithText("CAPS").assertExists()
        compose.onNodeWithText("CAPS").performClick()
        compose.waitForIdle()
        // Caps lock on: the letter keys read upper case.
        compose.onNodeWithTag("key_Q").assertExists()
    }

    @Test
    fun tuneSheetOpensFromToolbarAndWritesSettings() {
        showSurface()
        compose.onNodeWithTag("surface_tune").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("surface_tune_sheet").assertExists()

        compose.onNodeWithText("HHKB 60%").performClick()
        compose.waitForIdle()

        assertEquals(KeyboardGeometry.HHKB_60, KeyboardTuneState.read(prefs).geometry)
        compose.onNodeWithTag("key_F1").assertDoesNotExist()
    }

    @Test
    fun tuneSheetHidesTheKeysFromTalkBack() {
        showSurface()
        compose.onAllNodes(androidx.compose.ui.test.hasTestTag("key_q")).assertCountEquals(1)
        compose.onNodeWithTag("surface_tune").performClick()
        compose.waitForIdle()
        compose.onAllNodes(androidx.compose.ui.test.hasTestTag("key_q")).assertCountEquals(0)
        compose.onNodeWithTag("surface_tune_sheet").assertExists()
    }

    @Test
    @Config(qualifiers = "w411dp-h914dp-port-420dpi", sdk = [36])
    fun aPortraitWindowAsksToTurnThePhone() {
        showSurface()
        compose.onNodeWithTag("keyboard_turn_sideways").assertIsDisplayed()
        compose.onAllNodes(androidx.compose.ui.test.hasTestTag("key_q")).assertCountEquals(0)
        compose.onNodeWithTag("surface_tune").assertIsDisplayed()
    }
}

/** Tune state round trip: what Settings writes is what the surface reads. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class KeyboardTuneStateTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("keyboard-tune-state", Context.MODE_PRIVATE)

    @Test
    fun writeThenReadKeepsEveryChoice() {
        prefs.edit().clear().commit()
        val chosen = KeyboardTuneState(
            geometry = KeyboardGeometry.HHKB_60,
            characterLayout = KeyboardCharacterLayout.DVORAK,
            modifierStyle = KeyboardModifierStyle.MAC,
            themeId = KeyboardThemeCatalog.builtIns.last().id,
            sound = KeyboardSound.Custom("pack-1"),
        )
        chosen.write(prefs)
        assertEquals(chosen, KeyboardTuneState.read(prefs))

        val builtIn = chosen.copy(sound = KeyboardSound.BuiltIn(dev.arnv.bluke.sound.SwitchType.TOPRE))
        builtIn.write(prefs)
        assertEquals(builtIn, KeyboardTuneState.read(prefs))
    }

    @Test
    fun emptyPreferencesReadAsTheAppDefaults() {
        prefs.edit().clear().commit()
        assertEquals(KeyboardTuneState(), KeyboardTuneState.read(prefs))
    }
}

