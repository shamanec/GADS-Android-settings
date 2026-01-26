package com.gads.settings.server

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import com.gads.settings.server.utils.EncoderConfig
import java.io.ByteArrayOutputStream

/**
 * Reusable H.264 encoder that handles:
 * - Encoder selection with hardware/software fallback
 * - Output buffer processing and frame extraction
 * - SPS/PPS management and caching
 * - Resolution fallback on encoder failures
 * - Error recovery with alternative encoders
 *
 * This class is used by both H264Server (app_process mode) and H264ScreenCaptureService (Activity mode).
 */
class H264Encoder(
    private val frameCallback: FrameCallback
) {
    private val TAG = "GADS H264Encoder"

    // MediaCodec components
    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null

    // Encoder state
    @Volatile
    private var isEncoding = false
    private var preferredEncoderName: String? = null
    private var encoderAttempts = 0
    private val failedEncoders = mutableSetOf<String>()
    private var firstFrameSent = false
    private var maxSize = 0 // Track current max resolution for fallback, 0 means no limit

    // SPS/PPS cache
    private var cachedSPS: ByteArray? = null
    private var cachedPPS: ByteArray? = null

    // Output thread
    private var encoderOutputThread: Thread? = null

    // Current encoding parameters
    private var currentWidth = 0
    private var currentHeight = 0
    private var currentBitrate = 0
    private var currentFPS = 0
    private var currentIFrameInterval = 0

    companion object {
        // Resolution fallback values for encoder failures (in descending order)
        private val MAX_SIZE_FALLBACK = intArrayOf(2560, 1920, 1600, 1280, 1024, 800)
    }

    /**
     * Callback interface for encoder events.
     */
    interface FrameCallback {
        /**
         * Called when an encoded frame is ready to be broadcast.
         * @param data Frame data with 8-byte PTS timestamp prepended
         */
        fun onFrameEncoded(data: ByteArray, isKeyFrame: Boolean, pts: Long)

        /**
         * Called when encoder needs to be restarted (e.g., after resolution fallback).
         */
        fun onEncoderRestartNeeded()
    }

    /**
     * Configure and start the encoder.
     * @param width Target video width (must be multiple of 16)
     * @param height Target video height (must be multiple of 16)
     * @param bitrate Bitrate in bits per second
     * @param fps Target frames per second
     * @param iFrameInterval I-frame interval in seconds
     * @return Input Surface for the encoder, or null on failure
     */
    fun start(width: Int, height: Int, bitrate: Int = currentBitrate, fps: Int = currentFPS, iFrameInterval: Int = currentIFrameInterval): Surface? {
        currentWidth = width
        currentHeight = height
        currentBitrate = bitrate
        currentFPS = fps
        currentIFrameInterval = iFrameInterval

        // Apply maxSize limit if set (for resolution fallback)
        var targetWidth = width
        var targetHeight = height
        if (maxSize > 0) {
            val currentMaxDimension = maxOf(targetWidth, targetHeight)
            if (currentMaxDimension > maxSize) {
                val scale = maxSize.toFloat() / currentMaxDimension
                targetWidth = ((targetWidth * scale).toInt() / 16) * 16
                targetHeight = ((targetHeight * scale).toInt() / 16) * 16
                Log.d(TAG, "Applied maxSize limit $maxSize: ${targetWidth}x${targetHeight}")
            }
        }

        currentWidth = targetWidth
        currentHeight = targetHeight

        Log.d(TAG, "Starting encoder: ${currentWidth}x${currentHeight} @ ${currentFPS}fps, bitrate: $currentBitrate")

        try {
            // Create H.264 encoder
            mediaCodec = selectEncoder()

            // Configure MediaFormat for H.264 video encoding
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, currentWidth, currentHeight)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            format.setInteger(MediaFormat.KEY_BIT_RATE, currentBitrate)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, currentFPS)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, currentIFrameInterval)

            // Color range for better color reproduction (API 24+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            }

            // KEY_REPEAT_PREVIOUS_FRAME_AFTER causes crashes on some devices (Xiaomi/Android 15)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L) // 100ms
            }

            // Set actual FPS control (API 29+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                format.setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, currentFPS.toFloat())
                Log.d(TAG, "Set max FPS to encoder: $currentFPS")
            }

            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = mediaCodec?.createInputSurface()
            mediaCodec?.start()

            // Start encoder output thread
            startEncoderOutputThread()

            Log.d(TAG, "Encoder started successfully")
            return inputSurface

        } catch (e: Exception) {
            Log.e(TAG, "Error starting encoder", e)
            release()
            return null
        }
    }

    /**
     * Stop and release the encoder.
     */
    fun release() {
        try {
            // Signal encoder output thread to stop
            isEncoding = false

            // Wait for encoder output thread to fully stop
            encoderOutputThread?.let { thread ->
                if (thread.isAlive) {
                    Log.d(TAG, "Waiting for encoder output thread to stop...")
                    thread.interrupt()
                    try {
                        thread.join(2000) // Wait up to 2 seconds
                        if (thread.isAlive) {
                            Log.w(TAG, "Encoder output thread did not stop in time")
                        } else {
                            Log.d(TAG, "Encoder output thread stopped successfully")
                        }
                    } catch (e: InterruptedException) {
                        Log.w(TAG, "Interrupted while waiting for encoder thread to stop")
                    }
                }
            }
            encoderOutputThread = null

            // Stop and release MediaCodec
            try {
                mediaCodec?.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping MediaCodec: ${e.message}")
            }

            try {
                mediaCodec?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing MediaCodec: ${e.message}")
            }
            mediaCodec = null

            inputSurface?.release()
            inputSurface = null

            firstFrameSent = false

            Log.d(TAG, "Encoder released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing encoder", e)
        }
    }

    /**
     * Get cached SPS/PPS config frames.
     */
    fun getCachedConfigFrames(): Pair<ByteArray?, ByteArray?> {
        return Pair(cachedSPS, cachedPPS)
    }

    /**
     * Get current encoder dimensions.
     */
    fun getDimensions(): Pair<Int, Int> {
        return Pair(currentWidth, currentHeight)
    }

    /**
     * Check if encoder is currently encoding.
     */
    fun isEncoding(): Boolean {
        return isEncoding
    }

    /**
     * Select the best available H.264 encoder with fallback logic.
     */
    private fun selectEncoder(): MediaCodec {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val isProblematicDevice = manufacturer.contains("xiaomi") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM

        // If we've already found a working encoder, use it
        if (preferredEncoderName != null && !failedEncoders.contains(preferredEncoderName)) {
            try {
                Log.d(TAG, "Using previously successful encoder: $preferredEncoderName")
                return MediaCodec.createByCodecName(preferredEncoderName!!)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create preferred encoder $preferredEncoderName: ${e.message}")
                failedEncoders.add(preferredEncoderName!!)
                preferredEncoderName = null
            }
        }

        // List of encoders to try in order of preference
        val encodersToTry = if (isProblematicDevice) {
            Log.d(TAG, "Detected problematic device (Xiaomi Android 15+), preferring software encoder")
            listOf("c2.android.avc.encoder", "OMX.google.h264.encoder", null)
        } else {
            listOf(null, "c2.android.avc.encoder", "OMX.google.h264.encoder")
        }

        for (encoderName in encodersToTry) {
            val codecToTry = if (encoderName == null) {
                try {
                    val defaultCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                    if (failedEncoders.contains(defaultCodec.name)) {
                        Log.d(TAG, "Skipping previously failed encoder: ${defaultCodec.name}")
                        defaultCodec.release()
                        continue
                    }
                    defaultCodec.name
                } catch (e: Exception) {
                    continue
                }
            } else {
                if (failedEncoders.contains(encoderName)) {
                    Log.d(TAG, "Skipping previously failed encoder: $encoderName")
                    continue
                }
                encoderName
            }

            try {
                val codec = MediaCodec.createByCodecName(codecToTry)
                Log.d(TAG, "Successfully created encoder: ${codec.name}")
                preferredEncoderName = codec.name
                return codec
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create encoder $codecToTry: ${e.message}")
                failedEncoders.add(codecToTry)
            }
        }

        throw RuntimeException("No suitable H.264 encoder found (tried ${failedEncoders.size} encoders)")
    }

    /**
     * Start encoder output thread that reads H.264 frames and invokes callback.
     */
    private fun startEncoderOutputThread() {
        isEncoding = true
        encoderOutputThread = Thread {
            // Some devices (Meizu) deadlock if the video encoding thread has no Looper
            android.os.Looper.prepare()

            val bufferInfo = MediaCodec.BufferInfo()
            var consecutiveErrors = 0
            val maxConsecutiveErrors = 5

            Log.d(TAG, "Encoder output thread started")

            while (isEncoding && !Thread.currentThread().isInterrupted) {
                try {
                    // Use timeout to allow thread interruption (1 second)
                    val outputBufferIndex = mediaCodec?.dequeueOutputBuffer(bufferInfo, 1000000) ?: -1

                    when {
                        // MediaCodec signals format changed, extract SPS/PPS
                        outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val newFormat = mediaCodec?.outputFormat
                            val sps = newFormat?.getByteBuffer("csd-0")
                            val pps = newFormat?.getByteBuffer("csd-1")

                            if (sps != null) {
                                cachedSPS = ByteArray(sps.remaining())
                                sps.get(cachedSPS!!)
                                Log.d(TAG, "SPS extracted: ${cachedSPS!!.size} bytes")
                            }

                            if (pps != null) {
                                cachedPPS = ByteArray(pps.remaining())
                                pps.get(cachedPPS!!)
                                Log.d(TAG, "PPS extracted: ${cachedPPS!!.size} bytes")
                            }
                        }

                        // Valid output buffer with encoded frame data
                        outputBufferIndex >= 0 -> {
                            val encodedData = mediaCodec?.getOutputBuffer(outputBufferIndex)

                            // Reset attempt counter on successful frame
                            if (encoderAttempts > 0) {
                                encoderAttempts = 0
                            }

                            if (encodedData != null && bufferInfo.size > 0) {
                                encodedData.position(bufferInfo.offset)
                                encodedData.limit(bufferInfo.offset + bufferInfo.size)

                                val data = ByteArray(bufferInfo.size)
                                encodedData.get(data)

                                val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                                val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                                if (!isConfig) {
                                    firstFrameSent = true
                                    consecutiveErrors = 0
                                }

                                if (data.isNotEmpty()) {
                                    val outputStream = ByteArrayOutputStream()

                                    // Write 8-byte PTS timestamp (big-endian)
                                    val pts = bufferInfo.presentationTimeUs
                                    outputStream.write(byteArrayOf(
                                        (pts shr 56).toByte(),
                                        (pts shr 48).toByte(),
                                        (pts shr 40).toByte(),
                                        (pts shr 32).toByte(),
                                        (pts shr 24).toByte(),
                                        (pts shr 16).toByte(),
                                        (pts shr 8).toByte(),
                                        pts.toByte()
                                    ))

                                    // Prepend SPS/PPS to I-frames
                                    if (isKeyFrame && cachedSPS != null && cachedPPS != null) {
                                        outputStream.write(byteArrayOf(0x00, 0x00, 0x00, 0x01))
                                        outputStream.write(cachedSPS)
                                        outputStream.write(byteArrayOf(0x00, 0x00, 0x00, 0x01))
                                        outputStream.write(cachedPPS)
                                        outputStream.write(data)

                                        frameCallback.onFrameEncoded(outputStream.toByteArray(), true, pts)
                                        Log.d(TAG, "Sent I-frame with PTS=$pts and SPS/PPS (${outputStream.size()} bytes)")
                                    } else {
                                        // P-frames
                                        outputStream.write(data)
                                        frameCallback.onFrameEncoded(outputStream.toByteArray(), false, pts)
                                    }
                                }
                            }

                            mediaCodec?.releaseOutputBuffer(outputBufferIndex, false)
                        }
                    }

                } catch (e: InterruptedException) {
                    Log.d(TAG, "Encoder output thread interrupted, exiting")
                    break
                } catch (e: Exception) {
                    consecutiveErrors++
                    Log.e(TAG, "Error in encoder output thread ($consecutiveErrors/$maxConsecutiveErrors): ${e.message}")

                    if (consecutiveErrors >= maxConsecutiveErrors) {
                        Log.e(TAG, "Encoder crashed after $consecutiveErrors consecutive errors")

                        // Try resolution fallback before first frame
                        if (!firstFrameSent) {
                            val newMaxSize = chooseMaxSizeFallback(currentWidth, currentHeight)
                            if (newMaxSize > 0) {
                                Log.w(TAG, "Retrying with reduced resolution: max dimension = $newMaxSize")
                                maxSize = newMaxSize
                                consecutiveErrors = 0

                                // Notify caller to restart encoder
                                frameCallback.onEncoderRestartNeeded()
                                break
                            }
                            Log.w(TAG, "No resolution fallback available, trying different encoder")
                        }

                        // Try different encoder
                        preferredEncoderName?.let {
                            Log.w(TAG, "Marking encoder as failed: $it")
                            failedEncoders.add(it)
                        }

                        if (encoderAttempts < 2) {
                            Log.w(TAG, "Attempting to restart with alternative encoder (attempt ${encoderAttempts + 1})")
                            preferredEncoderName = null
                            encoderAttempts++

                            // Notify caller to restart encoder
                            frameCallback.onEncoderRestartNeeded()
                        }
                        break
                    }

                    Thread.sleep(100)
                }
            }

            Log.d(TAG, "Encoder output thread stopped")
        }
        encoderOutputThread?.start()
    }

    /**
     * Choose next smaller resolution from fallback list for encoder retry.
     */
    private fun chooseMaxSizeFallback(currentWidth: Int, currentHeight: Int): Int {
        val currentMaxSize = maxOf(currentWidth, currentHeight)
        for (value in MAX_SIZE_FALLBACK) {
            if (value < currentMaxSize) {
                Log.d(TAG, "Choosing fallback resolution: max dimension = $value (current: $currentMaxSize)")
                return value
            }
        }
        Log.d(TAG, "No resolution fallback available for ${currentWidth}x${currentHeight}")
        return 0
    }
}
