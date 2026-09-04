package com.example

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.room.Room
import com.example.data.ActivityRepository
import com.example.data.AppDatabase
import com.example.engine.CommandCategory
import com.example.engine.ParsedCommand
import com.example.ui.JarvisViewModel
import com.example.ui.screens.ActivityScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.ToolsScreen
import com.example.ui.theme.JARVISTheme

class MainActivity : ComponentActivity() {

    private lateinit var database: AppDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        database = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java, "jarvis-database"
        ).build()

        val repository = ActivityRepository(database.activityLogDao())

        setContent {
            JARVISTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    val viewModel: JarvisViewModel = viewModel(
                        factory = object : ViewModelProvider.Factory {
                            @Suppress("UNCHECKED_CAST")
                            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                                return JarvisViewModel(repository) as T
                            }
                        }
                    )

                    val uiState by viewModel.uiState.collectAsState()
                    val recentLogs by viewModel.activityLogs.collectAsState()
                    val tools by viewModel.tools.collectAsState()

                    val executeAction: (ParsedCommand) -> Unit = { command ->
                        executeCommand(command)
                    }

                    NavHost(navController = navController, startDestination = "home") {
                        composable("home") {
                            HomeScreen(
                                uiState = uiState,
                                recentLogs = recentLogs,
                                onCommandSubmit = { text -> viewModel.processCommand(text, executeAction) },
                                onApprove = { viewModel.approvePending(executeAction) },
                                onReject = { viewModel.rejectPending() },
                                onNavigateToTools = { navController.navigate("tools") },
                                onNavigateToActivity = { navController.navigate("activity") },
                                onNavigateToSettings = { navController.navigate("settings") }
                            )
                        }
                        composable("tools") {
                            ToolsScreen(tools = tools, onBack = { navController.popBackStack() })
                        }
                        composable("activity") {
                            ActivityScreen(logs = recentLogs, onBack = { navController.popBackStack() })
                        }
                        composable("settings") {
                            SettingsScreen(onBack = { navController.popBackStack() })
                        }
                    }
                }
            }
        }
    }

    private fun executeCommand(command: ParsedCommand) {
        when (command.category) {
            CommandCategory.DEVICE_ACTION -> {
                val targetName = command.targetAppOrPerson?.lowercase() ?: return
                // Hardcoded common packages for demonstration of app launching
                val pkg = when (targetName) {
                    "github" -> "com.github.android"
                    "termux" -> "com.termux"
                    "acode" -> "com.foxdebug.acode"
                    "spck" -> "io.spck"
                    "code studio" -> "com.qamar.ide"
                    "pydroid" -> "ru.iiec.pydroid3"
                    "settings" -> {
                        startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                        return
                    }
                    else -> null
                }

                if (pkg != null) {
                    val intent = packageManager.getLaunchIntentForPackage(pkg)
                    if (intent != null) {
                        startActivity(intent)
                    } else {
                        Toast.makeText(this, "$targetName is not installed.", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "Don't know how to launch $targetName", Toast.LENGTH_SHORT).show()
                }
            }
            CommandCategory.COMMUNICATION -> {
                val intent = Intent(Intent.ACTION_SENDTO)
                if (command.rawText.lowercase().startsWith("email")) {
                    intent.data = Uri.parse("mailto:")
                    val subject = "Message for ${command.targetAppOrPerson}"
                    intent.putExtra(Intent.EXTRA_SUBJECT, subject)
                    intent.putExtra(Intent.EXTRA_TEXT, command.messageOrQuery)
                } else if (command.rawText.lowercase().startsWith("text")) {
                    intent.data = Uri.parse("smsto:")
                    intent.putExtra("sms_body", command.messageOrQuery)
                } else if (command.rawText.lowercase().startsWith("call")) {
                    intent.action = Intent.ACTION_DIAL
                    intent.data = Uri.parse("tel:")
                }
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "No app available to handle this request.", Toast.LENGTH_SHORT).show()
                }
            }
            CommandCategory.DEVELOPMENT -> {
                Toast.makeText(this, "Development command acknowledged (placeholder)", Toast.LENGTH_SHORT).show()
            }
            else -> {
                // Do nothing
            }
        }
    }
}
