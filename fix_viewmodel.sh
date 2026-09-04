#!/bin/bash
sed -i '/val activityLogs = repository.allLogs/d' app/src/main/java/com/example/ui/JarvisViewModel.kt
sed -i '/val tools = toolRegistry.tools/d' app/src/main/java/com/example/ui/JarvisViewModel.kt
sed -i 's/    val localProcessingEnabled: StateFlow<Boolean> = settingsManager.localProcessingFlow.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, true)/    val localProcessingEnabled: StateFlow<Boolean> = settingsManager.localProcessingFlow.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, true)\n    val activityLogs = repository.allLogs\n    val tools = toolRegistry.tools/g' app/src/main/java/com/example/ui/JarvisViewModel.kt
