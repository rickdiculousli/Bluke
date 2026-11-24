package dev.arnv.bluke

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import dev.arnv.bluke.sound.CustomSoundPack
import dev.arnv.bluke.sound.CustomSoundPackRepository
import dev.arnv.bluke.sound.KEY_SOUND_ENABLED_PREFERENCE
import dev.arnv.bluke.sound.KeyboardSoundSynthesizer
import dev.arnv.bluke.sound.SoundPackImportResult
import dev.arnv.bluke.sound.SwitchType
import dev.arnv.bluke.sound.builtInSoundProfileId
import dev.arnv.bluke.sound.customSoundProfileId
import dev.arnv.bluke.sound.selectKeySound
import dev.arnv.bluke.sound.selectedBuiltInSound
import dev.arnv.bluke.sound.turnKeySoundsOff
import dev.arnv.bluke.ui.SettingsGroup
import dev.arnv.bluke.ui.SettingsSubheader
import dev.arnv.bluke.ui.selectedOrFirstEnabled
import dev.arnv.bluke.ui.settings.LibraryChoiceRow
import dev.arnv.bluke.ui.settings.PlaySoundButton
import dev.arnv.bluke.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SoundPacksActivity : ComponentActivity() {
    private var synthesizer: KeyboardSoundSynthesizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val repository = CustomSoundPackRepository(applicationContext)
        val preferences = getSharedPreferences("app_prefs", MODE_PRIVATE)

        fun storedSelection(packs: List<CustomSoundPack>): String =
            repository.selectedPackId()?.takeIf { id -> packs.any { it.id == id } }?.let(::customSoundProfileId)
                ?: builtInSoundProfileId(selectedBuiltInSound(preferences))

        setContent {
            MyApplicationTheme {
                var packs by remember { mutableStateOf(repository.listPacks()) }
                var selectedProfileId by remember { mutableStateOf(storedSelection(packs)) }
                var soundsOn by remember { mutableStateOf(preferences.getBoolean(KEY_SOUND_ENABLED_PREFERENCE, true)) }
                var importing by remember { mutableStateOf(false) }
                var pendingDeletion by remember { mutableStateOf<CustomSoundPack?>(null) }
                val scope = rememberCoroutineScope()
                val snackbarHostState = remember { SnackbarHostState() }

                fun choices() = soundPackChoices(packs)

                fun play(profileId: String) {
                    val player = synthesizer ?: KeyboardSoundSynthesizer(applicationContext).also { synthesizer = it }
                    player.previewSoundProfile(profileId)
                }

                fun select(profileId: String) {
                    if (!selectKeySound(preferences, profileId)) return
                    selectedProfileId = profileId
                    soundsOn = true
                }

                /** Moves the choice off a sound that left the list, leaving sounds on or off. */
                fun keepSelectionInList() {
                    val ids = choices().map { it.id }
                    val next = selectedOrFirstEnabled(selectedProfileId, ids.toSet(), ids) ?: return
                    if (next == selectedProfileId) return
                    selectKeySound(preferences, next)
                    if (!soundsOn) turnKeySoundsOff(preferences)
                    selectedProfileId = next
                }

                val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) {
                        importing = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                contentResolver.openInputStream(uri)?.use(repository::importZip)
                                    ?: SoundPackImportResult.Failure("Could not read the selected file.")
                            }
                            importing = false
                            when (result) {
                                is SoundPackImportResult.Success -> {
                                    packs = repository.listPacks()
                                    select(customSoundProfileId(result.pack.id))
                                    snackbarHostState.showSnackbar("Imported and selected ${result.pack.name}.")
                                }
                                is SoundPackImportResult.Duplicate ->
                                    snackbarHostState.showSnackbar("${result.pack.name} is already imported.")
                                is SoundPackImportResult.Failure -> snackbarHostState.showSnackbar(result.message)
                            }
                        }
                    }
                }

                pendingDeletion?.let { pack ->
                    AlertDialog(
                        onDismissRequest = { pendingDeletion = null },
                        title = { Text("Delete ${pack.name}?") },
                        text = { Text("This removes the imported pack from Bluke. Built-in sounds are not affected.") },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    pendingDeletion = null
                                    scope.launch {
                                        val deleted = withContext(Dispatchers.IO) { repository.deletePack(pack.id) }
                                        if (deleted) {
                                            packs = repository.listPacks()
                                            keepSelectionInList()
                                            snackbarHostState.showSnackbar("Deleted ${pack.name}.")
                                        } else {
                                            snackbarHostState.showSnackbar("Could not delete ${pack.name}.")
                                        }
                                    }
                                },
                            ) { Text("Delete") }
                        },
                        dismissButton = { TextButton(onClick = { pendingDeletion = null }) { Text("Cancel") } },
                    )
                }

                SoundPacksScreen(
                    importing = importing,
                    onImport = { importer.launch(arrayOf("application/zip", "application/octet-stream")) },
                    onBack = ::finish,
                    snackbarHostState = snackbarHostState,
                ) { innerPadding ->
                    SoundPacksContent(
                        modifier = Modifier.padding(innerPadding),
                        choices = choices(),
                        selectedId = selectedProfileId,
                        soundsOn = soundsOn,
                        // Choosing a sound plays it, so the choice is heard as it's made.
                        onSelect = { id ->
                            select(id)
                            if (selectedProfileId == id) play(id)
                        },
                        onPlay = ::play,
                        onDelete = { id -> pendingDeletion = packs.firstOrNull { customSoundProfileId(it.id) == id } },
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        synthesizer?.release()
        synthesizer = null
        super.onDestroy()
    }
}

