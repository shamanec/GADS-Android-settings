package com.shamanec.settings.audio

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import androidx.annotation.RequiresPermission

/**
 * AudioCapturer captures PCM audio from the device microphone.
 *
 * Specifications:
 * - Sample Rate: 48kHz (WebRTC standard)
 * - Channels: MONO
 * - Format: 16-bit PCM
 * - Buffer Size: 20ms frames (960 samples @ 48kHz = 1920 bytes)
 * - Audio Source: DEFAULT
 */
class AudioCapturer(
    private val sampleRate: Int = 48000,
    private val channelConfig: Int = AudioFormat.CHANNEL_IN_MONO,
    private val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT
) {
    private var audioRecord: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var isCapturing = false
    private var captureThread: Thread? = null

    companion object {
        private const val TAG = "AudioCapturer"
        private const val FRAME_DURATION_MS = 20 // 20ms frames for Opus
    }

    /**
     * Start capturing audio and invoke callback for each frame
     * @param onAudioData Callback invoked with (pcmData: ByteArray, pts: Long) for each frame
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startCapture(onAudioData: (ByteArray, Long) -> Unit) {
        if (isCapturing) {
            Log.w(TAG, "Audio capture already running")
            return
        }

        // Calculate buffer size for 20ms frames
        // 48000 samples/sec * 2 bytes/sample * 0.020 sec = 1920 bytes
        val bufferSize = sampleRate * 2 * FRAME_DURATION_MS / 1000

        // Get minimum buffer size required by AudioRecord
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val actualBufferSize = maxOf(bufferSize, minBufferSize)

        Log.d(TAG, "Creating AudioRecord: sampleRate=$sampleRate, bufferSize=$actualBufferSize (min=$minBufferSize)")

        try {
            // Create AudioRecord with DEFAULT source (automatically uses external mic if connected)
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.DEFAULT,
                sampleRate,
                channelConfig,
                audioFormat,
                actualBufferSize
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "AudioRecord DENIED: RECORD_AUDIO permission missing for this process - ${e.message}")
            return
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord constructor threw ${e.javaClass.simpleName}: ${e.message}")
            return
        }

        try {
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord state=${audioRecord?.state} (expected STATE_INITIALIZED=${AudioRecord.STATE_INITIALIZED}) - initialization failed without exception")
                audioRecord?.release()
                audioRecord = null
                return
            }

            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(audioRecord!!.audioSessionId)
                noiseSuppressor?.enabled = true
                Log.i(TAG, "NoiseSuppressor enabled (session=${audioRecord!!.audioSessionId})")
            } else {
                Log.w(TAG, "NoiseSuppressor not available on this device")
            }

            audioRecord?.startRecording()
            isCapturing = true

            Log.i(TAG, "Audio capture started successfully")

            // Start capture thread
            captureThread = Thread {
                captureLoop(bufferSize, onAudioData)
            }.apply {
                name = "AudioCaptureThread"
                start()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio capture", e)
            audioRecord?.release()
            audioRecord = null
        }
    }

    /**
     * Main capture loop - reads audio frames and invokes callback
     */
    private fun captureLoop(bufferSize: Int, onAudioData: (ByteArray, Long) -> Unit) {
        val buffer = ByteArray(bufferSize)
        var frameCount = 0

        Log.d(TAG, "Audio capture loop started (buffer size: $bufferSize bytes)")

        while (isCapturing) {
            try {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1

                if (read > 0) {
                    // Calculate presentation timestamp in microseconds
                    val pts = System.nanoTime() / 1000

                    // Invoke callback with PCM data and PTS
                    onAudioData(buffer.copyOf(read), pts)

                    frameCount++
                    if (frameCount % 100 == 0) {
                        Log.d(TAG, "Captured audio frame #$frameCount ($read bytes, PTS=$pts)")
                    }
                } else if (read == AudioRecord.ERROR_INVALID_OPERATION) {
                    Log.e(TAG, "AudioRecord read error: ERROR_INVALID_OPERATION")
                    break
                } else if (read == AudioRecord.ERROR_BAD_VALUE) {
                    Log.e(TAG, "AudioRecord read error: ERROR_BAD_VALUE")
                    break
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in capture loop", e)
                break
            }
        }

        Log.i(TAG, "Audio capture loop stopped (captured $frameCount frames)")
    }

    /**
     * Stop audio capture and release resources
     */
    fun stopCapture() {
        if (!isCapturing) {
            return
        }

        Log.i(TAG, "Stopping audio capture")
        isCapturing = false

        try {
            // Wait for capture thread to finish
            captureThread?.join(1000)
            captureThread = null

            // Stop and release AudioRecord and effects
            audioRecord?.stop()
            noiseSuppressor?.release()
            noiseSuppressor = null
            audioRecord?.release()
            audioRecord = null

            Log.i(TAG, "Audio capture stopped successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio capture", e)
        }
    }
}
