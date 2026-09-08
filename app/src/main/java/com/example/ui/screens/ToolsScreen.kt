package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.engine.Tool
import com.example.engine.ToolType
import com.example.data.AccessPolicy

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(
    tools: List<Tool>,
    onToggleToolEnabled: (toolId: String, enabled: Boolean) -> Unit,
    onUpdateAppPolicy: (packageName: String, policy: AccessPolicy) -> Unit,
    onRefreshTools: () -> Unit = {},
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tools Registry") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onRefreshTools) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh installed tools")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(tools) { tool ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (tool.enabled) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = tool.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (tool.toolType == ToolType.WEB) {
                                        if (tool.enabled) "JARVIS Integration: Active" else "JARVIS Integration: Disabled"
                                    } else if (tool.installedOrAvailable) {
                                        if (tool.enabled) "JARVIS Permission: Allowed" else "JARVIS Permission: Disabled"
                                    } else {
                                        if (tool.enabled) "JARVIS Permission: Allowed (App not installed on device)" else "JARVIS Permission: Disabled"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (tool.source == "DISCOVERED") {
                                var expanded by remember { mutableStateOf(false) }
                                Box {
                                    TextButton(onClick = { expanded = true }) {
                                        Text(tool.policy.name)
                                    }
                                    DropdownMenu(
                                        expanded = expanded,
                                        onDismissRequest = { expanded = false }
                                    ) {
                                        AccessPolicy.entries.forEach { policy ->
                                            DropdownMenuItem(
                                                text = { Text(policy.name) },
                                                onClick = {
                                                    tool.installedPackageName?.let { pkg ->
                                                        onUpdateAppPolicy(pkg, policy)
                                                    }
                                                    expanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                            } else {
                                Switch(
                                    checked = tool.enabled,
                                    onCheckedChange = { onToggleToolEnabled(tool.id, it) }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val (statusText, containerColor, contentColor) = when {
                                tool.source == "DISCOVERED" -> Triple(
                                    "DISCOVERED APP",
                                    MaterialTheme.colorScheme.secondaryContainer,
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                tool.toolType == ToolType.WEB -> Triple(
                                    "WEB TOOL",
                                    MaterialTheme.colorScheme.tertiaryContainer,
                                    MaterialTheme.colorScheme.onTertiaryContainer
                                )
                                tool.installedOrAvailable -> Triple(
                                    "STANDARD APP",
                                    MaterialTheme.colorScheme.primaryContainer,
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                else -> Triple(
                                    "NOT INSTALLED",
                                    MaterialTheme.colorScheme.errorContainer,
                                    MaterialTheme.colorScheme.onErrorContainer
                                )
                            }

                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = containerColor
                            ) {
                                Text(
                                    text = statusText,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = contentColor,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }

                            if (tool.aliases.isNotEmpty()) {
                                Text(
                                    text = "Aliases: ${tool.aliases.joinToString(", ")}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = tool.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Capabilities: ${tool.capabilities.joinToString(", ")}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Text(
                            text = "Prefers: ${tool.preferredUses}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}
