package com.gads.settings.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * Captures internal device audio (what the device is playing) using AudioPlaybackCapture API.
 *
 * Requires Android 10+ (API 29). Captures audio from apps using USAGE_MEDIA, USAGE_GAME,
 * and USAGE_UNKNOWN. Apps that declare allowAudioPlaybackCapture=false in their manifest
 * will not be captured (e.g. DRM-protected apps).
 *
 * Specifications match AudioCapturer for pipeline compatibility:
 * - Sample Rate: 48kHz
 * - Channels: MONO
 * - Format: 16-bit PCM
 * - Buffer Size: 20ms frames (960 samples = 1920 bytes)
 */
@RequiresApi(Build.VERSION_CODES.Q)
class InternalAudioCapturer(
    private val mediaProjection: MediaProjection,
    private val sampleRate: Int = 48000,
    private val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT
) {
    companion object {
        private const val TAG = "InternalAudioCapturer"
        private const val FRAME_DURATION_MS = 20
    }

    private var audioRecord: AudioRecord? = null
    private var isCapturing = false
    private var captureThread: Thread? = null

    fun startCapture(onAudioData: (ByteArray, Long) -> Unit) {
        if (isCapturing) {
            Log.w(TAG, "Internal audio capture already running")
            return
        }

        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val bufferSize = sampleRate * 2 * FRAME_DURATION_MS / 1000
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, audioFormat)
        val actualBufferSize = maxOf(bufferSize, minBufferSize)

        audioRecord = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(config)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(audioFormat)
                    .build()
            )
            .setBufferSizeInBytes(actualBufferSize)
            .build()

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord for internal capture failed to initialize")
            audioRecord?.release()
            audioRecord = null
            return
        }

        audioRecord?.startRecording()
        isCapturing = true

        captureThread = Thread {
            val buffer = ByteArray(bufferSize)
            var frameCount = 0

            Log.d(TAG, "Internal audio capture loop started (buffer: $bufferSize bytes)")

            while (isCapturing) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (read > 0) {
                    onAudioData(buffer.copyOf(read), System.nanoTime() / 1000)
                    frameCount++
                    if (frameCount % 100 == 0) Log.d(TAG, "Captured internal audio frame #$frameCount ($read bytes)")
                } else if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_BAD_VALUE) {
                    Log.e(TAG, "AudioRecord read error: $read")
                    break
                }
            }

            Log.i(TAG, "Internal audio capture loop stopped ($frameCount frames)")
        }.apply {
            name = "InternalAudioCaptureThread"
            start()
        }

        Log.i(TAG, "Internal audio capture started (AudioPlaybackCapture, sampleRate=$sampleRate)")
    }

    fun stopCapture() {
        if (!isCapturing) return
        isCapturing = false
        captureThread?.join(1000)
        captureThread = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        Log.i(TAG, "Internal audio capture stopped")
    }
}
