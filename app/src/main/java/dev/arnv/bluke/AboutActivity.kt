package dev.arnv.bluke

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.core.content.edit
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.arnv.bluke.ui.SettingsGroup
import dev.arnv.bluke.ui.SettingsCardGroup
import dev.arnv.bluke.ui.SettingsItemData
import dev.arnv.bluke.ui.theme.MyApplicationTheme
import dev.arnv.bluke.ui.theme.getCookieShape

class AboutActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val versionName = try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            packageInfo.versionName ?: "1.0.1"
        } catch (_: Exception) {
            "1.0.1"
        }
        
        setContent {
            MyApplicationTheme {
                val context = LocalContext.current
                
                // Compute shapes separately to avoid sharing a stateful Shape instance across different sizes, which causes layout/shrinking bugs on activity resume.
                val logoShape = getCookieShape(7)
                val developerShape = getCookieShape(7)
                
                var clickCount by remember { mutableIntStateOf(0) }
                val sharedPrefs = context.getSharedPreferences("app_prefs", MODE_PRIVATE)

                val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

                Scaffold(
                    modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
                    topBar = {
                        LargeTopAppBar(
                            title = { Text("About") },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
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
                            .padding(innerPadding)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Surface(
                            shape = logoShape,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(100.dp)
                                .clickable(
                                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                    indication = null // Hide ripple for secret click
                                ) {
                                    clickCount++
                                    if (clickCount == 5) {
                                        sharedPrefs.edit { putBoolean("is_developer_mode", true) }
                                        android.widget.Toast.makeText(context, "You are now a developer!", android.widget.Toast.LENGTH_SHORT).show()
                                    } else if (clickCount > 5 && clickCount % 3 == 0) {
                                        android.widget.Toast.makeText(context, "No need, you are already a developer.", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_launcher_foreground),
                                contentDescription = "App Logo",
                                modifier = Modifier.fillMaxSize(),
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                        
                        Text(
                            text = getString(R.string.app_name),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        
                        Text(
                            text = "Version $versionName",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        // Developer Heading and Card content wrapped in SettingsGroup for visual consistency
                        SettingsGroup(title = "Developer") {
                            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                     Surface(
                                         shape = developerShape,
                                         color = MaterialTheme.colorScheme.primary,
                                         modifier = Modifier
                                             .padding(end = 16.dp)
                                             .size(44.dp)
                                     ) {
                                         Box(
                                             contentAlignment = Alignment.Center,
                                             modifier = Modifier.fillMaxSize()
                                         ) {
                                             Icon(
                                                 painter = painterResource(id = R.drawable.ic_wordmark),
                                                 contentDescription = "Wordmark logo",
                                                 tint = MaterialTheme.colorScheme.onPrimary,
                                                 modifier = Modifier.size(24.dp)
                                             )
                                         }
                                     }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Arnav Kumar",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "@arnav-kr",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        SettingsCardGroup(
                            title = "App",
                            items = listOf(
                                SettingsItemData(
                                    title = "Licenses",
                                    subtitle = "View the licenses that the app and libraries are using",
                                    icon = { Icon(Icons.Default.Policy, null, tint = MaterialTheme.colorScheme.primary) },
                                    onClick = { startActivity(Intent(this@AboutActivity, LicensesActivity::class.java)) }
                                )
                            )
                        )
                        
                        Spacer(modifier = Modifier.height(32.dp))
                    }
                }
            }
        }
    }
}
