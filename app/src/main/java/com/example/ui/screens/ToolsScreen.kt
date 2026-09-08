package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.engine.Tool
import com.example.engine.ToolType
import com.example.engine.ToolsFilter
import com.example.data.AccessPolicy

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(
    tools: List<Tool>,
    onToggleToolEnabled: (toolId: String, enabled: Boolean) -> Unit = { _, _ -> },
    onUpdateAppPolicy: (packageName: String, policy: AccessPolicy) -> Unit = { _, _ -> },
    onUpdatePolicy: (tool: Tool, policy: AccessPolicy) -> Unit = { tool, policy ->
        tool.installedPackageName?.let { pkg -> onUpdateAppPolicy(pkg, policy) }
        onToggleToolEnabled(tool.id, policy != AccessPolicy.BLOCK)
    },
    onRefreshTools: () -> Unit = {},
    onBack: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredTools = remember(tools, searchQuery) {
        ToolsFilter.filter(tools, searchQuery)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tools & Apps Registry") },
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
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("tools_search_input"),
                    placeholder = { Text("Search tools or apps (Spotify, Termux...)...") },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = "Search tools")
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear search")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            if (filteredTools.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "No matching tools or apps",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "No results for \"$searchQuery\"",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            items(filteredTools) { tool ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (tool.policy != AccessPolicy.BLOCK && tool.enabled) {
                            MaterialTheme.colorScheme.surface
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        }
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
                                    text = when {
                                        tool.policy == AccessPolicy.BLOCK -> "Access: Blocked in JARVIS"
                                        tool.policy == AccessPolicy.ASK_EACH_TIME -> "Access: Ask confirmation each time"
                                        tool.toolType == ToolType.WEB -> "Access: Active (Web)"
                                        tool.installedOrAvailable -> "Access: Allowed (Installed)"
                                        else -> "Access: Allowed (Not installed on device)"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            // Unified Policy Selector for all tools and apps
                            var expanded by remember { mutableStateOf(false) }
                            Box {
                                val (policyButtonColor, policyTextColor) = when (tool.policy) {
                                    AccessPolicy.ALLOW -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
                                    AccessPolicy.ASK_EACH_TIME -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
                                    AccessPolicy.BLOCK -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
                                }

                                FilledTonalButton(
                                    onClick = { expanded = true },
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = policyButtonColor,
                                        contentColor = policyTextColor
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = tool.policy.name,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                DropdownMenu(
                                    expanded = expanded,
                                    onDismissRequest = { expanded = false }
                                ) {
                                    AccessPolicy.entries.forEach { policy ->
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text(
                                                        text = policy.name,
                                                        fontWeight = if (policy == tool.policy) FontWeight.Bold else FontWeight.Normal
                                                    )
                                                    Text(
                                                        text = when (policy) {
                                                            AccessPolicy.ALLOW -> "Allowed without prompt"
                                                            AccessPolicy.ASK_EACH_TIME -> "Ask confirmation each time"
                                                            AccessPolicy.BLOCK -> "Blocked / Disabled"
                                                        },
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            },
                                            onClick = {
                                                onUpdatePolicy(tool, policy)
                                                expanded = false
                                            }
                                        )
                                    }
                                }
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
            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}
