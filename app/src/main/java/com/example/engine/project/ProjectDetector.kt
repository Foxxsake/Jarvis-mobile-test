package com.example.engine.project

enum class ProjectType {
    ANDROID_GRADLE,
    NODE,
    PYTHON,
    UNKNOWN
}

sealed class TestCommandResult {
    data class Detected(val command: String, val reason: String) : TestCommandResult()
    data class NotDetected(val explanation: String) : TestCommandResult()
}

sealed class BuildCommandResult {
    data class Detected(val command: String, val reason: String) : BuildCommandResult()
    data class NotDetected(val explanation: String) : BuildCommandResult()
}

object ProjectDetector {

    val ANDROID_MARKERS = setOf(
        "gradlew", "settings.gradle", "settings.gradle.kts",
        "build.gradle", "build.gradle.kts", "app"
    )

    val NODE_MARKERS = setOf(
        "package.json"
    )

    val PYTHON_MARKERS = setOf(
        "pyproject.toml", "pytest.ini", "requirements.txt", "setup.py"
    )

    fun detectProjectTypes(files: Set<String>): Set<ProjectType> {
        val detected = mutableSetOf<ProjectType>()
        if (files.any { it in ANDROID_MARKERS }) {
            detected.add(ProjectType.ANDROID_GRADLE)
        }
        if (files.any { it in NODE_MARKERS }) {
            detected.add(ProjectType.NODE)
        }
        if (files.any { it in PYTHON_MARKERS }) {
            detected.add(ProjectType.PYTHON)
        }
        if (detected.isEmpty()) {
            detected.add(ProjectType.UNKNOWN)
        }
        return detected
    }

    fun detectPrimaryProjectType(files: Set<String>): ProjectType {
        val types = detectProjectTypes(files)
        return when {
            types.contains(ProjectType.ANDROID_GRADLE) -> ProjectType.ANDROID_GRADLE
            types.contains(ProjectType.NODE) -> ProjectType.NODE
            types.contains(ProjectType.PYTHON) -> ProjectType.PYTHON
            else -> ProjectType.UNKNOWN
        }
    }

    fun detectTestCommand(
        files: Set<String>,
        packageJsonContent: String? = null,
        requirementsOrPyprojectContent: String? = null
    ): TestCommandResult {
        val primary = detectPrimaryProjectType(files)

        return when (primary) {
            ProjectType.ANDROID_GRADLE -> {
                val hasApp = files.contains("app") || files.contains("app/")
                val hasGradlew = files.contains("gradlew") || files.contains("./gradlew")
                val cmd = when {
                    hasGradlew && hasApp -> "./gradlew :app:testDebugUnitTest"
                    hasGradlew -> "./gradlew testDebugUnitTest"
                    hasApp -> "gradle :app:testDebugUnitTest"
                    else -> "gradle test"
                }
                TestCommandResult.Detected(cmd, "Android Gradle project detected")
            }

            ProjectType.NODE -> {
                if (packageJsonContent == null) {
                    TestCommandResult.NotDetected("NO_TEST_COMMAND_DETECTED: package.json exists but script inspection is required.")
                } else if (hasScript(packageJsonContent, "test")) {
                    TestCommandResult.Detected("npm test", "Node project with 'test' script in package.json")
                } else {
                    TestCommandResult.NotDetected("NO_TEST_COMMAND_DETECTED: package.json has no 'test' script.")
                }
            }

            ProjectType.PYTHON -> {
                val hasPytestIni = files.contains("pytest.ini")
                val mentionsPytest = requirementsOrPyprojectContent?.contains("pytest", ignoreCase = true) == true
                if (hasPytestIni || mentionsPytest) {
                    TestCommandResult.Detected("pytest", "Python project configured with pytest")
                } else if (files.contains("setup.py")) {
                    TestCommandResult.Detected("python setup.py test", "Python project with setup.py test runner")
                } else {
                    TestCommandResult.NotDetected("NO_TEST_COMMAND_DETECTED: Python project detected but no pytest or test configuration found.")
                }
            }

            ProjectType.UNKNOWN -> {
                TestCommandResult.NotDetected("NO_TEST_COMMAND_DETECTED: Unknown project type.")
            }
        }
    }

    fun detectBuildCommand(
        files: Set<String>,
        packageJsonContent: String? = null
    ): BuildCommandResult {
        val primary = detectPrimaryProjectType(files)

        return when (primary) {
            ProjectType.ANDROID_GRADLE -> {
                val hasApp = files.contains("app") || files.contains("app/")
                val hasGradlew = files.contains("gradlew") || files.contains("./gradlew")
                val cmd = when {
                    hasGradlew && hasApp -> "./gradlew :app:assembleDebug"
                    hasGradlew -> "./gradlew assembleDebug"
                    hasApp -> "gradle :app:assembleDebug"
                    else -> "gradle assemble"
                }
                BuildCommandResult.Detected(cmd, "Android Gradle project detected")
            }

            ProjectType.NODE -> {
                if (packageJsonContent == null) {
                    BuildCommandResult.NotDetected("BUILD_COMMAND_NOT_DETECTED: package.json exists but script inspection is required.")
                } else if (hasScript(packageJsonContent, "build")) {
                    BuildCommandResult.Detected("npm run build", "Node project with 'build' script in package.json")
                } else {
                    BuildCommandResult.NotDetected("BUILD_COMMAND_NOT_DETECTED: package.json has no 'build' script.")
                }
            }

            ProjectType.PYTHON -> {
                if (files.contains("setup.py")) {
                    BuildCommandResult.Detected("python setup.py build", "Python project with setup.py")
                } else {
                    BuildCommandResult.NotDetected("BUILD_COMMAND_NOT_DETECTED: No build script defined for Python project.")
                }
            }

            ProjectType.UNKNOWN -> {
                BuildCommandResult.NotDetected("BUILD_COMMAND_NOT_DETECTED: Unknown project type.")
            }
        }
    }

    private fun hasScript(packageJson: String, scriptName: String): Boolean {
        // Extract scripts object
        val scriptsIndex = packageJson.indexOf("\"scripts\"")
        if (scriptsIndex == -1) return false
        val braceOpen = packageJson.indexOf('{', scriptsIndex)
        if (braceOpen == -1) return false
        val braceClose = packageJson.indexOf('}', braceOpen)
        if (braceClose == -1) return false
        val scriptsBlock = packageJson.substring(braceOpen, braceClose + 1)
        val scriptPattern = Regex("\"$scriptName\"\\s*:")
        return scriptPattern.containsMatchIn(scriptsBlock)
    }
}
