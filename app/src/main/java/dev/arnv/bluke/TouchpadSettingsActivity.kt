package dev.arnv.bluke

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import dev.arnv.bluke.ui.SettingsCardGroup
import dev.arnv.bluke.ui.SettingsGroup
import dev.arnv.bluke.ui.SettingsItemData
import dev.arnv.bluke.ui.TOUCHPAD_INVERT_SCROLL_PREFERENCE
import dev.arnv.bluke.ui.TOUCHPAD_SCROLL_GLIDE_PREFERENCE
import dev.arnv.bluke.ui.TOUCHPAD_SCROLL_MOMENTUM_PREFERENCE
import dev.arnv.bluke.ui.TouchpadScrollSettings
import dev.arnv.bluke.ui.theme.MyApplicationTheme

class TouchpadSettingsActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = getSharedPreferences("app_prefs", MODE_PRIVATE)
        setContent {
            MyApplicationTheme {
                var settings by remember { mutableStateOf(TouchpadScrollSettings.from(preferences)) }
                val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

                Scaffold(
                    modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
                    topBar = {
                        LargeTopAppBar(
                            title = { Text("Touchpad") },
                            navigationIcon = {
                                IconButton(onClick = ::finish) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                }
                            },
                            scrollBehavior = scrollBehavior,
                        )
                    },
                ) { padding ->
                    Column(
                        modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Spacer(Modifier.height(8.dp))
                        SettingsCardGroup(
                            title = "Two-finger scrolling",
                            items = listOf(
                                SettingsItemData(
                                    title = "Invert scroll direction",
                                    subtitle = if (settings.inverted) "Content moves against your fingers" else "Content follows your fingers",
                                    icon = { Icon(Icons.Default.SwapVert, null, tint = MaterialTheme.colorScheme.primary) },
                                    action = {
                                        Switch(settings.inverted, { on ->
                                            settings = settings.copy(inverted = on)
                                            preferences.edit { putBoolean(TOUCHPAD_INVERT_SCROLL_PREFERENCE, on) }
                                        })
                                    },
                                ),
                                SettingsItemData(
                                    title = "Momentum",
                                    subtitle = "Keep scrolling after a flick, slowing to a stop",
                                    icon = { Icon(Icons.Default.Speed, null, tint = MaterialTheme.colorScheme.primary) },
                                    action = {
                                        Switch(settings.momentum, { on ->
                                            settings = settings.copy(momentum = on)
                                            preferences.edit { putBoolean(TOUCHPAD_SCROLL_MOMENTUM_PREFERENCE, on) }
                                        })
                                    },
                                ),
                            ),
                        )
                        if (settings.momentum) {
                            SettingsGroup(title = "Momentum") {
                                ScrollSlider(
                                    title = "Glide distance",
                                    explanation = "How far a flick carries on after your fingers lift.",
                                    value = settings.glide,
                                    label = when {
                                        settings.glide < 0.34f -> "Short"
                                        settings.glide < 0.67f -> "Medium"
                                        else -> "Long"
                                    },
                                    onChange = { value ->
                                        settings = settings.copy(glide = value)
                                        preferences.edit { putFloat(TOUCHPAD_SCROLL_GLIDE_PREFERENCE, value) }
                                    },
                                )
                            }
                        }
                        Text(
                            "Pointer and scroll speed are also in the touchpad's Tune sheet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp),
                        )
                        Spacer(Modifier.height(32.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ScrollSlider(
    title: String,
    explanation: String,
    value: Float,
    label: String,
    onChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(explanation, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = value, onValueChange = onChange, valueRange = 0f..1f, steps = 9)
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}
