package com.example.engine.project

import com.example.data.workspace.Workspace
import com.example.engine.termux.TermuxCommandRequest
import com.example.engine.termux.TermuxRiskLevel
import com.example.engine.termux.TermuxWorker

enum class WorkspaceInspectionStatus {
    UNVERIFIED, VALID, PATH_NOT_FOUND, NOT_ACCESSIBLE, NOT_GIT_REPOSITORY, VALID_NON_GIT_PROJECT, TERMUX_UNAVAILABLE, FAILED
}

data class WorkspaceInspectionResult(
    val status: WorkspaceInspectionStatus,
    val isUsable: Boolean,
    val topLevelFiles: Set<String> = emptySet(),
    val projectTypes: Set<ProjectType> = emptySet(),
    val branch: String? = null,
    val gitRemote: String? = null,
    val packageJsonContent: String? = null,
    val requirementsOrPyprojectContent: String? = null,
    val message: String? = null
)

class TermuxWorkspaceInspector(
    private val termuxWorker: TermuxWorker
) {
    suspend fun inspectWorkspace(workspace: Workspace): WorkspaceInspectionResult {
        // Run ls -1A
        val req = TermuxCommandRequest(
            executablePath = "/data/data/com.termux/files/usr/bin/ls",
            arguments = listOf("-1A"),
            workingDirectory = workspace.localPath,
            description = "Inspect workspace files",
            riskLevel = TermuxRiskLevel.READ_ONLY
        )
        val res = termuxWorker.executeCommand(req)
        if (res.status != com.example.engine.termux.TermuxExecutionStatus.SUCCESS) {
            val status = if (res.message.contains("No such file or directory", ignoreCase = true)) {
                WorkspaceInspectionStatus.PATH_NOT_FOUND
            } else if (res.status == com.example.engine.termux.TermuxExecutionStatus.TERMUX_NOT_INSTALLED) {
                WorkspaceInspectionStatus.TERMUX_UNAVAILABLE
            } else {
                WorkspaceInspectionStatus.NOT_ACCESSIBLE
            }
            return WorkspaceInspectionResult(
                status = status,
                isUsable = false,
                message = "Workspace not accessible: ${res.message.ifEmpty { res.stdout }}"
            )
        }
        
        val topLevelFiles = res.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        
        var packageJsonContent: String? = null
        if (topLevelFiles.contains("package.json")) {
            val catRes = termuxWorker.executeCommand(
                TermuxCommandRequest(
                    executablePath = "/data/data/com.termux/files/usr/bin/cat",
                    arguments = listOf("package.json"),
                    workingDirectory = workspace.localPath,
                    description = "Read package.json",
                    riskLevel = TermuxRiskLevel.READ_ONLY
                )
            )
            if (catRes.status == com.example.engine.termux.TermuxExecutionStatus.SUCCESS) packageJsonContent = catRes.stdout
        }
        
        var pyContent: String? = null
        if (topLevelFiles.contains("pyproject.toml") || topLevelFiles.contains("requirements.txt")) {
            val fileToCat = if (topLevelFiles.contains("pyproject.toml")) "pyproject.toml" else "requirements.txt"
            val catRes = termuxWorker.executeCommand(
                TermuxCommandRequest(
                    executablePath = "/data/data/com.termux/files/usr/bin/cat",
                    arguments = listOf(fileToCat),
                    workingDirectory = workspace.localPath,
                    description = "Read Python config",
                    riskLevel = TermuxRiskLevel.READ_ONLY
                )
            )
            if (catRes.status == com.example.engine.termux.TermuxExecutionStatus.SUCCESS) pyContent = catRes.stdout
        }
        
        val isGitRepo = topLevelFiles.contains(".git")
        var branch: String? = null
        var gitRemote: String? = null
        
        if (isGitRepo) {
            val branchRes = termuxWorker.executeCommand(
                TermuxCommandRequest(
                    executablePath = "/data/data/com.termux/files/usr/bin/git",
                    arguments = listOf("branch", "--show-current"),
                    workingDirectory = workspace.localPath,
                    description = "Get current branch",
                    riskLevel = TermuxRiskLevel.READ_ONLY
                )
            )
            if (branchRes.status == com.example.engine.termux.TermuxExecutionStatus.SUCCESS) {
                branch = branchRes.stdout.trim().ifBlank { null }
            }
            val remoteRes = termuxWorker.executeCommand(
                TermuxCommandRequest(
                    executablePath = "/data/data/com.termux/files/usr/bin/git",
                    arguments = listOf("remote", "-v"),
                    workingDirectory = workspace.localPath,
                    description = "Get git remote",
                    riskLevel = TermuxRiskLevel.READ_ONLY
                )
            )
            if (remoteRes.status == com.example.engine.termux.TermuxExecutionStatus.SUCCESS) {
                gitRemote = remoteRes.stdout.trim().ifBlank { null }
            }
        }
        
        val projectTypes = ProjectDetector.detectProjectTypes(topLevelFiles)

        return WorkspaceInspectionResult(
            status = if (isGitRepo) WorkspaceInspectionStatus.VALID else WorkspaceInspectionStatus.VALID_NON_GIT_PROJECT,
            isUsable = true,
            topLevelFiles = topLevelFiles,
            projectTypes = projectTypes,
            branch = branch,
            gitRemote = gitRemote,
            packageJsonContent = packageJsonContent,
            requirementsOrPyprojectContent = pyContent
        )
    }
}
