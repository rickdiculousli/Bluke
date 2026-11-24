package dev.arnv.bluke

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.arnv.bluke.bluetooth.BluetoothKeyboardManager
import dev.arnv.bluke.bluetooth.BluetoothState
import dev.arnv.bluke.bluetooth.CURRENT_HID_DESCRIPTOR_REVISION
import dev.arnv.bluke.bluetooth.GAMEPAD_DPAD_MODE_PREFERENCE
import dev.arnv.bluke.bluetooth.HID_DESCRIPTOR_REVISION_PREFERENCE
import dev.arnv.bluke.bluetooth.HidLifecycleState
import dev.arnv.bluke.data.KEYBOARD_GEOMETRY_PREFERENCE
import dev.arnv.bluke.data.KeyboardThemeRepository
import dev.arnv.bluke.sound.KeyboardSoundSynthesizer
import dev.arnv.bluke.ui.HomeScreen
import dev.arnv.bluke.ui.InputMode
import dev.arnv.bluke.ui.KeyboardCharacterLayout
import dev.arnv.bluke.ui.KeyboardGeometry
import dev.arnv.bluke.ui.KeyboardLayoutSwitcherOverlay
import dev.arnv.bluke.ui.KeyboardView
import dev.arnv.bluke.ui.MediaPresentationView
import dev.arnv.bluke.ui.MultimediaPosture
import dev.arnv.bluke.ui.theme.MyApplicationTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Visual-audit harness: renders every user-facing screen in light and dark theme at Pixel 8 size
 * and writes PNGs to [OUT]. Not a regression test and not part of the suite: it lives under
 * `docs/proposals/assets/` and runs only when copied into a checkout.
 *
 * To capture a tree (a UI spec's reviewer runs it on a scratch checkout of `main` for the
 * "from main" captures, then on the branch):
 *
 *     cp docs/proposals/assets/001-AllScreensScreenshotTest.kt \
 *        app/src/test/java/dev/arnv/bluke/AllScreensScreenshotTest.kt
 *     SCREENS_OUT="$PWD/app/build/screens-main" ./gradlew recordRoborazziDebug \
 *        --tests 'dev.arnv.bluke.AllScreensScreenshotTest'
 *     rm app/src/test/java/dev/arnv/bluke/AllScreensScreenshotTest.kt
 *
 * Roborazzi writes images only in record mode, so `testDebugUnitTest` alone captures nothing.
 * Without `SCREENS_OUT` the PNGs go to `app/build/all-screens/`. A screen that fails to render
 * doesn't stop the others: each test prints `SCREEN FAILURES:` with the ones that failed (see
 * the test report's standard output), so count the PNGs to see what was captured. It renders
 * the screens through their public entry points (HomeScreen, the activities), so a spec that
 * renames or removes one of those updates this file in the same change.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class AllScreensScreenshotTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val prefs: SharedPreferences = app.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    private val failures = mutableListOf<String>()
    private val openScenarios = mutableListOf<ActivityScenario<*>>()

    private companion object {
        val OUT = File(System.getenv("SCREENS_OUT") ?: "build/all-screens").apply { mkdirs() }
        const val PORTRAIT = "w411dp-h914dp-port"
        const val LANDSCAPE = "w914dp-h411dp-land"
        const val TALL = "w411dp-h2400dp-port"
    }

    @After
    fun tearDown() {
        openScenarios.forEach { runCatching { it.close() } }
        if (failures.isNotEmpty()) println("SCREEN FAILURES:\n" + failures.joinToString("\n"))
    }

    // ---------- plumbing ----------

    private fun baselinePrefs() {
        prefs.edit().clear()
            .putBoolean("has_seen_onboarding", true)
            .putBoolean("has_seen_gamepad_guide", true)
            .putInt("last_run_version_code", BuildConfig.VERSION_CODE)
            .putInt(HID_DESCRIPTOR_REVISION_PREFERENCE, CURRENT_HID_DESCRIPTOR_REVISION)
            .putBoolean("dynamic_color", false)
            .commit()
    }

    private fun theme(dark: Boolean, size: String) {
        prefs.edit().putInt("theme_mode", if (dark) 2 else 1).commit()
        RuntimeEnvironment.setQualifiers("+$size-${if (dark) "night" else "notnight"}")
    }

    private fun grantBluetooth() {
        shadowOf(app).grantPermissions(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }

    private fun shot(name: String) {
        composeRule.waitForIdle()
        captureScreenRoboImage(File(OUT, "$name.png").absolutePath)
    }

    private fun attempt(name: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            failures += "$name: ${t::class.simpleName}: ${t.message?.take(300)}"
            t.printStackTrace()
        } finally {
            openScenarios.forEach { runCatching { it.close() } }
            openScenarios.clear()
        }
    }

    private fun <A : Activity> launch(cls: Class<A>): ActivityScenario<A> =
        ActivityScenario.launch(cls).also { openScenarios += it }

    private fun compose(content: @Composable () -> Unit) {
        launch(ComponentActivity::class.java).onActivity { it.setContent { MyApplicationTheme { content() } } }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> BluetoothKeyboardManager.flow(field: String): MutableStateFlow<T> =
        BluetoothKeyboardManager::class.java.getDeclaredField(field)
            .apply { isAccessible = true }
            .get(this) as MutableStateFlow<T>

    private fun device(address: String, name: String, bonded: Boolean): BluetoothDevice {
        val d = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address)
        shadowOf(d).setName(name)
        shadowOf(d).setBondState(if (bonded) BluetoothDevice.BOND_BONDED else BluetoothDevice.BOND_NONE)
        return d
    }

    private data class HomeState(
        val bt: BluetoothState,
        val message: String,
        val bonded: List<BluetoothDevice> = emptyList(),
        val scanned: List<BluetoothDevice> = emptyList(),
        val scanning: Boolean = false,
        val connected: BluetoothDevice? = null,
        val target: String? = null,
        val lifecycle: HidLifecycleState = HidLifecycleState.Registered,
    )

    private fun BluetoothKeyboardManager.apply(state: HomeState) {
        flow<BluetoothState>("_serviceState").value = state.bt
        flow<String>("_statusMessage").value = state.message
        flow<List<BluetoothDevice>>("_bondedDevices").value = state.bonded
        flow<List<BluetoothDevice>>("_scannedDevices").value = state.scanned
        flow<Boolean>("_isScanning").value = state.scanning
        flow<BluetoothDevice?>("_connectedDevice").value = state.connected
        flow<String?>("_connectionTargetAddress").value = state.target
        flow<HidLifecycleState>("_lifecycleState").value = state.lifecycle
    }

    /** Renders HomeScreen with a real manager whose state flows are overwritten with [state]. */
    private fun home(state: HomeState, afterLaunch: () -> Unit = {}) {
        val manager = (app as BlukeApplication).bluetoothKeyboardManager
        val synth = KeyboardSoundSynthesizer(app)
        manager.apply(state)
        compose { HomeScreen(btManager = manager, soundSynth = synth) }
        composeRule.waitForIdle()
        manager.apply(state)
        composeRule.waitForIdle()
        afterLaunch()
        manager.apply(state)
    }

    private val mac by lazy { device("A4:83:E7:11:22:33", "Rick's MacBook Pro", bonded = true) }
    private val pc by lazy { device("3C:7C:3F:44:55:66", "DESKTOP-K16", bonded = true) }
    private val tv by lazy { device("F0:B4:29:77:88:99", "Living Room Chromecast", bonded = true) }
    private val ipad by lazy { device("8C:85:90:AA:BB:CC", "iPad Air", bonded = false) }
    private val unknown by lazy { device("12:34:56:78:9A:BC", "Pixel Tablet", bonded = false) }

    private fun themes(block: (dark: Boolean, suffix: String) -> Unit) {
        for (dark in listOf(false, true)) block(dark, if (dark) "dark" else "light")
    }

    // ---------- home ----------

    @Test
    fun homeStates() {
        grantBluetooth()
        val states = linkedMapOf(
            "empty" to HomeState(BluetoothState.ReadyDisconnected, "Ready. Scan to find a host."),
            "paired-list" to HomeState(
                BluetoothState.ReadyDisconnected, "Ready",
                bonded = listOf(mac, pc, tv),
            ),
            "scanning" to HomeState(
                BluetoothState.ReadyDisconnected, "Scanning for hosts...",
                bonded = listOf(mac, pc), scanned = listOf(ipad, unknown), scanning = true,
            ),
            "connecting" to HomeState(
                BluetoothState.PairingMode("Rick's MacBook Pro"), "Connecting to Rick's MacBook Pro...",
                bonded = listOf(mac, pc), target = mac.address,
                lifecycle = HidLifecycleState.Connecting(mac.address),
            ),
            "connected" to HomeState(
                BluetoothState.Connected("Rick's MacBook Pro"), "Connected to Rick's MacBook Pro",
                bonded = listOf(mac, pc, tv), connected = mac,
                lifecycle = HidLifecycleState.Connected(mac.address),
            ),
            "bt-off" to HomeState(BluetoothState.BluetoothOff, "Bluetooth is off"),
            "permission-required" to HomeState(BluetoothState.PermissionRequired, "Bluetooth permission required"),
            "profile-not-supported" to HomeState(
                BluetoothState.ProfileNotSupported, "Bluetooth HID profile is not supported on this device firmware.",
            ),
            "unsupported" to HomeState(BluetoothState.Unsupported, "Bluetooth is not supported on this device's hardware."),
        )
        themes { dark, t ->
            for ((name, state) in states) {
                attempt("home-$name-$t") {
                    baselinePrefs(); theme(dark, PORTRAIT)
                    home(state)
                    shot("home-$name-$t")
                }
            }
            attempt("home-update-dialog-$t") {
                baselinePrefs(); theme(dark, PORTRAIT)
                prefs.edit().putInt(HID_DESCRIPTOR_REVISION_PREFERENCE, 1).commit()
                home(states.getValue("paired-list"))
                shot("home-dialog-descriptor-refresh-$t")
            }
            attempt("home-gamepad-guide-$t") {
                baselinePrefs(); theme(dark, PORTRAIT)
                prefs.edit().putBoolean("has_seen_gamepad_guide", false).commit()
                home(states.getValue("connected")) {
                    composeRule.onNodeWithTag("mode_tile_gamepad").performScrollTo().performClick()
                }
                shot("home-dialog-gamepad-guide-$t")
            }
        }
    }

    @Test
    fun homeDynamicColor() {
        grantBluetooth()
        themes { dark, t ->
            attempt("home-dynamic-$t") {
                baselinePrefs(); theme(dark, PORTRAIT)
                prefs.edit().putBoolean("dynamic_color", true).commit()
                home(
                    HomeState(
                        BluetoothState.Connected("Rick's MacBook Pro"), "Connected",
                        bonded = listOf(mac, pc, tv), connected = mac,
                        lifecycle = HidLifecycleState.Connected(mac.address),
                    ),
                )
                shot("home-connected-dynamiccolor-$t")
            }
        }
    }

    // ---------- input surfaces (launched from home, as a user does) ----------

    private val connectedState by lazy {
        HomeState(
            BluetoothState.Connected("Rick's MacBook Pro"), "Connected to Rick's MacBook Pro",
            bonded = listOf(mac, pc), connected = mac, lifecycle = HidLifecycleState.Connected(mac.address),
        )
    }

    private fun surface(name: String, dark: Boolean, t: String, size: String, setup: SharedPreferences.Editor.() -> Unit, after: () -> Unit = {}) {
        attempt("$name-$t") {
            baselinePrefs(); theme(dark, size)
            prefs.edit().apply(setup).commit()
            home(connectedState) {
                val mode = InputMode.fromId(prefs.getInt("launch_mode", InputMode.KEYBOARD.id))
                composeRule.onNodeWithTag("mode_tile_${mode.preferenceKey}").performScrollTo().performClick()
                composeRule.waitForIdle()
                after()
            }
            shot("$name-$t")
        }
    }

    @Test
    fun inputSurfaces() {
        grantBluetooth()
        themes { dark, t ->
            surface("keyboard-default-landscape", dark, t, LANDSCAPE, { putInt("launch_mode", 0) })
            surface("keyboard-hhkb60-landscape", dark, t, LANDSCAPE, {
                putInt("launch_mode", 0); putString(KEYBOARD_GEOMETRY_PREFERENCE, KeyboardGeometry.HHKB_60.name)
            })
            surface("keyboard-classic75-landscape", dark, t, LANDSCAPE, {
                putInt("launch_mode", 0); putString(KEYBOARD_GEOMETRY_PREFERENCE, KeyboardGeometry.CLASSIC_75.name)
            })
            surface("keyboard-default-portrait", dark, t, PORTRAIT, { putInt("launch_mode", 0) })
            surface("touchpad-landscape", dark, t, LANDSCAPE, { putInt("launch_mode", 1) })
            surface("touchpad-portrait", dark, t, PORTRAIT, { putInt("launch_mode", 1) })
            for (profile in listOf("native_hat", "android", "web_buttons")) {
                surface("gamepad-xbox-$profile-landscape", dark, t, LANDSCAPE, {
                    putInt("launch_mode", 2); putString(GAMEPAD_DPAD_MODE_PREFERENCE, profile)
                })
            }
            surface("gamepad-ps5-native_hat-landscape", dark, t, LANDSCAPE, {
                putInt("launch_mode", 2); putString(GAMEPAD_DPAD_MODE_PREFERENCE, "native_hat")
            }) { composeRule.onNodeWithTag("console_selector_pill").performClick() }
            surface("gamepad-xbox-editmode-landscape", dark, t, LANDSCAPE, {
                putInt("launch_mode", 2)
            }) { composeRule.onNodeWithText("Edit Layout").performClick() }
            surface("gamepad-portrait", dark, t, PORTRAIT, { putInt("launch_mode", 2) })
            surface("media-landscape", dark, t, LANDSCAPE, { putInt("launch_mode", 4) })
        }
    }

    @Test
    fun mediaUpright() {
        grantBluetooth()
        val manager = (app as BlukeApplication).bluetoothKeyboardManager
        themes { dark, t ->
            attempt("media-upright-$t") {
                baselinePrefs(); theme(dark, PORTRAIT)
                manager.apply(connectedState)
                compose {
                    MediaPresentationView(
                        btManager = manager, onClose = {}, launchMode = InputMode.MEDIA_PRESENTATION.id,
                        onModeChange = {}, sharedPrefs = prefs, isConnected = true,
                        postureOverride = MultimediaPosture.PORTRAIT_HELD,
                    )
                }
                shot("media-upright-portrait-$t")
            }
        }
    }

    @Test
    fun layoutSwitcherOverlay() {
        themes { dark, t ->
            attempt("layout-switcher-$t") {
                baselinePrefs(); theme(dark, LANDSCAPE)
                val keyboardTheme = KeyboardThemeRepository(app).selectedTheme()
                compose {
                    Box(Modifier.fillMaxSize().background(Color(keyboardTheme.caseArgb)).padding(top = 38.dp)) {
                        KeyboardView(
                            geometry = KeyboardGeometry.COMPACT_75, theme = keyboardTheme,
                            activePressedKeys = emptyList(), isCapsLockActive = false,
                            isNumLockActive = true, isScrollLockActive = false, onKeyPressChange = { _, _ -> },
                        )
                        KeyboardLayoutSwitcherOverlay(
                            visible = true,
                            selectedLayout = KeyboardCharacterLayout.entries[1],
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                }
                shot("keyboard-layout-switcher-overlay-landscape-$t")
            }
        }
    }

    // ---------- settings activities ----------

    private val activities: List<Pair<String, Class<out Activity>>> = listOf(
        "settings" to SettingsActivity::class.java,
        "lookandfeel" to LookAndFeelActivity::class.java,
        "darktheme" to DarkThemeActivity::class.java,
        "keyboardthemes" to KeyboardThemesActivity::class.java,
        "keyboardsettings" to KeyboardSettingsActivity::class.java,
        "keyboardlayouts" to KeyboardLayoutsActivity::class.java,
        "soundpacks" to SoundPacksActivity::class.java,
        "touchpadsettings" to TouchpadSettingsActivity::class.java,
        "controllersettings" to ControllerSettingsActivity::class.java,
        "behavior" to BehaviorActivity::class.java,
        "quickcycle" to QuickCycleActivity::class.java,
        "help" to HelpActivity::class.java,
        "about" to AboutActivity::class.java,
        "licenses" to LicensesActivity::class.java,
        "developeroptions" to DeveloperOptionsActivity::class.java,
        "developerlogs" to DeveloperLogsActivity::class.java,
    )

    @Test
    fun settingsActivities() {
        grantBluetooth()
        themes { dark, t ->
            for ((name, cls) in activities) {
                attempt("$name-$t") {
                    baselinePrefs(); theme(dark, PORTRAIT)
                    prefs.edit().putBoolean("is_developer_mode", true).commit()
                    launch(cls)
                    shot("$name-$t")
                }
            }
        }
        for ((name, cls) in activities) {
            attempt("$name-full") {
                baselinePrefs(); theme(false, TALL)
                prefs.edit().putBoolean("is_developer_mode", true).commit()
                launch(cls)
                shot("$name-full-light")
            }
        }
    }
}
