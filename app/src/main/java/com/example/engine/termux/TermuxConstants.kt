package com.example.engine.termux

object TermuxConstants {
    const val TERMUX_PACKAGE = "com.termux"
    const val RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"
    const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
    const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
    const val ACTION_RESULT_CALLBACK = "com.example.jarvis.TERMUX_RESULT_CALLBACK"

    // Intent extras sent TO Termux
    const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
    const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    const val EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION"
    const val EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
    const val EXTRA_COMMAND_DESCRIPTION = "com.termux.RUN_COMMAND_COMMAND_DESCRIPTION"
    const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

    // Result Bundle extra key in Intent returned FROM Termux
    const val EXTRA_PLUGIN_RESULT_BUNDLE = "result"

    // Keys INSIDE the plugin result Bundle
    const val RESULT_BUNDLE_STDOUT = "stdout"
    const val RESULT_BUNDLE_STDERR = "stderr"
    const val RESULT_BUNDLE_EXIT_CODE = "exitCode"
    const val RESULT_BUNDLE_ERR = "err"
    const val RESULT_BUNDLE_ERR_MSG = "errmsg"
    const val RESULT_BUNDLE_STDOUT_ORIGINAL_LENGTH = "stdout_original_length"
    const val RESULT_BUNDLE_STDERR_ORIGINAL_LENGTH = "stderr_original_length"

    // Minimum required Termux version for result callbacks
    const val MIN_REQUIRED_VERSION = "0.109"
}
