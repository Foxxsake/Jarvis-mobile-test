package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.JarvisViewModel
import kotlinx.coroutines.launch

enum class SettingsBadgeType {
    LOCKED_ON,
    COMING_LATER,
    NOT_CONNECTED,
    NOT_IMPLEMENTED
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: JarvisViewModel, onBack: () -> Unit) {
    val coroutineScope = rememberCoroutineScope()
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
            // PERMANENT SAFETY RULES - LOCKED ON
            SettingsSection(title = "CORE SAFETY (PERMANENT)") {
                SettingsLockedSafetyRow(
                    label = "Never spend money automatically",
                    description = "Zero paid APIs or financial transactions. Hard invariant."
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.surfaceVariant)
                SettingsLockedSafetyRow(
                    label = "Confirmation for consequential actions",
                    description = "Communication and destructive actions require explicit user approval."
                )
            }

            // EDITABLE NOW - ACTIVE CONTROLS
            SettingsSection(title = "LOCAL ENGINE (ACTIVE)") {
                SettingsSwitchRow(
                    label = "Local command processing",
                    description = "Execute supported commands on-device without cloud or AI latency",
                    checked = localProcessing,
                    onCheckedChange = {
                        coroutineScope.launch { viewModel.settingsManager.setLocalProcessing(it) }
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.surfaceVariant)
                SettingsStatusBadgeRow(
                    label = "Token / quota tracking",
                    badgeText = "NOT IMPLEMENTED",
                    badgeType = SettingsBadgeType.NOT_IMPLEMENTED
                )
            }

            // TERMUX EXECUTION WORKER
            val uiState by viewModel.uiState.collectAsState()
            val termux = uiState.termuxStatus
            val context = androidx.compose.ui.platform.LocalContext.current

            SettingsSection(title = "TERMUX EXECUTION WORKER") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Installed", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (termux.isInstalled) "YES" else "NO",
                        fontWeight = FontWeight.Bold,
                        color = if (termux.isInstalled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.surfaceVariant)

                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("RUN_COMMAND Permission", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (termux.isPermissionGranted) "GRANTED" else "REQUIRED",
                        fontWeight = FontWeight.Bold,
                        color = if (termux.isPermissionGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.surfaceVariant)

                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("External App Execution", style = MaterialTheme.typography.bodyMedium)
                    val (extText, extColor) = when (termux.connectionState) {
                        com.example.engine.termux.TermuxConnectionState.READY -> "READY" to MaterialTheme.colorScheme.primary
                        com.example.engine.termux.TermuxConnectionState.SETUP_REQUIRED -> "SETUP REQUIRED" to MaterialTheme.colorScheme.tertiary
                        com.example.engine.termux.TermuxConnectionState.FAILED -> "FAILED / CALLBACK ERROR" to MaterialTheme.colorScheme.error
                        com.example.engine.termux.TermuxConnectionState.UNVERIFIED -> "UNVERIFIED" to MaterialTheme.colorScheme.tertiary
                        else -> "NOT READY" to MaterialTheme.colorScheme.error
                    }
                    Text(
                        extText,
                        fontWeight = FontWeight.Bold,
                        color = extColor
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.surfaceVariant)

                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Connection State", style = MaterialTheme.typography.bodyMedium)
                    val (stateText, stateColor) = when (termux.connectionState) {
                        com.example.engine.termux.TermuxConnectionState.READY -> "READY" to MaterialTheme.colorScheme.primary
                        com.example.engine.termux.TermuxConnectionState.UNVERIFIED -> "UNVERIFIED" to MaterialTheme.colorScheme.tertiary
                        com.example.engine.termux.TermuxConnectionState.SETUP_REQUIRED -> "SETUP REQUIRED" to MaterialTheme.colorScheme.tertiary
                        com.example.engine.termux.TermuxConnectionState.TERMUX_NOT_INSTALLED -> "NOT INSTALLED" to MaterialTheme.colorScheme.error
                        com.example.engine.termux.TermuxConnectionState.TERMUX_TOO_OLD -> "TOO OLD (<0.109)" to MaterialTheme.colorScheme.error
                        com.example.engine.termux.TermuxConnectionState.TERMUX_PERMISSION_REQUIRED -> "PERMISSION REQUIRED" to MaterialTheme.colorScheme.error
                        com.example.engine.termux.TermuxConnectionState.FAILED -> "FAILED" to MaterialTheme.colorScheme.error
                    }
                    Text(stateText, fontWeight = FontWeight.Bold, color = stateColor)
                }

                if (!termux.detailMessage.isNullOrBlank()) {
                    Text(
                        termux.detailMessage!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }

                if (termux.connectionState == com.example.engine.termux.TermuxConnectionState.SETUP_REQUIRED) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Setup Instructions:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "1. Open Termux\n2. Ensure ~/.termux/termux.properties contains:\n   allow-external-apps=true\n3. Run: termux-reload-settings",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                val clip = android.content.ClipData.newPlainText("Termux Setup", "mkdir -p ~/.termux && echo \"allow-external-apps=true\" >> ~/.termux/termux.properties && termux-reload-settings")
                                clipboard?.setPrimaryClip(clip)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Copy Setup Command", style = MaterialTheme.typography.labelSmall)
                        }

                        OutlinedButton(
                            onClick = { viewModel.refreshTermuxStatus() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Check Connection", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { viewModel.refreshTermuxStatus() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Check Connection", style = MaterialTheme.typography.labelSmall)
                    }
                }

            }

            // WORKSPACE CONFIGURATION
            val activeWs = uiState.activeWorkspace
            var wsName by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(activeWs?.displayName ?: "JARVIS Mobile") }
            var wsPath by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(activeWs?.localPath ?: "/data/data/com.termux/files/home") }

            SettingsSection(title = "PROJECT WORKSPACE") {
                Text(
                    "Register local workspace directory for termux status and git commands:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = wsName,
                    onValueChange = { wsName = it },
                    label = { Text("Display Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = wsPath,
                    onValueChange = { wsPath = it },
                    label = { Text("Local Path") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.setWorkspacePath(wsName, wsPath) },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Save Workspace")
                }
            }

            // AI PREFERENCES (FUTURE)
            SettingsSection(title = "AI PREFERENCES (FUTURE)") {
                SettingsStatusBadgeRow(
                    label = "AI Mode",
                    badgeText = "FREE_FIRST (LOCAL ONLY)",
                    badgeType = SettingsBadgeType.NOT_IMPLEMENTED
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.surfaceVariant)
                SettingsStatusBadgeRow(
                    label = "AI Providers",
                    badgeText = "NOT IMPLEMENTED",
                    badgeType = SettingsBadgeType.NOT_IMPLEMENTED
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.surfaceVariant)
                SettingsStatusBadgeRow(
                    label = "Fallback order",
                    badgeText = "NOT IMPLEMENTED",
                    badgeType = SettingsBadgeType.NOT_IMPLEMENTED
                )
            }

            // SYSTEM INTEGRATION (FUTURE)
            SettingsSection(title = "SYSTEM INTEGRATION (FUTURE)") {
                SettingsStatusBadgeRow(
                    label = "Wake word detection",
                    badgeText = "COMING LATER",
                    badgeType = SettingsBadgeType.COMING_LATER
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.surfaceVariant)
                SettingsStatusBadgeRow(
                    label = "Background assistant",
                    badgeText = "COMING LATER",
                    badgeType = SettingsBadgeType.COMING_LATER
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.surfaceVariant)
                SettingsStatusBadgeRow(
                    label = "Accessibility integration",
                    badgeText = "COMING LATER",
                    badgeType = SettingsBadgeType.COMING_LATER
                )
            }

            // EXTERNAL TOOLS (FUTURE)
            SettingsSection(title = "EXTERNAL TOOLS (FUTURE)") {
                SettingsStatusBadgeRow(
                    label = "GitHub connection",
                    badgeText = "NOT CONNECTED",
                    badgeType = SettingsBadgeType.NOT_CONNECTED
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.surfaceVariant)
                SettingsStatusBadgeRow(
                    label = "Termux connection",
                    badgeText = "NOT CONNECTED",
                    badgeType = SettingsBadgeType.NOT_CONNECTED
                )
            }
        }
    }
}

@Composable
fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                content()
            }
        }
    }
}

@Composable
fun SettingsLockedSafetyRow(label: String, description: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = "Locked on",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "LOCKED ON",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@Composable
fun SettingsSwitchRow(
    label: String,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
fun SettingsStatusBadgeRow(
    label: String,
    badgeText: String,
    badgeType: SettingsBadgeType
) {
    val (containerColor, contentColor) = when (badgeType) {
        SettingsBadgeType.LOCKED_ON -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        SettingsBadgeType.COMING_LATER -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        SettingsBadgeType.NOT_CONNECTED -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        SettingsBadgeType.NOT_IMPLEMENTED -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).padding(end = 8.dp)
        )
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = containerColor
        ) {
            Text(
                text = badgeText,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = contentColor,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}
