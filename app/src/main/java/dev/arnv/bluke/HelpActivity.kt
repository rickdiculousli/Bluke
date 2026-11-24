package dev.arnv.bluke

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LaptopMac
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.arnv.bluke.ui.theme.MyApplicationTheme
import androidx.compose.ui.input.nestedscroll.nestedScroll

class HelpActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MyApplicationTheme {
                HelpContent(onBack = ::finish)
            }
        }
    }
}

/** The help screen: its top bar and the step cards, with Back left to the caller. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpContent(onBack: () -> Unit) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Help") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                scrollBehavior = scrollBehavior
            )
        }
    ) { innerPadding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            
            StepSection(
                icon = Icons.Default.Bluetooth, 
                title = "Connect a new host",
                steps = listOf(
                    "Make the host (a computer, TV, tablet or phone) visible in its Bluetooth settings.",
                    "Scan in Bluke and tap the host name.",
                    "Accept the matching pairing prompt on both devices.",
                    "Wait for Bluke's status dot to turn green before sending input."
                )
            )
            
            StepSection(
                icon = Icons.Default.BluetoothConnected, 
                title = "Repair a stale pairing",
                steps = listOf(
                    "Forget the host in this phone's Bluetooth settings.",
                    "Remove Bluke or this phone from the host's Bluetooth settings.",
                    "Toggle Bluetooth on both sides, then pair again from Bluke.",
                    "Reinstalling Bluke alone does not clear the host's cached HID layout."
                )
            )
            
            StepSection(
                icon = Icons.Default.Warning, 
                title = "Pairing was refused",
                isWarning = true,
                steps = listOf(
                    "A refusal means the host or phone rejected the request; it does not prove HID is unsupported.",
                    "Retry once and accept every prompt on both devices.",
                    "If it repeats, use the full repair steps above."
                )
            )

            StepSection(
                icon = Icons.Default.SportsEsports,
                title = "Gamepad and browser games",
                steps = listOf(
                    "Choose Native for Windows/Linux games, Android for Android games, or Web when a browser game ignores directions.",
                    "Choose the profile in the gamepad's Tune sheet or in Settings › Gamepad. Every profile switches live without reconnecting.",
                    "For Steam, enable the controller in Steam Input and verify it in Steam's controller test or the operating system's game-controller panel.",
                    "Bluke is a generic HID gamepad, not an Xbox XInput device. Some newer Windows games accept only XInput without a compatibility layer."
                )
            )

            StepSection(
                icon = Icons.Default.LaptopMac,
                title = "Using a Mac",
                steps = listOf(
                    "On first pairing macOS opens the Keyboard Setup Assistant: press the keys it asks for and choose ANSI or ISO as on your layout.",
                    "Set Settings › Keyboard › Modifier keys to Mac so Cmd and Opt sit beside the space bar.",
                    "Games and browsers read the gamepad only with Input Monitoring permission: System Settings > Privacy & Security > Input Monitoring. If you denied it to Chrome or Steam, turn it on there.",
                    "Pointer and scroll speed, scroll direction and the momentum glide are in Settings › Touchpad."
                )
            )

            StepSection(
                icon = Icons.Default.Info,
                title = "Platform limitations",
                steps = listOf(
                    "Support depends on the phone firmware exposing Android's Bluetooth HID Device role.",
                    "iPhone and iPad are not supported hosts.",
                    "Host audio routing prevention is a best-effort workaround for Linux and may be undone by the host or phone firmware.",
                    "If Android repeatedly rejects HID registration, use Retry once. The incompatible-device screen appears only after the registration attempt ceiling."
                )
            )
            
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun StepSection(icon: ImageVector, title: String, steps: List<String>, isWarning: Boolean = false) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (isWarning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (isWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isWarning) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isWarning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { heading() },
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                steps.forEachIndexed { index, step ->
                    Row(verticalAlignment = Alignment.Top) {
                        Box(
                            modifier = Modifier
                                .padding(top = 2.dp)
                                // Sized in sp, so the digit still fits the circle at large font scales.
                                .size(with(LocalDensity.current) { 20.sp.toDp() })
                                .clip(CircleShape)
                                // On a warning card an errorContainer badge would vanish into the card, so it takes the solid error role.
                                .background(if (isWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isWarning) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = step,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isWarning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 1.dp)
                        )
                    }
                }
            }
        }
    }
}
