package dev.arnv.bluke.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.arnv.bluke.ui.theme.PALETTE_STYLE_PREFERENCE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SurfacePreferencesTest {
    private val prefs by lazy {
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("surface-prefs-test", Context.MODE_PRIVATE)
    }

    @Before
    fun clear() {
        prefs.edit().clear().commit()
    }

    private fun migrate(vararg values: Pair<String, Boolean>): Boolean {
        prefs.edit().apply { values.forEach { (key, value) -> putBoolean(key, value) } }.commit()
        migrateSurfacePreferences(prefs)
        assertFalse(prefs.contains("touchpad_vibration_enabled"))
        assertFalse(prefs.contains("gamepad_vibration_enabled"))
        return prefs.getBoolean(HAPTICS_PREFERENCE, true)
    }

    @Test
    fun aFreshInstallKeepsHapticsOn() {
        migrateSurfacePreferences(prefs)
        assertFalse(prefs.contains(HAPTICS_PREFERENCE))
        assertTrue(prefs.getBoolean(HAPTICS_PREFERENCE, true))
    }

    @Test
    fun anExplicitOffOnAnySurfaceTurnsHapticsOff() {
        assertFalse(migrate("touchpad_vibration_enabled" to false))
        clear()
        assertFalse(migrate("gamepad_vibration_enabled" to false, "touchpad_vibration_enabled" to true))
        clear()
        assertFalse(migrate(HAPTICS_PREFERENCE to false, "touchpad_vibration_enabled" to true, "gamepad_vibration_enabled" to true))
    }

    @Test
    fun everySwitchOnKeepsHapticsOn() {
        assertTrue(migrate(HAPTICS_PREFERENCE to true, "touchpad_vibration_enabled" to true, "gamepad_vibration_enabled" to true))
        clear()
        assertTrue(migrate("gamepad_vibration_enabled" to true))
    }

    @Test
    fun migratingTwiceChangesNothing() {
        migrate("touchpad_vibration_enabled" to false)
        prefs.edit().putBoolean(HAPTICS_PREFERENCE, true).commit()
        migrateSurfacePreferences(prefs)
        assertTrue(prefs.getBoolean(HAPTICS_PREFERENCE, false))
    }

    @Test
    fun aRetiredPaletteStyleIsRewrittenAsDefault() {
        prefs.edit().putString(PALETTE_STYLE_PREFERENCE, "Fruit Salad").commit()
        migrateSurfacePreferences(prefs)
        assertEquals("Default", prefs.getString(PALETTE_STYLE_PREFERENCE, null))
        prefs.edit().putString(PALETTE_STYLE_PREFERENCE, "Monochrome").commit()
        migrateSurfacePreferences(prefs)
        assertEquals("Monochrome", prefs.getString(PALETTE_STYLE_PREFERENCE, null))
    }
}
