package dev.arnv.bluke

import android.content.Context
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import dev.arnv.bluke.ui.theme.PALETTE_STYLE_PREFERENCE
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Look & Feel palette picker offers only the styles that still exist, with the stored one selected. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LookAndFeelPaletteTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    @Test
    fun thePickerSelectsTheStoredStyleAndStoresItsValue() {
        prefs.edit().clear().putString(PALETTE_STYLE_PREFERENCE, "Monochrome").commit()
        ActivityScenario.launch(LookAndFeelActivity::class.java).use {
            // The row's subtitle names the stored style.
            compose.onNode(hasText("Palette style") and hasText("Monochrome") and hasClickAction()).performScrollTo().performClick()
            compose.onNode(hasText("Accent colour") and hasClickAction()).assertExists()
            compose.onNodeWithText("Vibrant").assertDoesNotExist()
            compose.onNode(hasText("Accent colour") and hasClickAction()).performClick()
            assertEquals("Default", prefs.getString(PALETTE_STYLE_PREFERENCE, null))
        }
    }
}
