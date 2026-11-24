package dev.arnv.bluke.ui.settings

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import dev.arnv.bluke.SettingsActivity
import dev.arnv.bluke.bluetooth.BluetoothKeyboardManager
import dev.arnv.bluke.sound.KeyboardSoundSynthesizer
import dev.arnv.bluke.ui.HomeScreen
import dev.arnv.bluke.ui.InputMode
import dev.arnv.bluke.ui.InputSurface
import dev.arnv.bluke.ui.SurfaceHost
import dev.arnv.bluke.ui.SurfaceHostStatus
import dev.arnv.bluke.ui.rememberKeyboardSurfaceState
import dev.arnv.bluke.ui.HomeUiState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The input-mode shortcuts the old settings screens left behind: a long press on a surface's mode
 * button or on a home mode tile opens the one Settings screen at Input modes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w914dp-h411dp-land-420dpi", sdk = [36])
class InputModeShortcutsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val prefs = application.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    private lateinit var manager: BluetoothKeyboardManager
    private lateinit var synth: KeyboardSoundSynthesizer

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
        // Home shows the mode tiles only once Bluetooth is usable, so the manager starts with it on and permitted.
        shadowOf(application).grantPermissions(
            Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_SCAN,
        )
        shadowOf(application.getSystemService(BluetoothManager::class.java).adapter).setState(BluetoothAdapter.STATE_ON)
        manager = BluetoothKeyboardManager(application)
        synth = KeyboardSoundSynthesizer(application)
    }

    @After
    fun tearDown() {
        synth.release()
        manager.close()
        prefs.edit().clear().commit()
    }

    private fun SemanticsNodeInteraction.longPressOpensInputModes(label: String) {
        assertEquals(label, fetchSemanticsNode().config[SemanticsActions.OnLongClick].label)
        while (shadowOf(application).nextStartedActivity != null) Unit
        performTouchInput { longClick() }
        compose.waitForIdle()
        val started = shadowOf(application).nextStartedActivity
        assertEquals(SettingsActivity::class.java.name, started?.component?.className)
        assertEquals(SettingsSection.INPUT_MODES.name, started?.getStringExtra(SETTINGS_SECTION_EXTRA))
    }

    @Test
    fun longPressingASurfacesModeButtonOpensInputModes() {
        compose.setContent {
            InputSurface(
                keyboardState = rememberKeyboardSurfaceState(manager, synth, prefs, HomeUiState(), false),
                btManager = manager,
                soundSynth = synth,
                sharedPrefs = prefs,
                launchMode = InputMode.KEYBOARD.id,
                isConnected = false,
                host = SurfaceHost(SurfaceHostStatus.Offline, null),
                onClose = {},
                onModeChange = {},
            )
        }
        compose.waitForIdle()
        compose.onNodeWithTag("surface_mode").longPressOpensInputModes("Choose input modes")
    }

    @Test
    fun longPressingAHomeModeTileOpensInputModes() {
        compose.setContent { HomeScreen(manager, synth) }
        compose.waitForIdle()
        // The tile's tag is on its surface; the long press belongs to the clickable inside it.
        compose.onNode(hasAnyAncestor(hasTestTag("mode_tile_${InputMode.TOUCHPAD.preferenceKey}")) and hasClickAction())
            .longPressOpensInputModes("Choose which modes the switch button cycles")
    }
}
