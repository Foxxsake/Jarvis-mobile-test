package com.example.engine.voice.handsfree

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.engine.JarvisRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Foreground Service for keeping an explicitly user-enabled hands-free voice session active.
 * Complies with Android 14+ (API 34) microphone foreground service requirements:
 * - Declares foregroundServiceType="microphone" in manifest
 * - Requires explicit RECORD_AUDIO and FOREGROUND_SERVICE_MICROPHONE permissions
 * - Shows a persistent, user-visible notification with a clear "Stop Listening" action
 * - Never starts silently or on device boot
 * - Routes all spoken commands through the unified JarvisRuntime command execution pipeline
 */
class HandsFreeVoiceService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        const val CHANNEL_ID = "jarvis_hands_free_channel"
        const val NOTIFICATION_ID = 2026
        const val ACTION_START = "com.example.action.START_HANDS_FREE"
        const val ACTION_STOP = "com.example.action.STOP_HANDS_FREE"

        private val _serviceState = MutableStateFlow<HandsFreeState>(HandsFreeState.OFF)
        val serviceState: StateFlow<HandsFreeState> = _serviceState.asStateFlow()

        fun startService(context: Context) {
            val hasMicPerm = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasMicPerm) {
                _serviceState.value = HandsFreeState.PERMISSION_REQUIRED
                return
            }

            _serviceState.value = HandsFreeState.STARTING
            val intent = Intent(context, HandsFreeVoiceService::class.java).apply {
                action = ACTION_START
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                _serviceState.value = HandsFreeState.ERROR
                try {
                    val runtime = JarvisRuntime.getInstance(context.applicationContext)
                    CoroutineScope(Dispatchers.Main).launch {
                        runtime.settingsManager.setHandsFree(false)
                    }
                } catch (_: Exception) {}
            }
        }

        fun stopService(context: Context) {
            _serviceState.value = HandsFreeState.STOPPING
            val intent = Intent(context, HandsFreeVoiceService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                _serviceState.value = HandsFreeState.OFF
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                handleStop()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                handleStart()
            }
        }
        return START_NOT_STICKY
    }

    private fun handleStart() {
        val hasMicPerm = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasMicPerm) {
            _serviceState.value = HandsFreeState.PERMISSION_REQUIRED
            stopSelf()
            return
        }

        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            _serviceState.value = HandsFreeState.ACTIVE

            // Connect to shared JarvisRuntime and start continuous listening
            val runtime = JarvisRuntime.getInstance(applicationContext)
            runtime.voiceSessionController.setHandsFreeMode(true)
            runtime.voiceSessionController.startListening()

        } catch (e: Exception) {
            _serviceState.value = HandsFreeState.ERROR
            try {
                val runtime = JarvisRuntime.getInstance(applicationContext)
                serviceScope.launch {
                    runtime.settingsManager.setHandsFree(false)
                }
            } catch (_: Exception) {}
            stopSelf()
        }
    }

    private fun handleStop() {
        try {
            val runtime = JarvisRuntime.getInstance(applicationContext)
            runtime.voiceSessionController.setHandsFreeMode(false)
            runtime.voiceSessionController.stopListening()
            runtime.voiceSessionController.stopSpeaking()

            serviceScope.launch {
                runtime.settingsManager.setHandsFree(false)
            }
        } catch (_: Exception) {}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        _serviceState.value = HandsFreeState.OFF
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            val runtime = JarvisRuntime.getInstance(applicationContext)
            runtime.voiceSessionController.setHandsFreeMode(false)
            serviceScope.launch {
                runtime.settingsManager.setHandsFree(false)
            }
        } catch (_: Exception) {}

        if (_serviceState.value != HandsFreeState.PERMISSION_REQUIRED &&
            _serviceState.value != HandsFreeState.ERROR
        ) {
            _serviceState.value = HandsFreeState.OFF
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "JARVIS Hands-free Listening",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps JARVIS active for hands-free voice commands while running"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingLaunch = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, HandsFreeVoiceService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("JARVIS Hands-Free Active")
            .setContentText("JARVIS hands-free listening active")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(pendingLaunch)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop Listening",
                pendingStop
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
