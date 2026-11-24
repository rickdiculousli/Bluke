package dev.arnv.bluke.sound

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SoundPreferencesTest {
    private val preferences by lazy {
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("sound-migration-test", Context.MODE_PRIVATE)
    }

    @Before
    fun clearPreferences() {
        preferences.edit().clear().commit()
    }

    @Test
    fun freshInstallDefaultsSoundOn() {
        migrateSoundPreferences(preferences)

        assertTrue(preferences.getBoolean(KEY_SOUND_ENABLED_PREFERENCE, false))
    }

    @Test
    fun legacyToggleMigratesForExistingInstall() {
        preferences.edit().putBoolean("sound_toggle", true).commit()

        migrateSoundPreferences(preferences)

        assertTrue(preferences.getBoolean(KEY_SOUND_ENABLED_PREFERENCE, false))
    }

    @Test
    fun explicitMuteIsPreserved() {
        preferences.edit()
            .putBoolean(KEY_SOUND_ENABLED_PREFERENCE, false)
            .putBoolean("sound_toggle", true)
            .commit()

        migrateSoundPreferences(preferences)

        assertFalse(preferences.getBoolean(KEY_SOUND_ENABLED_PREFERENCE, true))
        assertFalse(preferences.getBoolean("sound_toggle", true))
    }

    @Test
    fun builtInSoundSelectionSurvivesActivityRecreation() {
        assertEquals(SwitchType.CHERRY_MX_BROWN, selectedBuiltInSound(preferences))

        preferences.edit()
            .putString(SELECTED_BUILT_IN_SOUND_PREFERENCE, SwitchType.TOPRE.name)
            .commit()

        assertEquals(SwitchType.TOPRE, selectedBuiltInSound(preferences))
        assertEquals("built_in:TOPRE", builtInSoundProfileId(SwitchType.TOPRE))
    }

    @Test
    fun choosingAPackWhileMutedTurnsKeySoundsOn() {
        turnKeySoundsOff(preferences)

        assertTrue(selectKeySound(preferences, customSoundProfileId("office")))

        assertTrue(preferences.getBoolean(KEY_SOUND_ENABLED_PREFERENCE, false))
    }

    @Test
    fun choosingACustomPackStoresItAndChoosingABuiltInClearsIt() {
        selectKeySound(preferences, customSoundProfileId("office"))
        assertEquals("office", preferences.getString(CUSTOM_SOUND_PACK_PREFERENCE, null))

        selectKeySound(preferences, builtInSoundProfileId(SwitchType.TOPRE))

        assertNull(preferences.getString(CUSTOM_SOUND_PACK_PREFERENCE, null))
        assertEquals(SwitchType.TOPRE, selectedBuiltInSound(preferences))
    }

    @Test
    fun anUnknownSoundChangesNothing() {
        turnKeySoundsOff(preferences)
        val before = preferences.all.toMap()

        assertFalse(selectKeySound(preferences, "built_in:NOT_A_SWITCH"))
        assertFalse(selectKeySound(preferences, customSoundProfileId("")))
        assertFalse(selectKeySound(preferences, "office"))

        assertEquals(before, preferences.all.toMap())
    }

    @Test
    fun turningSoundsOffKeepsTheChosenSound() {
        selectKeySound(preferences, customSoundProfileId("office"))

        turnKeySoundsOff(preferences)

        assertFalse(preferences.getBoolean(KEY_SOUND_ENABLED_PREFERENCE, true))
        assertEquals("office", preferences.getString(CUSTOM_SOUND_PACK_PREFERENCE, null))
    }

    @Test
    fun theOldSoundCycleKeysAreDroppedOnceAndTheChoiceStays() {
        preferences.edit()
            .putStringSet("cycle_key_sounds", setOf("builtin:TOPRE"))
            .putInt("sound_cycle_schema", 1)
            .putString("selected_builtin_key_sound", "TOPRE")
            .commit()

        migrateSoundCyclePreferences(preferences)
        migrateSoundCyclePreferences(preferences)

        assertFalse(preferences.contains("cycle_key_sounds"))
        assertFalse(preferences.contains("sound_cycle_schema"))
        assertEquals("TOPRE", preferences.getString("selected_builtin_key_sound", null))
    }
}
