package com.gads.settings.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
    private var mpGranted = false

    companion object {
        const val EXTRA_MP_RESULT_CODE = "mp_result_code"
        const val EXTRA_MP_DATA = "mp_data"
        private const val RESULT_CODE_UNSET = Int.MIN_VALUE

        private const val TAG = "AudioService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "gads_audio"

        // WebRTCScreenCaptureService registers this callback to receive PCM frames when
        // audioInputType == "internal" (GetStream/DataChannel path). Avoids creating a second
        // InternalAudioCapturer on the same MediaProjection token which would fail with
        // "could not register audio policy".
        @Volatile
        var onPcmFrame: ((ByteArray) -> Unit)? = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_MP_RESULT_CODE, RESULT_CODE_UNSET) ?: RESULT_CODE_UNSET
        val mpData = intent?.let { getProjectionData(it) }
        if (resultCode != RESULT_CODE_UNSET && mpData != null) {
            handleProjectionPayload(resultCode, mpData)
            return START_STICKY
        }

        ensureForeground()

        if (audioWebSocketSender != null) {
            // Service re-delivered by START_STICKY or idempotent re-invocation — keep running.
            return START_STICKY
        }

        val audioInputType = intent?.getStringExtra("audio_input_type") ?: "internal"
        audioWebSocketSender = AudioWebSocketSender().also { it.start() }

        if (audioInputType == "internal" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startInternalCapture()
        } else {
            startMicrophoneCapture()
        }

        Log.i(TAG, "Audio service started (input: $audioInputType, port 1992)")
        return START_STICKY
    }

    private fun handleProjectionPayload(resultCode: Int, data: Intent) {
        if (mpGranted) {
            Log.i(TAG, "MediaProjection payload received but token already granted — ignoring")
            return
        }
        // Android 14+: MediaProjection.getMediaProjection() requires an active foreground
        // service of type mediaProjection. startForeground() MUST run first (within the ~5s
        // FGS grace window) so the subsequent getMediaProjection() call is accepted.
        mpGranted = true
        ensureForeground()

        try {
            val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = mpManager.getMediaProjection(resultCode, data)
            MediaProjectionHolder.set(mp)
            Log.i(TAG, "MediaProjection obtained within AudioService foreground")
        } catch (e: Exception) {
            // Keep the service alive for microphone path even if the token could not be consumed.
            Log.w(TAG, "Failed to obtain MediaProjection from forwarded payload", e)
        }
    }

    private fun ensureForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (mpGranted) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), types)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun getProjectionData(intent: Intent): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_MP_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_MP_DATA)
        }
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
                    // Run recovery on a background thread: stopCapture() joins the capture thread
                    // (up to 1s) and Thread.sleep(150) would both block the main thread if called here.
                    Thread {
                        internalAudioCapturer?.stopCapture()
                        internalAudioCapturer = null
                        // The kernel audio policy slot takes 1-1.2s to free after AudioRecord.release().
                        // A 600ms wait here ensures the first retry attempt starts well inside the
                        // safe window, giving the full retry loop (150+300+600ms) room to succeed.
                        Thread.sleep(600)
                        startInternalCapture()
                    }.start()
                }
            }, Handler(Looper.getMainLooper()))

            internalAudioCapturer = InternalAudioCapturer(mp).also { capturer ->
                capturer.startCapture { pcmData, pts ->
                    audioWebSocketSender?.sendAudioFrame(pcmData, pts)
                    onPcmFrame?.invoke(pcmData)
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
