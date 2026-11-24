package dev.arnv.bluke.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LockLightMigrationTest {
    private val preferences by lazy {
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("lock-light-migration-test", Context.MODE_PRIVATE)
            .also { it.edit().clear().commit() }
    }

    @Test
    fun theOldLocalModeChoiceIsDropped() {
        preferences.edit().putString("lock_sync_mode", "device").putBoolean("auto_connect", false).commit()

        migrateLockLightPreferences(preferences)

        assertFalse(preferences.contains("lock_sync_mode"))
        assertTrue(preferences.contains("auto_connect"))
    }

    @Test
    fun runningTwiceChangesNothingMore() {
        migrateLockLightPreferences(preferences)
        migrateLockLightPreferences(preferences)

        assertFalse(preferences.contains("lock_sync_mode"))
    }
}
