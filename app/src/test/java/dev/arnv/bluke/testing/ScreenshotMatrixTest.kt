package dev.arnv.bluke.testing

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.arnv.bluke.bluetooth.BluetoothKeyboardManager
import dev.arnv.bluke.ui.InputMode
import dev.arnv.bluke.ui.MediaPresentationView
import dev.arnv.bluke.ui.MultimediaPosture
import android.graphics.Bitmap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w800dp-h360dp-land-xhdpi", sdk = [36])
class ScreenshotMatrixTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = BluetoothKeyboardManager(context)

    @After
    fun closeManager() {
        manager.close()
    }

    @Test
    fun capturesLightAndDarkAtEachFontScale() {
        val appPrefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        appPrefs.edit().putInt("theme_mode", 0).putBoolean("dynamic_color", true).commit()
        val preferences = context.getSharedPreferences("screenshot-matrix", Context.MODE_PRIVATE)

        val images = mutableMapOf<Pair<Boolean, Float>, IntArray>()
        captureMatrix(
            name = "matrix-multimedia",
            landscape = true,
            onCaptured = { dark, scale, image -> images[dark to scale] = image.pixels() },
        ) {
            MediaPresentationView(
                btManager = manager,
                onClose = {},
                launchMode = InputMode.MEDIA_PRESENTATION.id,
                onModeChange = {},
                sharedPrefs = preferences,
                isConnected = false,
                postureOverride = MultimediaPosture.LANDSCAPE,
            )
        }

        assertEquals(setOf(false to 1.0f, false to 1.3f, true to 1.0f, true to 1.3f), images.keys)
        // The surface paints fixed dark colors, so only the font scale shows in its pixels.
        assertFalse(images.getValue(false to 1.0f).contentEquals(images.getValue(false to 1.3f)))
        assertEquals(0, appPrefs.getInt("theme_mode", -1))
        assertTrue(appPrefs.getBoolean("dynamic_color", false))
    }

    // MediaPresentationView paints fixed dark colors, so it can't show that the theme switch took
    // effect; a Surface and Text that follow the theme can.
    @Test
    fun themeAndFontScaleChangeThePixels() {
        val images = mutableMapOf<Pair<Boolean, Float>, IntArray>()
        captureMatrix(
            name = "matrix-probe",
            landscape = false,
            onCaptured = { dark, scale, image -> images[dark to scale] = image.pixels() },
        ) {
            Surface(color = MaterialTheme.colorScheme.background) {
                Box(Modifier.size(width = 200.dp, height = 80.dp)) {
                    Text("Bluke 123", color = MaterialTheme.colorScheme.onBackground)
                }
            }
        }

        fun pixels(dark: Boolean, scale: Float) = images.getValue(dark to scale)
        assertFalse(pixels(false, 1.0f).contentEquals(pixels(true, 1.0f)))
        assertFalse(pixels(false, 1.0f).contentEquals(pixels(false, 1.3f)))
        assertFalse(pixels(true, 1.0f).contentEquals(pixels(true, 1.3f)))
    }

    private fun Bitmap.pixels(): IntArray = IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }
}
