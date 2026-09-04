package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ActivityLog
import com.example.engine.ParsedCommand
import com.example.ui.JarvisUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    uiState: JarvisUiState,
    recentLogs: List<ActivityLog>,
    onCommandSubmit: (String) -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onNavigateToTools: () -> Unit,
    onNavigateToActivity: () -> Unit,
    onNavigateToSettings: () -> Unit,
) {
    var inputText by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 24.dp)
                    .statusBarsPadding(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column {
                    Text(
                        text = "JARVIS MOBILE",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelSmall
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (uiState.status == "Ready") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(uiState.status, fontSize = 18.sp, fontWeight = FontWeight.Light, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.5f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 0.dp,
            ) {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                    label = { Text("HOME", style = MaterialTheme.typography.labelSmall) },
                    selected = true,
                    onClick = { },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = Color.Transparent,
                        unselectedIconColor = MaterialTheme.colorScheme.tertiary,
                        unselectedTextColor = MaterialTheme.colorScheme.tertiary
                    )
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Build, contentDescription = "Tools") },
                    label = { Text("TOOLS", style = MaterialTheme.typography.labelSmall) },
                    selected = false,
                    onClick = onNavigateToTools,
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = Color.Transparent,
                        unselectedIconColor = MaterialTheme.colorScheme.tertiary,
                        unselectedTextColor = MaterialTheme.colorScheme.tertiary
                    )
                )
                NavigationBarItem(
                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Log") },
                    label = { Text("LOG", style = MaterialTheme.typography.labelSmall) },
                    selected = false,
                    onClick = onNavigateToActivity,
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = Color.Transparent,
                        unselectedIconColor = MaterialTheme.colorScheme.tertiary,
                        unselectedTextColor = MaterialTheme.colorScheme.tertiary
                    )
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Config") },
                    label = { Text("CONFIG", style = MaterialTheme.typography.labelSmall) },
                    selected = false,
                    onClick = onNavigateToSettings,
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = Color.Transparent,
                        unselectedIconColor = MaterialTheme.colorScheme.tertiary,
                        unselectedTextColor = MaterialTheme.colorScheme.tertiary
                    )
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Main Content Area
            Column(modifier = Modifier.weight(1f)) {
                // Approval Card
                if (uiState.pendingApproval != null) {
                    ApprovalCard(
                        command = uiState.pendingApproval,
                        planText = uiState.planToApprove ?: "",
                        onApprove = onApprove,
                        onReject = onReject
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                }

                // Recent Commands
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "RECENT ACTIVITY",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    TextButton(onClick = onNavigateToActivity, contentPadding = PaddingValues(0.dp)) {
                        Text("VIEW ALL", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f))
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(recentLogs.take(5)) { log ->
                        val isSuccess = log.status != "Rejected"
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.background.copy(alpha=0.5f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isSuccess) Icons.Default.Build else Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = if (isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = log.command, style = MaterialTheme.typography.bodyMedium, color = if(isSuccess) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.tertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    text = "${log.classification} • ${log.status}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                        }
                    }
                }
            }

            // Input Area
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp, top = 16.dp)
            ) {
                // Central Mic Button
                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(bottom = 24.dp)) {
                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .blur(24.dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), CircleShape)
                    )
                    Surface(
                        modifier = Modifier.size(80.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.background,
                        border = androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = "Voice setup coming next",
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                Text(
                    text = "Voice setup coming next",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // Text Input
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Enter command...", color = MaterialTheme.colorScheme.tertiary) },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            cursorColor = MaterialTheme.colorScheme.primary
                        )
                    )
                    IconButton(onClick = {
                        if (inputText.isNotBlank()) {
                            onCommandSubmit(inputText)
                            inputText = ""
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
fun ApprovalCard(
    command: ParsedCommand,
    planText: String,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(28.dp)
    ) {
        Box(modifier = Modifier.padding(20.dp)) {
            // Pending Approval Badge
            Surface(
                color = MaterialTheme.colorScheme.secondary,
                shape = CircleShape,
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                Text(
                    text = "PENDING APPROVAL",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            Column(modifier = Modifier.padding(top = 8.dp)) {
                Text(text = "Requested Action", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(text = command.rawText, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onReject,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha=0.1f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
                    ) {
                        Text("REJECT", style = MaterialTheme.typography.labelMedium)
                    }
                    Button(
                        onClick = onApprove,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary, contentColor = MaterialTheme.colorScheme.onSecondary)
                    ) {
                        Text("APPROVE", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
