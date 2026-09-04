package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.JarvisViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: JarvisViewModel, onBack: () -> Unit) {
    val coroutineScope = rememberCoroutineScope()

    val confirmationRequired by viewModel.confirmationRequired.collectAsState()
    val localProcessing by viewModel.localProcessingEnabled.collectAsState()

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
                SettingsSwitchRow(
                    label = "Never spend money automatically",
                    checked = true,
                    enabled = false,
                    onCheckedChange = {}
                )
                SettingsSwitchRow(
                    label = "Confirmation for consequential actions (Mandatory)",
                    checked = confirmationRequired,
                    enabled = true,
                    onCheckedChange = {
                        coroutineScope.launch { viewModel.settingsManager.setConfirmationRequired(it) }
                    }
                )
            }

            SettingsSection(title = "AI PREFERENCES") {
                SettingsRow(label = "AI Mode", value = "FREE_FIRST")
                SettingsRow(label = "AI Providers", value = "Placeholder - Not implemented")
                SettingsRow(label = "Fallback order", value = "Placeholder - Not implemented")
            }

            SettingsSection(title = "LOCAL ENGINE") {
                SettingsSwitchRow(
                    label = "Local command processing",
                    checked = localProcessing,
                    enabled = true,
                    onCheckedChange = {
                        coroutineScope.launch { viewModel.settingsManager.setLocalProcessing(it) }
                    }
                )
                SettingsRow(label = "Token/quota tracking", value = "Placeholder")
            }

            SettingsSection(title = "SYSTEM INTEGRATION (FUTURE)") {
                SettingsSwitchRow(label = "Wake word detection", checked = false, enabled = false, onCheckedChange = {})
                SettingsSwitchRow(label = "Background assistant", checked = false, enabled = false, onCheckedChange = {})
                SettingsSwitchRow(label = "Android accessibility integration", checked = false, enabled = false, onCheckedChange = {})
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
fun SettingsSwitchRow(label: String, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge, color = if(enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha=0.6f))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
