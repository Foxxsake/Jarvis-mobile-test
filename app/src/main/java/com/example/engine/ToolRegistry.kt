package com.example.engine

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import com.example.data.AccessPolicy
import com.example.data.AppPolicy
import com.example.data.AppPolicyDao
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ToolRegistry(
    private val context: Context,
    private val appPolicyDao: AppPolicyDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val _tools = MutableStateFlow<List<Tool>>(STANDARD_TOOLS)
    val tools: StateFlow<List<Tool>> = _tools.asStateFlow()

    private var disabledIds: Set<String> = emptySet()
    private val toolMatcher = ToolCommandMatcher { _tools.value }
    private val scope = CoroutineScope(ioDispatcher)

    init {
        scope.launch {
            appPolicyDao.getAllPoliciesFlow().collect { policies ->
                refreshToolsInternal(policies)
            }
        }
    }

    fun updateDisabledTools(disabledToolIds: Set<String>) {
        this.disabledIds = disabledToolIds
        _tools.value = _tools.value.map { tool ->
            val isEnabled = !disabledIds.contains(tool.id) && tool.policy != AccessPolicy.BLOCK
            tool.copy(enabled = isEnabled)
        }
        refreshTools()
    }

    fun refreshTools() {
        scope.launch {
            val policies = appPolicyDao.getAllPolicies()
            refreshToolsInternal(policies)
        }
    }

    private suspend fun refreshToolsInternal(policies: List<AppPolicy>) = withContext(ioDispatcher) {
        val policyMap = policies.associateBy { it.packageName }
        val pm = context.packageManager
        
        // Load discovered apps
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
        
        val dynamicTools = mutableMapOf<String, Tool>()
        for (info in resolveInfos) {
            val pkg = info.activityInfo.packageName
            val label = info.loadLabel(pm).toString()
            val policy = policyMap[pkg]?.policy ?: AccessPolicy.ASK_EACH_TIME
            
            // Generate tool ID for discovered apps
            val toolId = "app:$pkg"
            val isEnabled = !disabledIds.contains(toolId) && policy != AccessPolicy.BLOCK

            if (!dynamicTools.containsKey(pkg)) {
                dynamicTools[pkg] = Tool(
                    id = toolId,
                    name = label,
                    description = "Installed Android Application",
                    capabilities = listOf("app", "launch"),
                    preferredUses = "Launching $label",
                    toolType = ToolType.APP,
                    packageNames = listOf(pkg),
                    installedPackageName = pkg,
                    installedOrAvailable = true,
                    enabled = isEnabled,
                    policy = policy,
                    source = "DISCOVERED"
                )
            }
        }

        // Merge with standard tools
        val mergedTools = STANDARD_TOOLS.map { stdTool ->
            var isEnabled = !disabledIds.contains(stdTool.id)
            if (stdTool.toolType == ToolType.APP && stdTool.packageNames.isNotEmpty()) {
                val detectedPackage = findInstalledPackage(stdTool.packageNames, pm)
                
                // If it's installed, use the policy for that package, fallback to ID
                val pkgPolicy = policyMap[detectedPackage]?.policy ?: policyMap[stdTool.id]?.policy ?: AccessPolicy.ALLOW
                
                if (pkgPolicy == AccessPolicy.BLOCK) isEnabled = false

                // Remove from dynamic list to avoid duplicates
                if (detectedPackage != null) {
                    dynamicTools.remove(detectedPackage)
                }

                stdTool.copy(
                    installedOrAvailable = detectedPackage != null,
                    installedPackageName = detectedPackage,
                    enabled = isEnabled,
                    policy = pkgPolicy,
                    source = "STANDARD"
                )
            } else {
                val pol = policyMap[stdTool.id]?.policy ?: AccessPolicy.ALLOW
                val finalEnabled = if (pol == AccessPolicy.BLOCK) false else isEnabled
                stdTool.copy(enabled = finalEnabled, policy = pol, source = "STANDARD")
            }
        }.toMutableList()

        mergedTools.addAll(dynamicTools.values)
        
        // Update state
        _tools.value = mergedTools
    }

    private fun findInstalledPackage(packageNames: List<String>, pm: PackageManager): String? {
        for (pkg in packageNames) {
            try {
                pm.getPackageInfo(pkg, 0)
                return pkg
            } catch (_: Exception) {
            }
        }
        return null
    }

    suspend fun setAppPolicy(packageName: String, policy: AccessPolicy) {
        appPolicyDao.insertPolicy(AppPolicy(packageName, policy))
        _tools.value = _tools.value.map { t ->
            if (t.packageNames.contains(packageName) || t.installedPackageName == packageName) {
                t.copy(policy = policy, enabled = policy != AccessPolicy.BLOCK && !disabledIds.contains(t.id))
            } else t
        }
    }

    suspend fun setToolPolicy(tool: Tool, policy: AccessPolicy) {
        // Persist policy by tool ID
        appPolicyDao.insertPolicy(AppPolicy(tool.id, policy))
        // Persist policy by installed package name if present
        tool.installedPackageName?.let { pkg ->
            appPolicyDao.insertPolicy(AppPolicy(pkg, policy))
        }
        // Persist policy by declared package names
        for (pkg in tool.packageNames) {
            appPolicyDao.insertPolicy(AppPolicy(pkg, policy))
        }
        _tools.value = _tools.value.map { t ->
            if (t.id == tool.id || (tool.installedPackageName != null && t.installedPackageName == tool.installedPackageName)) {
                t.copy(policy = policy, enabled = policy != AccessPolicy.BLOCK && !disabledIds.contains(t.id))
            } else t
        }
    }

    fun findToolOutcome(query: String): ToolMatchOutcome {
        val clean = query.trim()
        if (clean.isBlank()) return ToolMatchOutcome.NoMatch

        val currentList = _tools.value
        return toolMatcher.matchSingleTarget(clean, currentList)
    }

    fun findTool(query: String): Tool? {
        val outcome = findToolOutcome(query)
        if (outcome is ToolMatchOutcome.Success) {
            return outcome.result.tool
        }
        return null
    }

    companion object {
        val STANDARD_TOOLS = listOf(
            Tool(
                policy = AccessPolicy.ALLOW, id = "github",
                name = "GitHub",
                description = "Repository storage, Version control, Issues/code collaboration",
                capabilities = listOf("git", "issues", "code review"),
                preferredUses = "Version control and project sharing",
                toolType = ToolType.APP,
                packageNames = listOf("com.github.android"),
                aliases = listOf("github", "gh")
            ),
            Tool(
                policy = AccessPolicy.ALLOW, id = "termux",
                name = "Termux",
                description = "Commands, Git, Node/npm, Build/test, Automation",
                capabilities = listOf("shell", "cli", "linux"),
                preferredUses = "Local builds and automation",
                toolType = ToolType.APP,
                packageNames = listOf("com.termux"),
                aliases = listOf("termux", "terminal", "shell")
            ),
            Tool(
                policy = AccessPolicy.ALLOW, id = "acode",
                name = "Acode",
                description = "Code editing, Project viewing",
                capabilities = listOf("editor", "text"),
                preferredUses = "Manual code inspection and fast edits",
                toolType = ToolType.APP,
                packageNames = listOf("com.foxdebug.acodefree", "com.foxdebug.acode"),
                aliases = listOf("acode")
            ),
            Tool(
                policy = AccessPolicy.ALLOW, id = "spck",
                name = "SPCK",
                description = "Code editing",
                capabilities = listOf("editor", "web"),
                preferredUses = "Web development",
                toolType = ToolType.APP,
                packageNames = listOf("io.spck"),
                aliases = listOf("spck", "spck editor")
            ),
            Tool(
                policy = AccessPolicy.ALLOW, id = "code_studio",
                name = "Code Studio",
                description = "Code editing",
                capabilities = listOf("editor", "ide"),
                preferredUses = "Full IDE experience",
                toolType = ToolType.APP,
                packageNames = listOf("com.alif.ide"),
                aliases = listOf("code studio")
            ),
            Tool(
                policy = AccessPolicy.ALLOW, id = "pydroid",
                name = "Pydroid 3",
                description = "Python execution",
                capabilities = listOf("python", "repl"),
                preferredUses = "Running python scripts",
                toolType = ToolType.APP,
                packageNames = listOf("ru.iiec.pydroid3"),
                aliases = listOf("pydroid", "pydroid 3", "python")
            ),
            Tool(
                id = "expo_go",
                name = "Expo Go",
                description = "React Native preview",
                capabilities = listOf("react-native", "preview"),
                preferredUses = "Previewing mobile apps",
                toolType = ToolType.APP,
                packageNames = listOf("host.exp.exponent"),
                aliases = listOf("expo", "expo go")
            ),
            Tool(
                id = "google_ai_studio",
                name = "Google AI Studio",
                description = "AI-assisted application development",
                capabilities = listOf("ai", "generation"),
                preferredUses = "Generating boilerplate and AI logic",
                toolType = ToolType.WEB,
                url = "https://aistudio.google.com/",
                aliases = listOf("ai studio", "google ai studio"),
                installedOrAvailable = true
            )
        )
    }
}
