package dev.arnv.bluke.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import dev.arnv.bluke.ui.HapticEvent
import dev.arnv.bluke.ui.KeyboardThemeCatalog
import dev.arnv.bluke.ui.KeyboardTuneOptions
import dev.arnv.bluke.ui.KeyboardTuneState
import dev.arnv.bluke.ui.LocalHapticPerformer
import dev.arnv.bluke.ui.RecordingHapticPerformer
import dev.arnv.bluke.ui.TuneChoice
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings' sliders tick like the Tune sheet's, its single-choice chips say they are radio buttons, and
 * [TuneChoice] picks segments, radio rows or chips by its one rule. Native graphics, so text has real widths.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h1600dp-port-hdpi", sdk = [36])
class SettingsControlsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val recorder = RecordingHapticPerformer()
    private val keySensitivity = mutableListOf<Float>()
    private val keyboardActions = object : KeyboardSettingsActions {
        override fun setTune(next: KeyboardTuneState) = Unit
        override fun setKeySensitivity(value: Float) {
            keySensitivity += value
        }
        override fun openThemes() = Unit
        override fun selectSound(id: String) = Unit
        override fun turnSoundsOff() = Unit
        override fun playSound(id: String) = Unit
        override fun openSoundPacks() = Unit
    }

    private fun show(section: SettingsSection, hapticsEnabled: Boolean = true) {
        compose.setContent {
            CompositionLocalProvider(LocalHapticPerformer provides recorder) {
                SettingsContent(
                    state = SettingsState(hapticsEnabled = hapticsEnabled),
                    actions = SettingsActions.None,
                    keyboard = KeyboardSettingsState(
                        KeyboardTuneState(),
                        KeyboardTuneOptions(KeyboardThemeCatalog.builtIns, emptyList()),
                        6f,
                        true,
                    ),
                    keyboardActions = keyboardActions,
                    expanded = setOf(section),
                    onToggleSection = {},
                    connectionAdvancedExpanded = false,
                    onToggleConnectionAdvanced = {},
                    onBack = {},
                )
            }
        }
        compose.waitForIdle()
    }

    private fun slide(label: String, to: Float) {
        compose.onNode(hasContentDescription(label)).performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(to) }
        compose.waitForIdle()
    }

    @Test
    fun touchpadSpeedTicksInSettings() {
        show(SettingsSection.TOUCHPAD)
        slide("Pointer speed", 0f)
        assertEquals(listOf(HapticEvent.TICK), recorder.events)
    }

    @Test
    fun multimediaLeavesPointerSpeedToTouchpad() {
        show(SettingsSection.MULTIMEDIA)
        compose.onNode(hasContentDescription("Pointer speed")).assertDoesNotExist()
    }

    @Test
    fun sliderTicksFollowTheHapticsSetting() {
        show(SettingsSection.TOUCHPAD, hapticsEnabled = false)
        slide("Pointer speed", 0f)
        assertEquals(emptyList<HapticEvent>(), recorder.events)
    }

    @Test
    fun keyTouchAreaTicksOnEachStep() {
        show(SettingsSection.KEYBOARD)
        compose.onNodeWithText("Advanced").performScrollTo().performClick()
        compose.waitForIdle()
        slide("Key touch area", 9f)
        assertEquals(listOf(9f), keySensitivity)
        assertEquals(listOf(HapticEvent.TICK), recorder.events)
    }

    @Test
    fun keyTouchAreaIsFoldedUnderAdvanced() {
        show(SettingsSection.KEYBOARD)
        compose.onNode(hasContentDescription("Key touch area")).assertDoesNotExist()
        compose.onNodeWithText("Advanced").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Key touch area: ", substring = true).assertExists()
    }

    @Test
    fun themeChipsAreRadioButtonsInAGroup() {
        show(SettingsSection.KEYBOARD)
        compose.onNodeWithText("Theme").performScrollTo().performClick()
        compose.waitForIdle()
        val name = KeyboardThemeCatalog.builtIns.last().name
        val chip = compose.onNode(hasText(name) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected), useUnmergedTree = false)
            .performScrollTo().fetchSemanticsNode()
        assertEquals(Role.RadioButton, chip.config[SemanticsProperties.Role])
        assertTrue(compose.onNodeWithText("Edit themes").fetchSemanticsNode().config.contains(SemanticsActions.OnClick))
    }

    private fun choice(options: List<String>, width: Int) {
        compose.setContent {
            Box(Modifier.width(width.dp)) {
                TuneChoice(label = null, options = options, selected = options.first(), optionLabel = { it }, onSelect = {})
            }
        }
        compose.waitForIdle()
    }

    private fun roleOf(text: String): Role? =
        compose.onNode(hasText(text) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).fetchSemanticsNode()
            .config.getOrElseNullable(SemanticsProperties.Role) { null }

    @Test
    fun layoutDoesNotDependOnWhichOptionIsSelected() {
        val options = listOf("Narrow", "Medium width", "A bit wider one")
        var selected by mutableStateOf(options.first())
        var width by mutableStateOf(200)
        compose.setContent {
            Box(Modifier.width(width.dp)) {
                TuneChoice(label = null, options = options, selected = selected, optionLabel = { it }, onSelect = {})
            }
        }
        val layouts = mutableSetOf<Boolean>()
        for (w in 200..420 step 4) {
            width = w
            val widths = options.map { choice ->
                selected = choice
                compose.waitForIdle()
                compose.onNode(hasText(choice) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).fetchSemanticsNode().size.width
            }
            // A radio row spans the width; a segment takes a third of it.
            val rows = widths.map { it >= with(compose.density) { (w - 1).dp.roundToPx() } }
            assertEquals("layout flips with the selection at width $w", 1, rows.toSet().size)
            layouts += rows.first()
        }
        assertEquals("the sweep should cross from rows to segments", setOf(true, false), layouts)
    }

    @Test
    fun fewOptionsThatFitAreSegments() {
        choice(listOf("Off", "Left", "Right"), width = 360)
        assertEquals(Role.RadioButton, roleOf("Off"))
        // Three segments share the row; a radio row would span it.
        compose.onAllNodes(hasText("Off") or hasText("Left") or hasText("Right")).fetchSemanticsNodes().forEach {
            assertTrue("a segment takes a third of the row", it.size.width < with(compose.density) { 180.dp.roundToPx() })
        }
    }

    @Test
    fun fewOptionsThatDontFitAreRadioRows() {
        val long = listOf("A very long first option", "A very long second option", "A very long third option")
        choice(long, width = 240)
        long.forEach { assertEquals(Role.RadioButton, roleOf(it)) }
        compose.onAllNodes(hasText("A very long", substring = true)).fetchSemanticsNodes().forEach {
            assertTrue("a radio row spans the width", it.size.width >= with(compose.density) { 239.dp.roundToPx() })
        }
    }

    @Test
    fun moreThanFourOptionsAreChips() {
        val many = (1..5).map { "Option $it" }
        choice(many, width = 600)
        many.forEach { assertEquals(Role.RadioButton, roleOf(it)) }
        compose.onAllNodes(hasText("Option", substring = true)).fetchSemanticsNodes().forEach {
            assertTrue("a chip is narrower than the row", it.size.width < with(compose.density) { 300.dp.roundToPx() })
        }
    }
}
