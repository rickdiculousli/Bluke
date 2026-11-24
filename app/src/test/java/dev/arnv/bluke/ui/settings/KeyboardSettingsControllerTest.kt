package dev.arnv.bluke.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.arnv.bluke.data.KEYBOARD_GEOMETRY_PREFERENCE
import dev.arnv.bluke.sound.KEY_SOUND_ENABLED_PREFERENCE
import dev.arnv.bluke.sound.SELECTED_BUILT_IN_SOUND_PREFERENCE
import dev.arnv.bluke.sound.SwitchType
import dev.arnv.bluke.sound.builtInSoundProfileId
import dev.arnv.bluke.ui.KeyboardGeometry
import dev.arnv.bluke.ui.KeyboardModifierStyle
import dev.arnv.bluke.ui.KeyboardSound
import dev.arnv.bluke.ui.KeyboardTuneState
import org.junit.After
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
class KeyboardSettingsControllerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    private var controller: KeyboardSettingsController? = null

    @Before
    fun clearPreferences() {
        preferences.edit().clear().commit()
    }

    @After
    fun releaseController() {
        controller?.release()
    }

    private fun open() = KeyboardSettingsController(context).also { controller = it }

    @Test
    fun openingTheSectionWritesNoPreference() {
        preferences.edit()
            .putString(KEYBOARD_GEOMETRY_PREFERENCE, KeyboardGeometry.HHKB_60.name)
            .putString(SELECTED_BUILT_IN_SOUND_PREFERENCE, SwitchType.TOPRE.name)
            .putBoolean(KEY_SOUND_ENABLED_PREFERENCE, false)
            .commit()
        val before = preferences.all.toMap()

        val state = open().state

        assertEquals(before, preferences.all.toMap())
        assertEquals(KeyboardGeometry.HHKB_60, state.tune.geometry)
        assertEquals(builtInSoundProfileId(SwitchType.TOPRE), state.selectedSoundId)
        assertFalse(state.keySoundsOn)
    }

    @Test
    fun choosingASoundWhileMutedTurnsSoundsOn() {
        preferences.edit().putBoolean(KEY_SOUND_ENABLED_PREFERENCE, false).commit()
        val controller = open()

        controller.selectSound(builtInSoundProfileId(SwitchType.HOLY_PANDA))

        assertTrue(preferences.getBoolean(KEY_SOUND_ENABLED_PREFERENCE, false))
        assertEquals(SwitchType.HOLY_PANDA.name, preferences.getString(SELECTED_BUILT_IN_SOUND_PREFERENCE, null))
        assertTrue(controller.state.keySoundsOn)
        assertEquals(builtInSoundProfileId(SwitchType.HOLY_PANDA), controller.state.selectedSoundId)
    }

    @Test
    fun turningSoundsOffKeepsTheChosenSound() {
        val controller = open()
        controller.selectSound(builtInSoundProfileId(SwitchType.TOPRE))

        controller.turnSoundsOff()

        assertFalse(preferences.getBoolean(KEY_SOUND_ENABLED_PREFERENCE, true))
        assertEquals(builtInSoundProfileId(SwitchType.TOPRE), open().state.selectedSoundId)
    }

    @Test
    fun aTuneChangeIsStoredUnderTheKeyboardsKeys() {
        val controller = open()

        controller.setTune(controller.state.tune.copy(geometry = KeyboardGeometry.INLINE_75, modifierStyle = KeyboardModifierStyle.MAC))

        assertEquals(KeyboardGeometry.INLINE_75.name, preferences.getString(KEYBOARD_GEOMETRY_PREFERENCE, null))
        assertEquals(KeyboardModifierStyle.MAC, KeyboardTuneState.read(preferences).modifierStyle)
        assertEquals(KeyboardGeometry.INLINE_75, controller.state.tune.geometry)
    }

    @Test
    fun summaryNamesTheChoicesAndSaysWhenSoundsAreOff() {
        val base = open().state
        val state = base.copy(
            tune = base.tune.copy(geometry = KeyboardGeometry.HHKB_60, sound = KeyboardSound.BuiltIn(SwitchType.TOPRE)),
        )

        assertEquals("HHKB 60% · QWERTY · PC · Oblivion · Topre 45g", state.summary())
        assertEquals("HHKB 60% · QWERTY · PC · Oblivion · key sounds off", state.copy(keySoundsOn = false).summary())
        assertEquals(
            "HHKB 60% · QWERTY · PC · Oblivion · Topre 45g · touch area Broad\u00A0(9)",
            state.copy(keySensitivity = 9f).summary(),
        )
    }
}
