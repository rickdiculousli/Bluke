package dev.arnv.bluke.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LayoutRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun offsetsSavedForTheOldLayoutAreClearedOnceAndLegacyValuesStayGone() = runBlocking {
        // An install from before the rebuilt gamepad: offsets in the DataStore and, from builds
        // older still, in SharedPreferences that were never migrated.
        context.gamepadLayoutDataStore.edit {
            it[floatPreferencesKey("xbox_series_left_stick_x")] = 40f
            it[floatPreferencesKey("playstation_5_face_buttons_scale")] = 1.4f
        }
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit()
            .putFloat("playstation_5_dpad_y", -12f)
            .commit()

        val first = LayoutRepository(context)
        assertEquals(emptyMap<String, Float>(), first.load("xbox_series"))
        assertEquals(emptyMap<String, Float>(), first.load("playstation_5"))

        // An offset saved against the new layout survives later launches: the reset runs once.
        first.save(mapOf("xbox_series_left_stick_x" to 8f))
        val second = LayoutRepository(context)
        assertEquals(mapOf("xbox_series_left_stick_x" to 8f), second.load("xbox_series"))
        assertEquals(emptyMap<String, Float>(), second.load("playstation_5"))
    }
}