/** The Sound packs screen's frame: app bar, snackbars and the Import button around [content]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SoundPacksScreen(
    importing: Boolean,
    onImport: () -> Unit,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    content: @Composable (PaddingValues) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Sound packs") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { if (!importing) onImport() },
                icon = {
                    if (importing) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Add, contentDescription = null)
                    }
                },
                text = { Text(if (importing) "Importing…" else "Import pack") },
            )
        },
        content = content,
    )
}

/** One row of the Sound packs list; [imported] packs can be deleted. */
internal data class SoundPackChoice(val id: String, val name: String, val imported: Boolean)

internal fun soundPackChoices(packs: List<CustomSoundPack>): List<SoundPackChoice> =
    SwitchType.entries.map { SoundPackChoice(builtInSoundProfileId(it), it.displayName, imported = false) } +
        packs.map { SoundPackChoice(customSoundProfileId(it.id), it.name, imported = true) }

/** The Sound packs list, stateless so a screenshot test can draw it without packs on disk. */
@Composable
internal fun SoundPacksContent(
    choices: List<SoundPackChoice>,
    selectedId: String,
    soundsOn: Boolean,
    onSelect: (String) -> Unit,
    onPlay: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            if (soundsOn) {
                "The chosen sound plays on every key press. Imported Mechvibes packs sit beside the built-in switches."
            } else {
                "Key sounds are off. Choosing a sound turns them on."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        SettingsGroup {
            SettingsSubheader("Sounds")
            // The menu sits before play, so play is always last and lines up on every row with no spacer.
            Column(Modifier.selectableGroup()) {
                choices.forEach { choice ->
                    LibraryChoiceRow(
                        title = choice.name,
                        subtitle = if (choice.imported) "Imported" else null,
                        selected = soundsOn && choice.id == selectedId,
                        onSelect = { onSelect(choice.id) },
                        trailing = {
                            if (choice.imported) PackMenu(choice.name, onDelete = { onDelete(choice.id) })
                            PlaySoundButton(choice.name) { onPlay(choice.id) }
                        },
                    )
                }
            }
        }
        if (choices.none { it.imported }) {
            Text(
                "Imported packs appear in this list. Import a Mechvibes .zip with the button below.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        Spacer(Modifier.height(96.dp))
    }
}

/** An imported pack's overflow menu; deleting asks again before anything is removed. */
@Composable
private fun PackMenu(name: String, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.MoreVert, contentDescription = "More options for $name")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Delete…") },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                onClick = {
                    open = false
                    onDelete()
                },
            )
        }
    }
}
