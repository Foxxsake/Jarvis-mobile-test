package com.example.data.workspace

import java.io.File

enum class WorkspaceValidationStatus {
    VALID,
    VALID_NOT_GIT,
    DIRECTORY_DOES_NOT_EXIST,
    NOT_A_DIRECTORY,
    NOT_A_GIT_REPO,
    PATH_EMPTY
}

data class WorkspaceValidationResult(
    val status: WorkspaceValidationStatus,
    val message: String,
    val isUsable: Boolean
)

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
    fun validateWorkspace(workspace: Workspace): WorkspaceValidationResult
    fun validatePath(path: String): WorkspaceValidationResult
}
