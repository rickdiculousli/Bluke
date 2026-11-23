package dev.arnv.bluke.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.arnv.bluke.R

/** What home asks its host to do; [HomeContent] itself holds no app state. */
data class HomeCallbacks(
    val onAction: (ConnectionAction) -> Unit = {},
    val onConnectHost: (HostRef) -> Unit = {},
    val onToggleScan: () -> Unit = {},
    val onLaunch: (InputMode) -> Unit = {},
    val onModeSettings: (InputMode) -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onOpenHelp: () -> Unit = {},
)

internal val InputMode.icon: ImageVector
    get() = when (this) {
        InputMode.KEYBOARD -> Icons.Default.Keyboard
        InputMode.TOUCHPAD -> Icons.Default.Mouse
        InputMode.GAMEPAD -> Icons.Default.SportsEsports
        InputMode.MEDIA_PRESENTATION -> Icons.Default.SmartDisplay
        else -> Icons.Default.Devices
    }

/**
 * The home screen as a pure function of [state]: the connection card, every input mode one tap away,
 * and the host lists, which stay out of the way until they're needed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(
    state: HomeUiState,
    callbacks: HomeCallbacks,
    showMacAddress: Boolean = false,
) {
    val connection = state.connection
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = callbacks.onOpenHelp) {
                        Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = "Help")
                    }
                    IconButton(onClick = callbacks.onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxSize()
                    .testTag("home_list"),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                item(key = "card") {
                    ConnectionCard(connection, state.statusLine, callbacks.onAction)
                }
                if (connection is ConnectionUi.Blocked) return@LazyColumn
                item(key = "modes") {
                    ModeTiles(
                        enabled = connection.allowsLaunch,
                        onLaunch = callbacks.onLaunch,
                        onModeSettings = callbacks.onModeSettings,
                    )
                }
                item(key = "hosts") {
                    HostSections(state, showMacAddress, callbacks)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModeTiles(
    enabled: Boolean,
    onLaunch: (InputMode) -> Unit,
    onModeSettings: (InputMode) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Use this phone as")
        InputMode.entries.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { mode ->
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 88.dp)
                            .testTag("mode_tile_${mode.preferenceKey}"),
                    ) {
                        Column(
                            modifier = Modifier
                                .combinedClickable(
                                    enabled = enabled,
                                    role = Role.Button,
                                    onClickLabel = "Open ${mode.displayName}",
                                    onLongClickLabel = "Choose which modes the switch button cycles",
                                    onLongClick = { onModeSettings(mode) },
                                    onClick = { onLaunch(mode) },
                                )
                                .heightIn(min = 88.dp)
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                mode.icon,
                                contentDescription = null,
                                tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(24.dp),
                            )
                            Text(
                                mode.displayName,
                                style = MaterialTheme.typography.titleMedium,
                                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun HostSections(state: HomeUiState, showMacAddress: Boolean, callbacks: HomeCallbacks) {
    val connection = state.connection
    val connected = connection is ConnectionUi.Connected
    // Paired collapses itself while connected; a tap overrides that until the connection changes.
    var pairedOverride by rememberSaveable { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(connected) { pairedOverride = null }
    val pairedExpanded = pairedOverride ?: !connected
    val nearbyOpen = state.isScanning || state.nearby.isNotEmpty()

    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        if (state.paired.isEmpty()) {
            FirstHostSteps()
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader(
                    "Paired hosts",
                    count = state.paired.size,
                    expanded = pairedExpanded,
                    onToggle = { pairedOverride = !pairedExpanded },
                    modifier = Modifier.testTag("paired_header"),
                )
                AnimatedVisibility(
                    visible = pairedExpanded,
                    enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(tween(150)),
                    exit = shrinkVertically(spring(stiffness = Spring.StiffnessMedium)) + fadeOut(tween(100)),
                ) {
                    HostGroup(state.paired, connection, showMacAddress, callbacks.onConnectHost)
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader(
                "Nearby",
                count = state.nearby.size.takeIf { nearbyOpen },
                trailing = { ScanButton(state.isScanning, callbacks.onToggleScan) },
                modifier = Modifier.testTag("nearby_header"),
            )
            AnimatedVisibility(
                visible = nearbyOpen,
                enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(tween(150)),
                exit = shrinkVertically(spring(stiffness = Spring.StiffnessMedium)) + fadeOut(tween(100)),
            ) {
                if (state.nearby.isEmpty()) {
                    Text(
                        "Looking for hosts with Bluetooth settings open…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Start,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 8.dp),
                    )
                } else {
                    HostGroup(state.nearby, connection, showMacAddress, callbacks.onConnectHost)
                }
            }
        }
    }
}
