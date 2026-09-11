package com.example.engine.phone

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.session.MediaSessionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.Timer
import java.util.TimerTask

enum class PhoneActionStatus {
    SUCCESS,
    NOT_AVAILABLE,
    UNSUPPORTED,
    FAILED,
    PERMISSION_REQUIRED
}

data class PhoneActionResult(
    val status: PhoneActionStatus,
    val message: String
)

class PhoneController(private val context: Context) {

    companion object {
        private const val TAG = "PhoneController"
        private const val FLASHLIGHT_BROADCAST_ACTION = "com.example.engine.phone.FLASHLIGHT_TOGGLE"
    }

    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private val wifiManager: WifiManager by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

    private val notificationManager: NotificationManagerCompat by lazy {
        NotificationManagerCompat.from(context)
    }

    // Flashlight state tracking
    @Volatile
    private var flashlightOn = false

    // DND state tracking
    @Volatile
    private var dndOn = false

    fun setVolume(level: Int): PhoneActionResult {
        return try {
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val clampedLevel = level.coerceIn(0, 100)
            val streamVolume = (clampedLevel * maxVolume / 100)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, streamVolume, 0)
            PhoneActionResult(PhoneActionStatus.SUCCESS, "Volume set to $clampedLevel%")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set volume", e)
            PhoneActionResult(PhoneActionStatus.FAILED, "Failed to set volume: ${e.message}")
        }
    }

    fun volumeUp(): PhoneActionResult {
        return try {
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val step = maxVolume / 10
            val newVolume = (currentVolume + step).coerceAtMost(maxVolume)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
            val percent = (newVolume * 100 / maxVolume)
            PhoneActionResult(PhoneActionStatus.SUCCESS, "Volume up to $percent%")
        } catch (e: Exception) {
            PhoneActionResult(PhoneActionStatus.FAILED, "Failed to increase volume: ${e.message}")
        }
    }

    fun volumeDown(): PhoneActionResult {
        return try {
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val step = maxVolume / 10
            val newVolume = (currentVolume - step).coerceAtLeast(0)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
            val percent = (newVolume * 100 / maxVolume)
            PhoneActionResult(PhoneActionStatus.SUCCESS, "Volume down to $percent%")
        } catch (e: Exception) {
            PhoneActionResult(PhoneActionStatus.FAILED, "Failed to decrease volume: ${e.message}")
        }
    }

    fun mute(): PhoneActionResult {
        return try {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            PhoneActionResult(PhoneActionStatus.SUCCESS, "Audio muted")
        } catch (e: Exception) {
            PhoneActionResult(PhoneActionStatus.FAILED, "Failed to mute: ${e.message}")
        }
    }

    fun unmute(): PhoneActionResult {
        return try {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            PhoneActionResult(PhoneActionStatus.SUCCESS, "Audio unmuted")
        } catch (e: Exception) {
            PhoneActionResult(PhoneActionStatus.FAILED, "Failed to unmute: ${e.message}")
        }
    }

    fun setBrightness(level: Int): PhoneActionResult {
        return try {
            if (!Settings.System.canWrite(context)) {
                return PhoneActionResult(PhoneActionStatus.PERMISSION_REQUIRED, "Write settings permission required to change brightness")
            }
            val clampedLevel = level.coerceIn(0, 100)
            val systemBrightness = (clampedLevel * 255 / 100)
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                systemBrightness
            )
            PhoneActionResult(PhoneActionStatus.SUCCESS, "Brightness set to $clampedLevel%")
        } catch (e: Exception) {
            PhoneActionResult(PhoneActionStatus.FAILED, "Failed to set brightness: ${e.message}")
        }
    }

    fun toggleWifi(enable: Boolean): PhoneActionResult {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // On Android 10+, we can't directly toggle WiFi programmatically
                // Open WiFi settings instead
                val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                PhoneActionResult(PhoneActionStatus.SUCCESS, "Opened WiFi settings (Android ${Build.VERSION.SDK_INT} requires manual toggle)")
            } else {
                @Suppress("DEPRECATION")
                wifiManager.isWifiEnabled = enable
                val state = if (enable) "enabled" else "disabled"
                PhoneActionResult(PhoneActionStatus.SUCCESS, "WiFi $state")
            }
        } catch (e: Exception) {
            PhoneActionResult(PhoneActionStatus.FAILED, "Failed to toggle WiFi: ${e.message}")
        }
    }

    fun toggleBluetooth(enable: Boolean): PhoneActionResult {
        return try {
            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            val state = if (enable) "enable" else "disable"
            PhoneActionResult(PhoneActionStatus.SUCCESS, "Opened Bluetooth settings to $state")
        } catch (e: Exception) {
            PhoneActionResult(PhoneActionStatus.FAILED, "Failed to toggle Bluetooth: ${e.message}")
        }
    }

    fun toggleFlashlight(on: Boolean): PhoneActionResult {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull()
                ?: return PhoneActionResult(PhoneActionStatus.NOT_AVAILABLE, "No camera available")

            flashlightOn = on
            cameraManager.setTorchMode(cameraId, on)
            val state = if (on) "on" else "off"
            PhoneActionResult(PhoneActionStatus.SUCCESS, "Flashlight $state")
        } catch (e: Exception) {
            PhoneActionResult(PhoneActionStatus.FAILED, "Failed to toggle flashlight: ${e.message}")
        }
    }

    fun sendMediaKeyEvent(keyCode: Int): PhoneActionResult {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val event = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode)
            audioManager.dispatchMediaKeyEvent(event)
            val upEvent = android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode)
            audioManager.dispatchMediaKeyEvent(upEvent)
            PhoneActionResult(PhoneActionStatus.SUCCESS, "Media key event dispatched")
        } catch (e: Exception) {
            PhoneActionResult(PhoneActionStatus.FAILED, "Failed to send media key: ${e.message}")
        }
    }

    fun playMedia(): PhoneActionResult {
        return sendMediaKeyEvent(android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
    }

    fun pauseMedia(): PhoneActionResult {
        return sendMediaKeyEvent(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE)
    }

    fun nextTrack(): PhoneActionResult {
        return sendMediaKeyEvent(android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
    }

    fun prevTrack(): PhoneActionResult {
        return sendMediaKeyEvent(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    }

    fun setAlarm(hour: Int, minute: Int, label: String?): PhoneActionResult {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, label ?: "JARVIS Alarm")
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            val timeStr = String.format("%02d:%02d", hour, minute)
            PhoneActionResult(PhoneActionStatus.SUCCESS, if (label != null) "Alarm set for $timeStr ($label)" else "Alarm set for $timeStr")
        } catch (e: Exception) {
            PhoneActionResult(PhoneActionStatus.FAILED, "Failed to set alarm: ${e.message}")
        }
    }

    fun setTimer(durationSeconds: Int, label: String?): PhoneActionResult {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, durationSeconds)
                putExtra(AlarmClock.EXTRA_MESSAGE, label ?: "JARVIS Timer")
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            val mins = durationSeconds / 60
            val secs = durationSeconds % 60
            val timeStr = when {
                mins > 0 && secs > 0 -> "${mins}m ${secs}s"
                mins > 0 -> "${mins}m"
                else -> "${secs}s"
            }
            PhoneActionResult(PhoneActionStatus.SUCCESS, "Timer set for $timeStr")
        } catch (e: Exception) {
            PhoneActionResult(PhoneActionStatus.FAILED, "Failed to set timer: ${e.message}")
        }
    }

    fun takeScreenshot(): PhoneActionResult {
        return try {
            // Request screenshot via shell command if running in an environment that supports it
            // On a real device, we'd need MediaProjection API; for now, open the camera app
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            PhoneActionResult(PhoneActionStatus.SUCCESS, "Opened camera for screenshot")
        } catch (e: Exception) {
            PhoneActionResult(PhoneActionStatus.FAILED, "Failed to take screenshot: ${e.message}")
        }
    }

    fun openUrl(url: String): PhoneActionResult {
        return try {
            val formattedUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) {
                "https://$url"
            } else {
                url
            }
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(formattedUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            PhoneActionResult(PhoneActionStatus.SUCCESS, "Opened $formattedUrl")
        } catch (e: Exception) {
            PhoneActionResult(PhoneActionStatus.FAILED, "Failed to open URL: ${e.message}")
        }
    }

    fun searchWeb(query: String): PhoneActionResult {
        return try {
            val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(android.app.SearchManager.QUERY, query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            PhoneActionResult(PhoneActionStatus.SUCCESS, "Searching for: $query")
        } catch (e: Exception) {
            PhoneActionResult(PhoneActionStatus.FAILED, "Failed to search: ${e.message}")
        }
    }

    fun takePhoto(): PhoneActionResult {
        return try {
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            PhoneActionResult(PhoneActionStatus.SUCCESS, "Opened camera to take photo")
        } catch (e: Exception) {
            PhoneActionResult(PhoneActionStatus.FAILED, "Failed to open camera: ${e.message}")
        }
    }

    fun setDoNotDisturb(enable: Boolean): PhoneActionResult {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                dndOn = enable
                val state = if (enable) "on" else "off"
                PhoneActionResult(PhoneActionStatus.SUCCESS, "Opened DND settings. Please grant access to set Do Not Disturb $state.")
            } else {
                PhoneActionResult(PhoneActionStatus.UNSUPPORTED, "Do Not Disturb control requires Android 6.0+")
            }
        } catch (e: Exception) {
            PhoneActionResult(PhoneActionStatus.FAILED, "Failed to toggle DND: ${e.message}")
        }
    }

    fun getCurrentVolume(): Int {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return if (maxVolume > 0) (currentVolume * 100 / maxVolume) else 0
    }

    fun isWifiEnabled(): Boolean {
        return try {
            @Suppress("DEPRECATION")
            wifiManager.isWifiEnabled
        } catch (e: Exception) {
            false
        }
    }

    fun isFlashlightOn(): Boolean = flashlightOn
}
