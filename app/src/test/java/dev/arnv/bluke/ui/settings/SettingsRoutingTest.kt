package dev.arnv.bluke.ui.settings

import android.content.Context
import android.content.Intent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import dev.arnv.bluke.SettingsActivity
import dev.arnv.bluke.ui.InputMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-port", sdk = [36])
class SettingsRoutingTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val checkbox = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)

    @Test
    fun everySectionRoundTripsThroughItsIntent() {
        SettingsSection.entries.forEach { section ->
            val intent = settingsIntent(context, section)
            assertEquals(SettingsActivity::class.java.name, intent.component?.className)
            assertEquals(section, settingsSectionFrom(intent.getStringExtra(SETTINGS_SECTION_EXTRA)))
        }
    }

    @Test
    fun anUnknownOrMissingSectionOpensTheTop() {
        assertNull(settingsSectionFrom(null))
        assertNull(settingsSectionFrom("QUICK_CYCLE"))
        assertNull(settingsSectionFrom(settingsIntent(context).getStringExtra(SETTINGS_SECTION_EXTRA)))
    }

    @Test
    fun theInputModesPathShowsTheModeCheckboxes() {
        ActivityScenario.launch<SettingsActivity>(settingsIntent(context, SettingsSection.INPUT_MODES)).use {
            compose.onAllNodes(checkbox).assertCountEquals(InputMode.entries.size)
            compose.onNode(checkbox and hasText(InputMode.TOUCHPAD.displayName)).assertIsDisplayed()
        }
    }

    @Test
    fun noSectionOpensWithEverythingCollapsed() {
        ActivityScenario.launch<SettingsActivity>(Intent(context, SettingsActivity::class.java)).use {
            compose.onAllNodes(checkbox).assertCountEquals(0)
            compose.onNode(hasText("Appearance")).assertIsDisplayed()
        }
    }
}
