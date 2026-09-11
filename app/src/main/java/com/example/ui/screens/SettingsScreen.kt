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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.ui.JarvisViewModel
import kotlinx.coroutines.launch

enum class SettingsBadgeType {
    LOCKED_ON,
    COMING_LATER,
    NOT_CONNECTED,
    NOT_IMPLEMENTED,
    CONNECTED,
    WARNING
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: JarvisViewModel, onBack: () -> Unit) {
    val coroutineScope = rememberCoroutineScope()
    val localProcessing by viewModel.localProcessingEnabled.collectAsState()
    val spokenResponses by viewModel.spokenResponsesEnabled.collectAsState()
    val handsFree by viewModel.handsFreeEnabled.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val geminiApiKey by viewModel.geminiApiKey.collectAsState()
    var showApiKeyField by remember { mutableStateOf(false) }
    var apiKeyInput by remember { mutableStateOf("") }

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

            // AI CONFIGURATION
            SettingsSection(title = "AI CONFIGURATION") {
                SettingsStatusBadgeRow(
                    label = "Gemini AI fallback",
                    badgeText = if (geminiApiKey.isNotBlank()) "CONFIGURED" else "NOT CONFIGURED",
                    badgeType = if (geminiApiKey.isNotBlank()) SettingsBadgeType.CONNECTED else SettingsBadgeType.NOT_CONNECTED
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.surfaceVariant)

                if (showApiKeyField) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Enter your Gemini API key for AI-powered command understanding:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = apiKeyInput,
                            onValueChange = { apiKeyInput = it },
                            label = { Text("Gemini API Key") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    coroutineScope.launch {
                                        viewModel.settingsManager.setGeminiApiKey(apiKeyInput)
                                        viewModel.refreshGeminiApiKey()
                                    }
                                    showApiKeyField = false
                                    apiKeyInput = ""
                                },
                                enabled = apiKeyInput.isNotBlank()
                            ) {
                                Text("Save")
                            }
                            OutlinedButton(onClick = {
                                showApiKeyField = false
                                apiKeyInput = ""
                            }) {
                                Text("Cancel")
                            }
                        }
                    }
                } else {
                    Button(onClick = {
                        apiKeyInput = geminiApiKey
                        showApiKeyField = true
                    }) {
                        Text(if (geminiApiKey.isNotBlank()) "Update API Key" else "Add Gemini API Key")
                    }
                }
            }

            // VOICE & CONVERSATION (ACTIVE & FOUNDATIONAL)
            val voiceContext = androidx.compose.ui.platform.LocalContext.current
            val handsFreeState by viewModel.handsFreeServiceState.collectAsState()

            val permissionsToRequest = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                arrayOf(
                    android.Manifest.permission.RECORD_AUDIO,
                    android.Manifest.permission.POST_NOTIFICATIONS
                )
            } else {
                arrayOf(android.Manifest.permission.RECORD_AUDIO)
            }

            val handsFreePermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                contract = androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
            ) { permissionsMap ->
                val micGranted = permissionsMap[android.Manifest.permission.RECORD_AUDIO]
                    ?: (androidx.core.content.ContextCompat.checkSelfPermission(voiceContext, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                val notifGranted = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    permissionsMap[android.Manifest.permission.POST_NOTIFICATIONS]
                        ?: (androidx.core.content.ContextCompat.checkSelfPermission(voiceContext, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                } else true

                if (micGranted && notifGranted) {
                    viewModel.startHandsFree(voiceContext)
                }
            }

            SettingsSection(title = "VOICE & CONVERSATION") {
                SettingsSwitchRow(
                    label = "Spoken responses",
                    description = "JARVIS speaks responses aloud after executing commands",
                    checked = spokenResponses,
                    onCheckedChange = {
                        coroutineScope.launch { viewModel.settingsManager.setSpokenResponses(it) }
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.surfaceVariant)
                SettingsSwitchRow(
                    label = "Hands-free mode",
                    description = "Continuously listen for commands without pressing the mic button",
                    checked = handsFree,
                    onCheckedChange = { enabled ->
                        coroutineScope.launch { viewModel.settingsManager.setHandsFree(enabled) }
                        if (enabled) {
                            val allGranted = permissionsToRequest.all { perm ->
                                androidx.core.content.ContextCompat.checkSelfPermission(voiceContext, perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
                            }
                            if (allGranted) {
                                viewModel.startHandsFree(voiceContext)
                            } else {
                                handsFreePermissionLauncher.launch(permissionsToRequest)
                            }
                        } else {
                            viewModel.stopHandsFree(voiceContext)
                        }
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.surfaceVariant)
                SettingsStatusBadgeRow(
                    label = "Wake word engine",
                    badgeText = uiState.wakeWordStatus.name,
                    badgeType = when (uiState.wakeWordStatus) {
                        com.example.engine.voice.wakeword.WakeWordEngineStatus.READY -> SettingsBadgeType.CONNECTED
                        com.example.engine.voice.wakeword.WakeWordEngineStatus.LISTENING -> SettingsBadgeType.CONNECTED
                        com.example.engine.voice.wakeword.WakeWordEngineStatus.DISABLED -> SettingsBadgeType.NOT_CONNECTED
                        else -> SettingsBadgeType.NOT_CONNECTED
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.surfaceVariant)
                SettingsStatusBadgeRow(
                    label = "Speaker verification",
                    badgeText = uiState.speakerEnrollmentState.name,
                    badgeType = when (uiState.speakerEnrollmentState) {
                        com.example.engine.voice.speaker.SpeakerEnrollmentState.ENROLLED -> SettingsBadgeType.CONNECTED
                        com.example.engine.voice.speaker.SpeakerEnrollmentState.NOT_ENROLLED -> SettingsBadgeType.NOT_CONNECTED
                        else -> SettingsBadgeType.NOT_CONNECTED
                    }
                )
            }

            // SPEECH RECOGNITION
            SettingsSection(title = "SPEECH RECOGNITION") {
                SettingsStatusBadgeRow(
                    label = "Active backend",
                    badgeText = uiState.speechBackend.name,
                    badgeType = SettingsBadgeType.CONNECTED
                )
                if (uiState.lastSpeechError != null) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.surfaceVariant)
                    SettingsStatusBadgeRow(
                        label = "Last error",
                        badgeText = uiState.lastSpeechError ?: "None",
                        badgeType = SettingsBadgeType.WARNING
                    )
                }
            }

            // TERMUX INTEGRATION
            SettingsSection(title = "TERMUX INTEGRATION") {
                SettingsStatusBadgeRow(
                    label = "Termux status",
                    badgeText = uiState.termuxStatus.connectionState.name,
                    badgeType = when (uiState.termuxStatus.connectionState) {
                        com.example.engine.termux.TermuxConnectionState.READY -> SettingsBadgeType.CONNECTED
                        com.example.engine.termux.TermuxConnectionState.TERMUX_NOT_INSTALLED -> SettingsBadgeType.NOT_CONNECTED
                        com.example.engine.termux.TermuxConnectionState.TERMUX_PERMISSION_REQUIRED -> SettingsBadgeType.WARNING
                        com.example.engine.termux.TermuxConnectionState.SETUP_REQUIRED -> SettingsBadgeType.WARNING
                        else -> SettingsBadgeType.NOT_CONNECTED
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.surfaceVariant)
                SettingsStatusBadgeRow(
                    label = "Active workspace",
                    badgeText = uiState.activeWorkspace?.displayName ?: "None configured",
                    badgeType = if (uiState.activeWorkspace != null) SettingsBadgeType.CONNECTED else SettingsBadgeType.NOT_CONNECTED
                )
            }

            // PHONE CONTROL (NEW)
            SettingsSection(title = "PHONE CONTROL") {
                SettingsStatusBadgeRow(
                    label = "Volume control",
                    badgeText = "ACTIVE",
                    badgeType = SettingsBadgeType.CONNECTED
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.surfaceVariant)
                SettingsStatusBadgeRow(
                    label = "Brightness control",
                    badgeText = if (android.provider.Settings.System.canWrite(voiceContext)) "ACTIVE" else "NEEDS PERMISSION",
                    badgeType = if (android.provider.Settings.System.canWrite(voiceContext)) SettingsBadgeType.CONNECTED else SettingsBadgeType.WARNING
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.surfaceVariant)
                SettingsStatusBadgeRow(
                    label = "Flashlight control",
                    badgeText = "ACTIVE",
                    badgeType = SettingsBadgeType.CONNECTED
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.surfaceVariant)
                SettingsStatusBadgeRow(
                    label = "Media playback",
                    badgeText = "ACTIVE",
                    badgeType = SettingsBadgeType.CONNECTED
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.surfaceVariant)
                SettingsStatusBadgeRow(
                    label = "Alarms & timers",
                    badgeText = "ACTIVE",
                    badgeType = SettingsBadgeType.CONNECTED
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.surfaceVariant)
                SettingsStatusBadgeRow(
                    label = "WiFi / Bluetooth",
                    badgeText = "ACTIVE",
                    badgeType = SettingsBadgeType.CONNECTED
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.surfaceVariant)
                SettingsStatusBadgeRow(
                    label = "Web search & URLs",
                    badgeText = "ACTIVE",
                    badgeType = SettingsBadgeType.CONNECTED
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.surfaceVariant)
                SettingsStatusBadgeRow(
                    label = "Do Not Disturb",
                    badgeText = "ACTIVE",
                    badgeType = SettingsBadgeType.CONNECTED
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
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
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
        SettingsBadgeType.CONNECTED -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        SettingsBadgeType.WARNING -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
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
