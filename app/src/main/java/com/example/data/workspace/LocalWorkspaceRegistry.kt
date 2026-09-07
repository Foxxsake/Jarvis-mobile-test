package com.example.data.workspace

import android.content.Context
import android.content.SharedPreferences

class LocalWorkspaceRegistry(context: Context? = null) : WorkspaceRegistry {

    private val prefs: SharedPreferences? = context?.getSharedPreferences("jarvis_workspaces", Context.MODE_PRIVATE)
    private val memoryWorkspaces = mutableListOf<Workspace>()
    private var activeWorkspaceId: String? = null

    init {
        loadFromPrefs()
    }

    private fun loadFromPrefs() {
        if (prefs == null) return
        val activeId = prefs.getString("active_workspace_id", null)
        activeWorkspaceId = activeId
        
        val count = prefs.getInt("workspace_count", 0)
        for (i in 0 until count) {
            val id = prefs.getString("ws_${i}_id", null) ?: continue
            val name = prefs.getString("ws_${i}_name", "Project $i") ?: "Project"
            val path = prefs.getString("ws_${i}_path", "/data/data/com.termux/files/home") ?: "/data/data/com.termux/files/home"
            val remote = prefs.getString("ws_${i}_remote", null)
            val tool = prefs.getString("ws_${i}_tool", "Termux") ?: "Termux"
            val lastUsed = prefs.getLong("ws_${i}_last_used", System.currentTimeMillis())
            memoryWorkspaces.add(Workspace(id, name, path, remote, tool, lastUsed))
        }
    }

    private fun saveToPrefs() {
        if (prefs == null) return
        val editor = prefs.edit()
        editor.putString("active_workspace_id", activeWorkspaceId)
        editor.putInt("workspace_count", memoryWorkspaces.size)
        memoryWorkspaces.forEachIndexed { i, ws ->
            editor.putString("ws_${i}_id", ws.id)
            editor.putString("ws_${i}_name", ws.displayName)
            editor.putString("ws_${i}_path", ws.localPath)
            editor.putString("ws_${i}_remote", ws.optionalGitRemote)
            editor.putString("ws_${i}_tool", ws.preferredTool)
            editor.putLong("ws_${i}_last_used", ws.lastUsed)
        }
        editor.apply()
    }

    override fun getActiveWorkspace(): Workspace? {
        val currentId = activeWorkspaceId ?: return memoryWorkspaces.firstOrNull()
        return memoryWorkspaces.find { it.id == currentId } ?: memoryWorkspaces.firstOrNull()
    }

    override fun setActiveWorkspace(workspace: Workspace) {
        val existingIndex = memoryWorkspaces.indexOfFirst { it.id == workspace.id }
        if (existingIndex >= 0) {
            memoryWorkspaces[existingIndex] = workspace
        } else {
            memoryWorkspaces.add(workspace)
        }
        activeWorkspaceId = workspace.id
        saveToPrefs()
    }

    override fun getAllWorkspaces(): List<Workspace> {
        return memoryWorkspaces.toList()
    }

    override fun addWorkspace(workspace: Workspace) {
        memoryWorkspaces.removeAll { it.id == workspace.id }
        memoryWorkspaces.add(workspace)
        if (activeWorkspaceId == null) {
            activeWorkspaceId = workspace.id
        }
        saveToPrefs()
    }

    override fun validateWorkspace(workspace: Workspace): WorkspaceValidationResult {
        return validatePath(workspace.localPath)
    }

    override fun validatePath(path: String): WorkspaceValidationResult {
        if (path.isBlank()) {
            return WorkspaceValidationResult(
                status = WorkspaceValidationStatus.PATH_EMPTY,
                message = "Workspace path is empty.",
                isUsable = false
            )
        }
        
        if (!path.startsWith("/data/data/com.termux/")) {
            val file = java.io.File(path)
            if (!file.exists()) {
                return WorkspaceValidationResult(
                    status = WorkspaceValidationStatus.DIRECTORY_DOES_NOT_EXIST,
                    message = "Directory does not exist.",
                    isUsable = false
                )
            }
        }
        
        // This class only manages metadata. Do not inspect private Termux storage using java.io.File.
        return WorkspaceValidationResult(
            status = WorkspaceValidationStatus.VALID,
            message = "Path format accepted (inspection occurs via Termux commands).",
            isUsable = true
        )
    }
}

