package com.gads.settings.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.ServiceCompat

/**
 * Foreground service that runs AudioWebSocketSender + AudioCapturer within the
 * com.gads.settings app context, where RECORD_AUDIO permission is properly granted
 * and Android audio policy allows microphone access.
 *
 * Started by the GADS provider via:
 *   adb shell am start-foreground-service -n com.gads.settings/.audio.AudioService
 *       --es audio_input_type internal|microphone
 *
 * Audio input types:
 * - "internal" (default): AudioPlaybackCapture — captures what the device is playing (Android 10+).
 *   Waits for MediaProjection via MediaProjectionHolder (set when screen capture session starts).
 * - "microphone": AudioRecord with DEFAULT source — captures via microphone.
 */
class AudioService : Service() {
    private var audioWebSocketSender: AudioWebSocketSender? = null
    private var audioCapturer: AudioCapturer? = null
    private var internalAudioCapturer: InternalAudioCapturer? = null

    companion object {
        private const val TAG = "AudioService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "gads_audio"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val audioInputType = intent?.getStringExtra("audio_input_type") ?: "internal"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION requires android:project_media appops to be granted.
            // Only request it for internal audio (AudioPlaybackCapture uses MediaProjection).
            // For microphone audio, FOREGROUND_SERVICE_TYPE_MICROPHONE is sufficient.
            val fgsType = if (audioInputType == "internal") {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), fgsType)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        audioWebSocketSender = AudioWebSocketSender().also { it.start() }

        if (audioInputType == "internal" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startInternalCapture()
        } else {
            startMicrophoneCapture()
        }

        Log.i(TAG, "Audio service started (input: $audioInputType, port 1992)")
        return START_STICKY
    }

    private fun startMicrophoneCapture() {
        audioCapturer = AudioCapturer().also { capturer ->
            capturer.startCapture { pcmData, pts ->
                audioWebSocketSender?.sendAudioFrame(pcmData, pts)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startInternalCapture() {
        MediaProjectionHolder.getOrListen { mp ->
            // Register callback to recover if Android 15 stops the projection (e.g. lock screen)
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "MediaProjection stopped - stopping internal audio capture, awaiting new token")
                    internalAudioCapturer?.stopCapture()
                    internalAudioCapturer = null
                    startInternalCapture()
                }
            }, null)

            internalAudioCapturer = InternalAudioCapturer(mp).also { capturer ->
                capturer.startCapture { pcmData, pts ->
                    audioWebSocketSender?.sendAudioFrame(pcmData, pts)
                }
            }
            Log.i(TAG, "Internal audio capture started via MediaProjectionHolder")
        }
    }

    override fun onDestroy() {
        audioCapturer?.stopCapture()
        audioCapturer = null
        internalAudioCapturer?.stopCapture()
        internalAudioCapturer = null
        audioWebSocketSender?.stop()
        audioWebSocketSender = null
        Log.i(TAG, "Audio service stopped")
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "GADS Audio", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("GADS Audio Capture")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
    }
}
