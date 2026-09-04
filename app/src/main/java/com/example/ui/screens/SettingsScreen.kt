package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            SettingsSection(title = "CORE SAFETY") {
                SettingsSwitchRow(label = "Never spend money automatically", checked = true, enabled = false)
                SettingsSwitchRow(label = "Confirmation before consequential actions", checked = true, enabled = false)
            }
            
            SettingsSection(title = "AI PREFERENCES") {
                SettingsRow(label = "AI Mode", value = "Free-first")
                SettingsRow(label = "AI Providers", value = "Placeholder - Not implemented")
                SettingsRow(label = "Fallback order", value = "Placeholder - Not implemented")
            }

            SettingsSection(title = "LOCAL ENGINE") {
                SettingsSwitchRow(label = "Local command processing", checked = true, enabled = false)
                SettingsRow(label = "Token/quota tracking", value = "Placeholder")
            }

            SettingsSection(title = "SYSTEM INTEGRATION (FUTURE)") {
                SettingsSwitchRow(label = "Wake word detection", checked = false, enabled = false)
                SettingsSwitchRow(label = "Background assistant", checked = false, enabled = false)
                SettingsSwitchRow(label = "Android accessibility integration", checked = false, enabled = false)
            }

            SettingsSection(title = "EXTERNAL TOOLS (FUTURE)") {
                SettingsRow(label = "GitHub connection", value = "Not connected")
                SettingsRow(label = "Termux connection", value = "Not connected")
            }
        }
    }
}

@Composable
fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        content()
    }
}

@Composable
fun SettingsRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    }
}

@Composable
fun SettingsSwitchRow(label: String, checked: Boolean, enabled: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge, color = if(enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha=0.6f))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
