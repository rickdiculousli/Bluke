package dev.arnv.bluke

import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.arnv.bluke.ui.settings.KeyboardSettingsController
import dev.arnv.bluke.ui.settings.PreferenceSettingsActions
import dev.arnv.bluke.ui.settings.SETTINGS_SECTION_EXTRA
import dev.arnv.bluke.ui.settings.SettingsContent
import dev.arnv.bluke.ui.settings.SettingsSection
import dev.arnv.bluke.ui.settings.SettingsState
import dev.arnv.bluke.ui.settings.readSettingsState
import dev.arnv.bluke.ui.settings.settingsListIndex
import dev.arnv.bluke.ui.settings.settingsSectionFrom
import dev.arnv.bluke.ui.theme.MyApplicationTheme

/**
 * The one Settings screen. An intent built by `settingsIntent(context, section)` opens with that
 * section expanded and scrolled into view; without one, or with one that doesn't exist, it opens at
 * the top with every section collapsed.
 */
class SettingsActivity : ComponentActivity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var keyboardController: KeyboardSettingsController
    private var state by mutableStateOf(SettingsState())

    // SharedPreferences holds its listeners weakly, so the activity keeps this one.
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> reloadState() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        keyboardController = KeyboardSettingsController(this)
        reloadState()
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)

        val initialSection = settingsSectionFrom(intent.getStringExtra(SETTINGS_SECTION_EXTRA))
        val actions = PreferenceSettingsActions(
            prefs = prefs,
            onOpenHelp = { startActivity(Intent(this, HelpActivity::class.java)) },
            onOpenAbout = { startActivity(Intent(this, AboutActivity::class.java)) },
            onOpenDeveloperOptions = { startActivity(Intent(this, DeveloperOptionsActivity::class.java)) },
        )

        setContent {
            MyApplicationTheme {
                var expanded by rememberSaveable(stateSaver = SectionSetSaver) { mutableStateOf(setOfNotNull(initialSection)) }
                var advancedExpanded by rememberSaveable { mutableStateOf(false) }
                // The list state is saved too, so a rotation keeps the scroll rather than jumping back to the section.
                val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialSection?.let(::settingsListIndex) ?: 0)
                SettingsContent(
                    state = state,
                    actions = actions,
                    keyboard = keyboardController.state,
                    keyboardActions = keyboardController,
                    expanded = expanded,
                    onToggleSection = { section -> expanded = if (section in expanded) expanded - section else expanded + section },
                    connectionAdvancedExpanded = advancedExpanded,
                    onToggleConnectionAdvanced = { advancedExpanded = !advancedExpanded },
                    onBack = ::finish,
                    listState = listState,
                    startCollapsed = initialSection != null,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Developer mode is unlocked on About, and Themes and Sound packs change the keyboard from their own screens.
        reloadState()
        keyboardController.reload()
    }

    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        keyboardController.release()
        super.onDestroy()
    }

    private fun reloadState() {
        state = readSettingsState(
            prefs = prefs,
            dynamicColorAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
            versionName = versionName(),
        )
    }

    private fun versionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()
}

private val SectionSetSaver = Saver<Set<SettingsSection>, ArrayList<String>>(
    save = { sections -> ArrayList(sections.map { it.name }) },
    restore = { names -> names.mapNotNullTo(linkedSetOf()) { settingsSectionFrom(it) } },
)
