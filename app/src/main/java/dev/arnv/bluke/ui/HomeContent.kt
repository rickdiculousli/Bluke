package dev.arnv.bluke.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ripple
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material3.HorizontalDivider
import android.view.HapticFeedbackConstants
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
    hapticsEnabled: Boolean = true,
) {
    val haptics = LocalHapticFeedback.current
    // Long-press haptics follow the app's haptics switch, like every other buzz in Bluke.
    val gatedHaptics = remember(haptics, hapticsEnabled) {
        object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                if (hapticsEnabled) haptics.performHapticFeedback(hapticFeedbackType)
            }
        }
    }
    CompositionLocalProvider(LocalHapticFeedback provides gatedHaptics) {
        HomeScaffold(state, callbacks, showMacAddress, hapticsEnabled)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScaffold(state: HomeUiState, callbacks: HomeCallbacks, showMacAddress: Boolean, hapticsEnabled: Boolean) {
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
        bottomBar = {
            // The modes sit outside the scrolling list, in thumb reach, so a press shows the instant it lands.
            if (connection.allowsLaunch) {
                ModeDock(connection is ConnectionUi.Connected, hapticsEnabled, callbacks.onLaunch, callbacks.onModeSettings)
            }
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
                // Hosts wait until Bluetooth is checked: before that, nothing in them can be acted on.
                if (connection is ConnectionUi.Blocked || connection == ConnectionUi.Checking) return@LazyColumn
                item(key = "hosts") {
                    HostSections(state, showMacAddress, callbacks)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModeDock(
    connected: Boolean,
    hapticsEnabled: Boolean,
    onLaunch: (InputMode) -> Unit,
    onModeSettings: (InputMode) -> Unit,
) {
    // A short window (a landscape phone, a split screen) gets one row of four, so the card keeps its room.
    val compactHeight = LocalConfiguration.current.screenHeightDp < 600
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().testTag("mode_dock")) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            HorizontalDivider()
            Column(
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Use this phone as",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // On the tiles' content edge, as the section headers sit on the rows'.
                    modifier = Modifier.padding(start = 16.dp).semantics { heading() },
                )
                InputMode.entries.chunked(if (compactHeight) 4 else 2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { mode ->
                            ModeTile(mode, compactHeight, connected, hapticsEnabled, onLaunch, onModeSettings, Modifier.weight(1f))
                        }
                        if (!compactHeight && row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModeTile(
    mode: InputMode,
    stacked: Boolean,
    connected: Boolean,
    hapticsEnabled: Boolean,
    onLaunch: (InputMode) -> Unit,
    onModeSettings: (InputMode) -> Unit,
    modifier: Modifier,
) {
    val view = LocalView.current
    val scheme = MaterialTheme.colorScheme
    // The pressed tone snaps on with the finger, the same frame, with no animation in the way.
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // The haptic lands with the pressed tone, on touch-down; the launch still waits for the tap.
    LaunchedEffect(interaction, hapticsEnabled) {
        interaction.interactions.collect {
            if (it is PressInteraction.Press && hapticsEnabled) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }
    Surface(
        shape = MaterialTheme.shapes.large,
        // Once a host is connected the modes are the next step, so they take the tonal emphasis.
        color = when {
            pressed -> scheme.secondary
            connected -> scheme.secondaryContainer
            else -> scheme.surfaceContainerHighest
        },
        contentColor = when {
            pressed -> scheme.onSecondary
            connected -> scheme.onSecondaryContainer
            else -> scheme.onSurface
        },
        modifier = modifier
            .heightIn(min = 64.dp)
            .testTag("mode_tile_${mode.preferenceKey}"),
    ) {
        val click = Modifier
            .combinedClickable(
                interactionSource = interaction,
                indication = ripple(),
                role = Role.Button,
                onClickLabel = "Open ${mode.displayName}",
                onLongClickLabel = "Choose which modes the switch button cycles",
                onLongClick = { onModeSettings(mode) },
                onClick = { onLaunch(mode) },
            )
            .heightIn(min = 64.dp)
        val tint = when {
            pressed -> scheme.onSecondary
            connected -> scheme.onSecondaryContainer
            else -> scheme.onSurfaceVariant
        }
        if (stacked) {
            Column(
                modifier = click.padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            ) {
                Icon(mode.icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                Text(mode.displayName, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else {
            Row(
                modifier = click.padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(mode.icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                Text(mode.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun HostSections(state: HomeUiState, showMacAddress: Boolean, callbacks: HomeCallbacks) {
    val connection = state.connection
    val connected = connection is ConnectionUi.Connected
    // Paired steps aside while connected or scanning, so Nearby's results show; a tap overrides that until either changes.
    // ...and while the card is about a host that isn't paired (pairing it, or it refused), so its Nearby row stays in view.
    val cardHostUnpaired = connection.host?.let { host -> host.address.isNotBlank() && state.paired.none { it.address == host.address } } == true
    val stepAside = connected || state.isScanning || cardHostUnpaired
    var pairedOverride by rememberSaveable { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(stepAside) { pairedOverride = null }
    val pairedExpanded = pairedOverride ?: !stepAside
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
                    enter = expandVertically(spring(stiffness = Spring.StiffnessMedium)) + fadeIn(spring(stiffness = Spring.StiffnessMedium)),
                    exit = shrinkVertically(spring(stiffness = Spring.StiffnessMedium)) + fadeOut(spring(stiffness = Spring.StiffnessMedium)),
                ) {
                    HostGroup(state.paired, connection, showMacAddress, callbacks.onConnectHost)
                }
            }
        }
        // When the card's one step is already Scan or Stop scanning, Nearby neither repeats it nor sits empty.
        val cardScans = connection.card().primary.let { it == ConnectionAction.Scan || it == ConnectionAction.StopScan }
        if (cardScans && !nearbyOpen) return@Column
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader(
                "Nearby",
                count = state.nearby.size.takeIf { nearbyOpen },
                trailing = { if (!cardScans) ScanButton(state.isScanning, callbacks.onToggleScan) },
                modifier = Modifier.testTag("nearby_header"),
            )
            if (!nearbyOpen) {
                Text(
                    "Scan to add another host.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            AnimatedVisibility(
                visible = nearbyOpen,
                enter = expandVertically(spring(stiffness = Spring.StiffnessMedium)) + fadeIn(spring(stiffness = Spring.StiffnessMedium)),
                exit = shrinkVertically(spring(stiffness = Spring.StiffnessMedium)) + fadeOut(spring(stiffness = Spring.StiffnessMedium)),
            ) {
                if (state.nearby.isEmpty()) {
                    Text(
                        "Looking for hosts with Bluetooth settings open…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Start,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                } else {
                    HostGroup(state.nearby, connection, showMacAddress, callbacks.onConnectHost)
                }
            }
        }
    }
}
