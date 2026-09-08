package com.example.data

/**
 * Granular capability types that can be controlled independently of basic app launch.
 */
enum class CapabilityType {
    // App specific capabilities
    APP_LAUNCH,
    APP_PLAYBACK,
    
    // Termux execution capabilities
    TERMUX_READ_STATUS,
    TERMUX_RUN_READ_ONLY,
    TERMUX_MUTATE_FILES,
    TERMUX_PUBLISH_GIT,
    
    // Communication capabilities
    COMMUNICATION_CALL,
    COMMUNICATION_TEXT,
    COMMUNICATION_EMAIL,
    
    // System capabilities
    DEVICE_SETTINGS,
    APP_INSPECTION
}

/**
 * Represents granular policy for an explicit capability on a target tool/package.
 * Backwards compatible with standard [AppPolicy].
 */
data class CapabilityPermission(
    val targetId: String,
    val capability: CapabilityType,
    val policy: AccessPolicy = AccessPolicy.ASK_EACH_TIME
)
