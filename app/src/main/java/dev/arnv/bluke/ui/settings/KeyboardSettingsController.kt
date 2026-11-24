package dev.arnv.bluke.ui.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import dev.arnv.bluke.KeyboardThemesActivity
import dev.arnv.bluke.SoundPacksActivity
import dev.arnv.bluke.data.KeyboardThemeRepository
import dev.arnv.bluke.sound.CustomSoundPackRepository
import dev.arnv.bluke.sound.KEY_SOUND_ENABLED_PREFERENCE
import dev.arnv.bluke.sound.KeyboardSoundSynthesizer
import dev.arnv.bluke.sound.selectKeySound
import dev.arnv.bluke.sound.turnKeySoundsOff
import dev.arnv.bluke.ui.KeyboardThemeCatalog
import dev.arnv.bluke.ui.KeyboardTuneOptions
import dev.arnv.bluke.ui.KeyboardTuneState

private const val KEY_SENSITIVITY_PREFERENCE = "key_sensitivity"

/**
 * Reads Settings › Keyboard from preferences into [state] and writes each change back. The Settings
 * activity makes one, calls [reload] on resume (Themes and Sound packs change things from their own
 * screens) and [release] when it's destroyed. Reading writes nothing, so opening the screen leaves
 * every preference as it was.
 */
class KeyboardSettingsController(private val context: Context) : KeyboardSettingsActions {
    private val preferences = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    private val themes = KeyboardThemeRepository(context)
    private val soundPacks = CustomSoundPackRepository(context)
    private var synthesizer: KeyboardSoundSynthesizer? = null

    var state: KeyboardSettingsState by mutableStateOf(read())
        private set

    fun reload() {
        state = read()
    }

    fun release() {
        synthesizer?.release()
        synthesizer = null
    }

    private fun read(): KeyboardSettingsState {
        val tune = KeyboardTuneState.read(preferences)
        return KeyboardSettingsState(
            tune = tune,
            options = KeyboardTuneOptions(
                themes = themes.allThemes(),
                customSoundPacks = soundPacks.listPacks().map { it.id to it.name },
            ),
            keySensitivity = preferences.getFloat(KEY_SENSITIVITY_PREFERENCE, DEFAULT_KEY_SENSITIVITY),
            keySoundsOn = preferences.getBoolean(KEY_SOUND_ENABLED_PREFERENCE, true),
            theme = themes.resolve(tune.themeId) ?: KeyboardThemeCatalog.defaultTheme,
        )
    }

    /** Stores what the keyboard's Tune content chose, the way its Tune sheet does. */
    override fun setTune(next: KeyboardTuneState) {
        state.tune.writeChange(preferences, next)
        state = read()
    }

    override fun setKeySensitivity(value: Float) {
        val clamped = value.coerceIn(0f, 10f)
        preferences.edit { putFloat(KEY_SENSITIVITY_PREFERENCE, clamped) }
        state = state.copy(keySensitivity = clamped)
    }

    override fun openThemes() {
        context.startActivity(launchable(Intent(context, KeyboardThemesActivity::class.java)))
    }

    override fun openSoundPacks() {
        context.startActivity(launchable(Intent(context, SoundPacksActivity::class.java)))
    }

    /** Chooses [id], turns key sounds on and plays it once. */
    override fun selectSound(id: String) {
        if (!selectKeySound(preferences, id)) return
        state = read()
        playSound(id)
    }

    override fun turnSoundsOff() {
        turnKeySoundsOff(preferences)
        state = state.copy(keySoundsOn = false)
    }

    /** Plays a sample of [id], audible even with key sounds off; the selection stays as it is. */
    override fun playSound(id: String) {
        val player = synthesizer ?: KeyboardSoundSynthesizer(context.applicationContext).also { synthesizer = it }
        player.previewSoundProfile(id)
    }

    /** Starting an activity from a non-activity context needs a new task. */
    private fun launchable(intent: Intent): Intent =
        if (context is Activity) intent else intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
