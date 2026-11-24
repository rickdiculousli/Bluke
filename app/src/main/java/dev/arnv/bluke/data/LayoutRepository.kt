package dev.arnv.bluke.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal val Context.gamepadLayoutDataStore by preferencesDataStore(name = "gamepad_layout")

class LayoutRepository(context: Context) {
    private val appContext = context.applicationContext
    private val dataStore = appContext.gamepadLayoutDataStore
    private val migrationMutex = Mutex()

    suspend fun load(consoleId: String): Map<String, Float> {
        migrateLegacyValuesOnce()
        return dataStore.data.first().asMap().mapNotNull { (key, value) ->
            val name = key.name
            if (name.startsWith("${consoleId}_") && value is Float) name to value else null
        }.toMap()
    }

    suspend fun save(values: Map<String, Float>) {
        if (values.isEmpty()) return
        migrateLegacyValuesOnce()
        dataStore.edit { preferences ->
            values.forEach { (name, value) -> preferences[floatPreferencesKey(name)] = value }
        }
    }

    suspend fun clear(consoleId: String) {
        migrateLegacyValuesOnce()
        dataStore.edit { preferences ->
            val names = preferences.asMap().keys
                .filter { it.name.startsWith("${consoleId}_") }
                .map { it.name }
            names.forEach { preferences.remove(floatPreferencesKey(it)) }
        }
    }

    private suspend fun migrateLegacyValuesOnce() = migrationMutex.withLock {
        resetForRebuiltLayoutOnce()
        val current = dataStore.data.first()
        if (current[MIGRATION_COMPLETE] == true) return@withLock

        val legacyValues = appContext.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).all
            .filter { (key, value) ->
                CONSOLE_PREFIXES.any(key::startsWith) && value is Float
            }
        dataStore.edit { preferences ->
            // Another repository instance may have migrated while we awaited edit().
            if (preferences[MIGRATION_COMPLETE] == true) return@edit
            legacyValues.forEach { (name, value) ->
                preferences[floatPreferencesKey(name)] = value as Float
            }
            preferences[MIGRATION_COMPLETE] = true
        }
    }

    /**
     * The rebuilt gamepad moved every control's base position, so offsets saved against the old
     * layout would land controls in odd places. Clear them once, for every console. Legacy
     * SharedPreferences values are offsets against that same old layout, so the reset also marks
     * the legacy migration complete: it must never copy them back afterwards.
     */
    private suspend fun resetForRebuiltLayoutOnce() {
        if (dataStore.data.first()[REBUILT_LAYOUT_RESET] == true) return
        dataStore.edit { preferences ->
            if (preferences[REBUILT_LAYOUT_RESET] == true) return@edit
            preferences.asMap()
                .filterValues { it is Float }
                .keys
                .forEach { preferences.remove(it) }
            preferences[MIGRATION_COMPLETE] = true
            preferences[REBUILT_LAYOUT_RESET] = true
        }
    }

    private companion object {
        val MIGRATION_COMPLETE = booleanPreferencesKey("legacy_layout_migrated")
        val REBUILT_LAYOUT_RESET = booleanPreferencesKey("rebuilt_layout_reset")
        val CONSOLE_PREFIXES = listOf("xbox_series_", "playstation_5_")
    }
}
