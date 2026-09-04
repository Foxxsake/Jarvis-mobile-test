package com.example

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
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
import com.example.engine.ContactResolver
import com.example.engine.ToolExecutor
import com.example.engine.ToolRegistry
import com.example.engine.contacts.AndroidContactsProvider
import com.example.engine.speech.SpeechManager
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
    private lateinit var contactsProvider: AndroidContactsProvider
    private lateinit var contactResolver: ContactResolver
    private lateinit var speechManager: SpeechManager

    private var activeViewModel: JarvisViewModel? = null
    private var hasRequestedMicPermission = false
    private var hasRequestedContactsPermission = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        activeViewModel?.let { vm ->
            val pendingPerm = vm.uiState.value.permissionRationaleNeeded
            if (isGranted) {
                if (pendingPerm == "MIC") {
                    speechManager.startListening()
                } else if (pendingPerm == "CONTACTS") {
                    val pendingCmd = vm.uiState.value.pendingApproval
                    if (pendingCmd != null) {
                        vm.processCommand(pendingCmd.rawText)
                    }
                }
            } else {
                val permString = if (pendingPerm == "MIC") {
                    android.Manifest.permission.RECORD_AUDIO
                } else {
                    android.Manifest.permission.READ_CONTACTS
                }
                val shouldShowRationale = androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(this, permString)
                if (!shouldShowRationale) {
                    if (pendingPerm != null) {
                        vm.showPermissionPermanentlyDenied(pendingPerm)
                    }
                }
            }
            vm.dismissPermissionRationale()
        }
    }

    private fun openAppSettings() {
        val intent = android.content.Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.fromParts("package", packageName, null)
        )
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

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
        contactsProvider = AndroidContactsProvider(applicationContext)
        contactResolver = ContactResolver(contactsProvider)
        toolExecutor = ToolExecutor(applicationContext, toolRegistry, contactResolver)
        speechManager = SpeechManager(applicationContext)

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
                                return JarvisViewModel(
                                    repository = repository,
                                    settingsManager = settingsManager,
                                    toolRegistry = toolRegistry,
                                    toolExecutor = toolExecutor,
                                    contactResolver = contactResolver,
                                    speechManager = speechManager
                                ) as T
                            }
                        }
                    )

                    activeViewModel = viewModel

                    val uiState by viewModel.uiState.collectAsState()
                    val recentLogs by viewModel.activityLogs.collectAsState()
                    val tools by viewModel.tools.collectAsState()

                    androidx.compose.runtime.LaunchedEffect(uiState.permissionRationaleNeeded) {
                        if (uiState.permissionRationaleNeeded == "CONTACTS") {
                            val shouldShow = androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(
                                this@MainActivity,
                                android.Manifest.permission.READ_CONTACTS
                            )
                            if (hasRequestedContactsPermission && !shouldShow) {
                                viewModel.dismissPermissionRationale()
                                viewModel.showPermissionPermanentlyDenied("CONTACTS")
                            }
                        }
                    }

                    NavHost(navController = navController, startDestination = "home") {
                        composable("home") {
                            HomeScreen(
                                uiState = uiState,
                                recentLogs = recentLogs,
                                onCommandSubmit = { text -> viewModel.processCommand(text) },
                                onMicClick = {
                                    if (ContextCompat.checkSelfPermission(
                                            this@MainActivity,
                                            android.Manifest.permission.RECORD_AUDIO
                                        ) == PackageManager.PERMISSION_GRANTED
                                    ) {
                                        speechManager.startListening()
                                    } else {
                                        val shouldShowRationale = androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(
                                            this@MainActivity,
                                            android.Manifest.permission.RECORD_AUDIO
                                        )
                                        if (hasRequestedMicPermission && !shouldShowRationale) {
                                            viewModel.showPermissionPermanentlyDenied("MIC")
                                        } else {
                                            viewModel.showPermissionRationale("MIC")
                                        }
                                    }
                                },
                                onApprove = { viewModel.approvePending() },
                                onReject = { viewModel.rejectPending() },
                                onSelectCandidate = { candidate -> viewModel.selectContactCandidate(candidate) },
                                onSelectDestination = { destination -> viewModel.selectContactDestination(destination) },
                                onRequestPermission = { permission ->
                                    if (permission == android.Manifest.permission.RECORD_AUDIO) {
                                        hasRequestedMicPermission = true
                                    } else if (permission == android.Manifest.permission.READ_CONTACTS) {
                                        hasRequestedContactsPermission = true
                                    }
                                    permissionLauncher.launch(permission)
                                },
                                onDismissRationale = { viewModel.dismissPermissionRationale() },
                                onDismissPermanentlyDenied = { viewModel.dismissPermissionPermanentlyDenied() },
                                onOpenAppSettings = { openAppSettings() },
                                onNavigateToTools = { navController.navigate("tools") },
                                onNavigateToActivity = { navController.navigate("activity") },
                                onNavigateToSettings = { navController.navigate("settings") }
                            )
                        }
                        composable("tools") {
                            ToolsScreen(
                                tools = tools,
                                onToggleToolEnabled = { id, enabled -> viewModel.toggleToolEnabled(id, enabled) },
                                onRefreshTools = { viewModel.refreshTools() },
                                onBack = { navController.popBackStack() }
                            )
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

    override fun onDestroy() {
        super.onDestroy()
        speechManager.destroyRecognizer()
    }
}
