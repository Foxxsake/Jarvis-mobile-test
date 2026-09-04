package com.example.data.workspace

data class Workspace(
    val id: String,
    val displayName: String,
    val localPath: String,
    val optionalGitRemote: String? = null,
    val preferredTool: String = "Termux",
    val lastUsed: Long = System.currentTimeMillis()
)

interface WorkspaceRegistry {
    fun getActiveWorkspace(): Workspace?
    fun setActiveWorkspace(workspace: Workspace)
    fun getAllWorkspaces(): List<Workspace>
    fun addWorkspace(workspace: Workspace)
}
