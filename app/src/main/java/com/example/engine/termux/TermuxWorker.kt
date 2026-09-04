package com.example.engine.termux

interface TermuxWorker {
    fun checkConnectionState(): TermuxConnectionStatus
    suspend fun executeCommand(request: TermuxCommandRequest): TermuxExecutionResult
}
