package com.example.engine.ai

interface AIProvider {
    suspend fun generateResponse(prompt: String): String
}

class AIProviderRouter {
    // Placeholder for future AI routing
    fun route(prompt: String): AIProvider? {
        return null 
    }
}
