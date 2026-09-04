package com.example.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

data class ActivityStatusVisual(
    val icon: ImageVector,
    val tint: Color,
    val containerColor: Color,
    val label: String
)

object ActivityStatusVisuals {
    @Composable
    fun getVisual(status: String): ActivityStatusVisual {
        return when (status.uppercase()) {
            "SUCCESS" -> ActivityStatusVisual(
                icon = Icons.Default.CheckCircle,
                tint = MaterialTheme.colorScheme.primary,
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                label = "SUCCESS"
            )
            "REJECTED" -> ActivityStatusVisual(
                icon = Icons.Default.Close,
                tint = MaterialTheme.colorScheme.error,
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                label = "REJECTED"
            )
            "NOT_INSTALLED" -> ActivityStatusVisual(
                icon = Icons.Default.Warning,
                tint = MaterialTheme.colorScheme.tertiary,
                containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f),
                label = "NOT INSTALLED"
            )
            "FAILED" -> ActivityStatusVisual(
                icon = Icons.Default.Warning,
                tint = MaterialTheme.colorScheme.error,
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                label = "FAILED"
            )
            "NOT_IMPLEMENTED" -> ActivityStatusVisual(
                icon = Icons.Default.Info,
                tint = MaterialTheme.colorScheme.secondary,
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
                label = "NOT IMPLEMENTED"
            )
            else -> ActivityStatusVisual(
                icon = Icons.Default.Info,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                label = status
            )
        }
    }
}
