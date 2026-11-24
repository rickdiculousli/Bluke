package dev.arnv.bluke.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.arnv.bluke.KeyboardThemeLibrary
import dev.arnv.bluke.KeyboardThemesScreen
import dev.arnv.bluke.SoundPacksContent
import dev.arnv.bluke.SoundPacksScreen
import dev.arnv.bluke.soundPackChoices
import dev.arnv.bluke.sound.SwitchType
import dev.arnv.bluke.sound.builtInSoundProfileId
import dev.arnv.bluke.sound.customSoundProfileId
import dev.arnv.bluke.testing.captureMatrix
import dev.arnv.bluke.ui.CUSTOM_KEYBOARD_THEME_PREFIX
import dev.arnv.bluke.ui.KeyboardCharacterLayout
import dev.arnv.bluke.ui.KeyboardGeometry
import dev.arnv.bluke.ui.KeyboardModifierStyle
import dev.arnv.bluke.ui.KeyboardSound
import dev.arnv.bluke.ui.KeyboardThemeCatalog
import dev.arnv.bluke.ui.KeyboardTuneOptions
import dev.arnv.bluke.ui.KeyboardTuneState
import dev.arnv.bluke.ui.SettingsSectionCard
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h3600dp-port-xhdpi", sdk = [36])
class KeyboardSettingsScreenshotTest {
    private val scales = listOf(1.0f, 1.3f, 2.0f)

    private val fixture = KeyboardSettingsState(
        tune = KeyboardTuneState(
            geometry = KeyboardGeometry.COMPACT_75,
            characterLayout = KeyboardCharacterLayout.US_QWERTY,
            modifierStyle = KeyboardModifierStyle.MAC,
            themeId = KeyboardThemeCatalog.defaultTheme.id,
            sound = KeyboardSound.BuiltIn(SwitchType.HOLY_PANDA),
        ),
        options = KeyboardTuneOptions(KeyboardThemeCatalog.builtIns, listOf("office" to "Office Pack")),
        keySensitivity = 6f,
        keySoundsOn = true,
        theme = KeyboardThemeCatalog.defaultTheme,
    )

    @Test
    fun keyboardSection() = captureMatrix(name = "settings-keyboard", fontScales = scales) {
        Section(fixture)
    }

    @Test
    fun keyboardSectionWithSoundOpen() =
        captureMatrix(name = "settings-keyboard-open", fontScales = scales) {
            Section(fixture, soundOpen = true)
        }

    @Test
    fun keyboardSectionWithThemeOpen() = captureMatrix(name = "settings-keyboard-theme", fontScales = scales) {
        Section(fixture, themeOpen = true)
    }

    @Test
    fun keyboardSectionWithSoundsOff() = captureMatrix(name = "settings-keyboard-soundoff", fontScales = scales) {
        Section(fixture.copy(keySoundsOn = false), soundOpen = true)
    }

    @Test
    fun soundPacks() = captureMatrix(name = "soundpacks", fontScales = scales) {
        SoundPacksScreen(importing = false, onImport = {}, onBack = {}) { padding ->
            SoundPacksContent(
                modifier = Modifier.padding(padding),
                choices = soundPackChoices(emptyList()) +
                    dev.arnv.bluke.SoundPackChoice(customSoundProfileId("office"), "Office Pack", imported = true),
                selectedId = builtInSoundProfileId(SwitchType.HOLY_PANDA),
                soundsOn = true,
                onSelect = {},
                onPlay = {},
                onDelete = {},
            )
        }
    }

    // One custom theme beside the built-ins, so the library shows both of its lists.
    private val customTheme = KeyboardThemeCatalog.defaultTheme.copy(
        id = "${CUSTOM_KEYBOARD_THEME_PREFIX}fixture",
        name = "${KeyboardThemeCatalog.defaultTheme.name} copy",
        editable = true,
    )

    @Test
    fun keyboardThemes() = captureMatrix(name = "keyboardthemes", fontScales = scales) { Themes() }

    @Composable
    private fun Themes() {
        KeyboardThemesScreen(editing = false, onBack = {}) { padding ->
            KeyboardThemeLibrary(
                modifier = Modifier.padding(padding),
                selectedThemeId = customTheme.id,
                customThemes = listOf(customTheme),
                onCreate = {},
                onSelect = {},
                onCopy = {},
                onEdit = {},
                onDelete = {},
            )
        }
    }

    @Composable
    private fun Section(state: KeyboardSettingsState, soundOpen: Boolean = false, themeOpen: Boolean = false) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(16.dp)) {
            SettingsSectionCard(
                title = "Keyboard",
                summary = state.summary(),
                icon = Icons.Default.Keyboard,
                expanded = true,
                onToggle = {},
            ) {
                KeyboardSettingsBody(
                    state,
                    NoActions,
                    haptics = null,
                    initiallySoundOpen = soundOpen,
                    initiallyThemeOpen = themeOpen,
                )
            }
        }
    }

    private object NoActions : KeyboardSettingsActions {
        override fun setTune(next: KeyboardTuneState) = Unit
        override fun setKeySensitivity(value: Float) = Unit
        override fun openThemes() = Unit
        override fun selectSound(id: String) = Unit
        override fun turnSoundsOff() = Unit
        override fun playSound(id: String) = Unit
        override fun openSoundPacks() = Unit
    }
}
