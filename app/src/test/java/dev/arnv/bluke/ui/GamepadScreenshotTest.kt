package dev.arnv.bluke.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.test.core.app.ApplicationProvider
import dev.arnv.bluke.bluetooth.BluetoothKeyboardManager
import dev.arnv.bluke.bluetooth.GamepadDpadOutputMode
import dev.arnv.bluke.testing.captureMatrix
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w914dp-h411dp-land-420dpi", sdk = [36])
class GamepadScreenshotTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = BluetoothKeyboardManager(context)
    private val host = SurfaceHost(SurfaceHostStatus.Connected, "Living Room PC")

    @After
    fun closeManager() {
        manager.close()
    }

    private fun prefs(console: String, profile: GamepadDpadOutputMode = GamepadDpadOutputMode.NATIVE_HAT): SharedPreferences =
        context.getSharedPreferences("gamepad-screenshot", Context.MODE_PRIVATE).also {
            it.edit().clear().putString(GAMEPAD_CONSOLE_PREFERENCE, console).commit()
            GamepadTuneState(profile).write(it)
        }

    private fun capture(
        name: String,
        prefs: SharedPreferences,
        fontScales: List<Float> = listOf(1.0f, 1.3f),
        editMode: Boolean = false,
        tuneOpen: Boolean = false,
        landscape: Boolean = true,
    ) = captureMatrix(name = name, landscape = landscape, fontScales = fontScales) {
        SurfaceTheme {
            GamepadView(
                btManager = manager,
                chrome = previewChrome(InputMode.GAMEPAD, host),
                sharedPrefs = prefs,
                caseBrush = SolidColor(Color.Unspecified),
                initialEditMode = editMode,
                initialTuneOpen = tuneOpen,
            )
        }
    }

    @Test
    fun xboxStyle() = capture("gamepad-xbox", prefs("xbox_series"))

    @Test
    fun playStationStyle() = capture("gamepad-playstation", prefs("playstation_5"))

    @Test
    fun eachProfileIsNamedOnTheSurface() {
        GamepadDpadOutputMode.entries.forEach { profile ->
            capture("gamepad-profile-${profile.preferenceValue}", prefs("xbox_series", profile), fontScales = listOf(1.0f))
        }
    }

    @Test
    fun editMode() = capture("gamepad-edit", prefs("xbox_series"), fontScales = listOf(1.0f), editMode = true)

    @Test
    fun tuneSheetOpen() = capture("gamepad-tune", prefs("playstation_5"), tuneOpen = true)

    @Test
    @Config(qualifiers = "w800dp-h360dp-land-xhdpi", sdk = [36])
    fun smallLandscapeStaysClear() {
        capture("gamepad-xbox-small", prefs("xbox_series"), fontScales = listOf(1.3f))
        capture("gamepad-playstation-small", prefs("playstation_5"), fontScales = listOf(1.3f))
    }

    @Test
    @Config(qualifiers = "w411dp-h914dp-port-420dpi", sdk = [36])
    fun portraitAsksToTurnThePhone() = capture("gamepad-portrait", prefs("xbox_series"), landscape = false)
}
