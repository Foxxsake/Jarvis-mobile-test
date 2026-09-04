package com.example

import android.os.Bundle
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
import com.example.data.SettingsManager
import com.example.engine.ToolExecutor
import com.example.engine.ToolRegistry
import com.example.ui.JarvisViewModel
import com.example.ui.screens.ActivityScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.ToolsScreen
import com.example.ui.theme.JARVISTheme

class MainActivity : ComponentActivity() {

    private lateinit var database: AppDatabase
    private lateinit var settingsManager: SettingsManager
    private lateinit var toolRegistry: ToolRegistry
    private lateinit var toolExecutor: ToolExecutor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        database = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java, "jarvis-database"
        ).build()

        val repository = ActivityRepository(database.activityLogDao())
        settingsManager = SettingsManager(applicationContext)
        toolRegistry = ToolRegistry(applicationContext)
        toolExecutor = ToolExecutor(applicationContext, toolRegistry)

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
                                return JarvisViewModel(repository, settingsManager, toolRegistry, toolExecutor) as T
                            }
                        }
                    )

                    val uiState by viewModel.uiState.collectAsState()
                    val recentLogs by viewModel.activityLogs.collectAsState()
                    val tools by viewModel.tools.collectAsState()

                    NavHost(navController = navController, startDestination = "home") {
                        composable("home") {
                            HomeScreen(
                                uiState = uiState,
                                recentLogs = recentLogs,
                                onCommandSubmit = { text -> viewModel.processCommand(text) },
                                onApprove = { viewModel.approvePending() },
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
                            SettingsScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
                        }
                    }
                }
            }
        }
    }
}
