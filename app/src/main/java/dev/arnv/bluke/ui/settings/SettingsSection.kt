package dev.arnv.bluke.ui.settings

import android.content.Context
import android.content.Intent
import dev.arnv.bluke.SettingsActivity

/** The sections of the one Settings screen, top to bottom. */
enum class SettingsSection {
    APPEARANCE,
    FEEDBACK,
    KEYBOARD,
    TOUCHPAD,
    CONTROLLER,
    MULTIMEDIA,
    INPUT_MODES,
    CONNECTION,
    HELP_AND_ABOUT,
}

/** A labelled run of sections on the Settings screen; [label] is null for a run that needs none. */
internal data class SettingsSectionGroup(val label: String?, val sections: List<SettingsSection>)

/** The Settings screen's groups, top to bottom; together they hold every section once, in order. */
internal val SettingsSectionGroups = listOf(
    SettingsSectionGroup("Look & feel", listOf(SettingsSection.APPEARANCE, SettingsSection.FEEDBACK)),
    SettingsSectionGroup(
        "Inputs",
        listOf(
            SettingsSection.KEYBOARD,
            SettingsSection.TOUCHPAD,
            SettingsSection.CONTROLLER,
            SettingsSection.MULTIMEDIA,
            SettingsSection.INPUT_MODES,
        ),
    ),
    // Not "Connection": a label repeating its only card's title says nothing.
    SettingsSectionGroup("App", listOf(SettingsSection.CONNECTION, SettingsSection.HELP_AND_ABOUT)),
)

/** One row of the Settings list: a group's label or a section's card. */
internal sealed interface SettingsListItem {
    data class Label(val text: String) : SettingsListItem
    data class Section(val section: SettingsSection) : SettingsListItem
}

/** The Settings list's rows in order: each group's label, when it has one, then its sections. */
internal val SettingsListItems: List<SettingsListItem> = SettingsSectionGroups.flatMap { group ->
    listOfNotNull(group.label?.let(SettingsListItem::Label)) + group.sections.map(SettingsListItem::Section)
}

/**
 * The list row to scroll to for [section]: its group's label when the section opens the group, so
 * the label stays in view, otherwise the section's own card.
 */
internal fun settingsListIndex(section: SettingsSection): Int {
    val card = SettingsListItems.indexOf(SettingsListItem.Section(section))
    val above = SettingsListItems.getOrNull(card - 1)
    return if (above is SettingsListItem.Label) card - 1 else card
}

const val SETTINGS_SECTION_EXTRA = "dev.arnv.bluke.settings.SECTION"

/** Opens Settings with [section] expanded and scrolled into view. */
fun settingsIntent(context: Context, section: SettingsSection? = null): Intent =
    Intent(context, SettingsActivity::class.java).apply {
        if (section != null) putExtra(SETTINGS_SECTION_EXTRA, section.name)
    }

/** The section an intent asks for; null, which opens the top, when it names none or one that doesn't exist. */
fun settingsSectionFrom(value: String?): SettingsSection? =
    value?.let { name -> SettingsSection.entries.firstOrNull { it.name == name } }
