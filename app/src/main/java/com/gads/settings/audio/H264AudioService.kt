package com.gads.settings.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * Foreground service that runs AudioWebSocketSender + AudioCapturer within the
 * com.gads.settings app context, where RECORD_AUDIO permission is properly granted
 * and Android audio policy allows microphone access.
 *
 * This service is started by the GADS provider via:
 *   adb shell am start-foreground-service -n com.gads.settings/.audio.H264AudioService
 *
 * It replaces the previous approach of starting AudioRecord inside app_process (H264Server),
 * which fails because AudioFlinger rejects microphone access for shell uid processes.
 */
class H264AudioService : Service() {
    private var audioWebSocketSender: AudioWebSocketSender? = null
    private var audioCapturer: AudioCapturer? = null

    companion object {
        private const val TAG = "H264AudioService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "gads_h264_audio"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())

        audioWebSocketSender = AudioWebSocketSender().also { it.start() }
        audioCapturer = AudioCapturer().also { capturer ->
            capturer.startCapture { pcmData, pts ->
                audioWebSocketSender?.sendAudioFrame(pcmData, pts)
            }
        }
        Log.i(TAG, "H264 audio service started (port 1992)")
        return START_STICKY
    }

    override fun onDestroy() {
        audioCapturer?.stopCapture()
        audioCapturer = null
        audioWebSocketSender?.stop()
        audioWebSocketSender = null
        Log.i(TAG, "H264 audio service stopped")
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
