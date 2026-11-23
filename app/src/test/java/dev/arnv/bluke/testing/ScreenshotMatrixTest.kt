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
import java.io.File
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

        captureMatrix(name = "matrix-multimedia", landscape = true) {
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

        for (dark in listOf("light", "dark")) {
            for (scale in listOf("1.0", "1.3")) {
                val file = File("src/test/screenshots/matrix-multimedia-$dark-fs$scale-land.png")
                assertTrue("missing ${file.path}", file.isFile)
            }
        }
        assertEquals(0, appPrefs.getInt("theme_mode", -1))
        assertTrue(appPrefs.getBoolean("dynamic_color", false))
    }

    // MediaPresentationView paints fixed dark colors, so it can't show that the theme switch took
    // effect; a Surface and Text that follow the theme can.
    @Test
    fun themeAndFontScaleChangeThePixels() {
        captureMatrix(name = "matrix-probe", landscape = false) {
            Surface(color = MaterialTheme.colorScheme.background) {
                Box(Modifier.size(width = 200.dp, height = 80.dp)) {
                    Text("Bluke 123", color = MaterialTheme.colorScheme.onBackground)
                }
            }
        }

        fun bytes(dark: String, scale: String) =
            File("src/test/screenshots/matrix-probe-$dark-fs$scale.png").readBytes()
        assertFalse(bytes("light", "1.0").contentEquals(bytes("dark", "1.0")))
        assertFalse(bytes("light", "1.0").contentEquals(bytes("light", "1.3")))
        assertFalse(bytes("dark", "1.0").contentEquals(bytes("dark", "1.3")))
    }
}
