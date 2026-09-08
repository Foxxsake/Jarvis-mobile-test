package com.example.engine.voice.handsfree

/**
 * Real operational states of the Hands-free Foreground Service.
 */
enum class HandsFreeState {
    OFF,
    STARTING,
    ACTIVE,
    STOPPING,
    ERROR,
    PERMISSION_REQUIRED
}
