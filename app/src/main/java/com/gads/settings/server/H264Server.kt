package com.gads.settings.server

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import com.gads.settings.server.utils.EncoderConfig
import com.gads.settings.server.wrappers.DisplayManagerWrapper
import java.io.ByteArrayOutputStream

/**
 * Scrcpy-style H.264 screen capture server that can run with or without MediaProjection.
 *
 * This server implements two operating modes:
 * 1. **Activity mode**: Uses MediaProjection (requires user consent dialog)
 * 2. **app_process mode**: Uses hidden APIs with shell privileges (no consent dialog)
 *
 * The key innovation from scrcpy: DisplayManager.createVirtualDisplay() with **null MediaProjection**
 * works when running with shell user privileges, bypassing the permission requirement.
 *
 * Streams H.264 video over WebSocket on port 1992 in Annex-B format with timestamps,
 * compatible with Pion WebRTC decoder.
 *
 * Based on H264ScreenCaptureService.kt with adaptations for hybrid deployment.
 */
class H264Server(
    private val context: Context? = null,
    private val useMediaProjection: Boolean = false,
    private val mediaProjection: MediaProjection? = null,
    private val config: EncoderConfig = EncoderConfig()
) {
    private val TAG = "GADS H264 Server"

    // WebSocket server for broadcasting frames
    private val websocketServer = H264WebSocketServer(1992, this)

    // Encoder components
    private var mMediaCodec: MediaCodec? = null
    private var mInputSurface: Surface? = null
    private var mVirtualDisplay: VirtualDisplay? = null

    // Display metrics
    private var mWidth = 0
    private var mHeight = 0
    private var mDensity = 0

    // Encoder configuration (mutable for runtime changes)
    private var targetFPS = config.fps
    private var bitrate = config.bitrate
    private var scalingFactor = config.scalingFactor
    private var iFrameInterval = config.iFrameInterval

    // Encoder state
    @Volatile
    private var isEncoding = false
    private var preferredEncoderName: String? = null
    private var encoderAttempts = 0
    private val failedEncoders = mutableSetOf<String>()
    private var firstFrameSent = false
    private var maxSize = 0 // Track current max resolution for fallback, 0 means no limit

    // SPS/PPS (Sequence Parameter Set / Picture Parameter Set) cache
    private var cachedSPS: ByteArray? = null
    private var cachedPPS: ByteArray? = null

    // Threading
    private var encoderThread: HandlerThread? = null
    private var encoderHandler: Handler? = null

    companion object {
        // Resolution fallback values for encoder failures (in descending order)
        private val MAX_SIZE_FALLBACK = intArrayOf(2560, 1920, 1600, 1280, 1024, 800)

        /**
         * Main entry point for app_process execution (no MediaProjection).
         *
         * Usage: adb shell CLASSPATH=/path/to/app.apk app_process / com.gads.settings.server.H264ServerKt \
         *        port=1992 width=1280 height=720 bitrate=4000000 fps=30
         */
        @JvmStatic
        fun main(args: Array<String>) {
            val TAG = "GADS H264 Main"
            Log.d(TAG, "Starting H.264 server via app_process")
            Log.d(TAG, "Arguments: ${args.joinToString(" ")}")

            try {
                val config = EncoderConfig.fromArgs(args)
                Log.d(TAG, "Encoder config: $config")

                val server = H264Server(
                    context = null,
                    useMediaProjection = false,
                    mediaProjection = null,
                    config = config
                )

                server.start()

                // Keep main thread alive
                Log.d(TAG, "Server started successfully. Press Ctrl+C to stop.")
                Thread.currentThread().join()

            } catch (e: Exception) {
                Log.e(TAG, "Fatal error in main()", e)
                System.err.println("Error: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    /**
     * Start the server: create encoder thread, start WebSocket, create VirtualDisplay.
     */
    fun start() {
        Log.d(TAG, "Starting H264Server...")

        // Create background thread for encoder operations
        encoderThread = HandlerThread("EncoderThread")
        encoderThread?.start()
        encoderHandler = Handler(encoderThread!!.looper)

        // Start WebSocket server
        try {
            websocketServer.isReuseAddr = true
            websocketServer.start()
            Log.d(TAG, "WebSocket server started on port 1992")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start WebSocket server", e)
            throw e
        }

        // Create virtual display and start encoding
        encoderHandler?.post {
            createVirtualDisplay()
        }

        Log.d(TAG, "H264Server started successfully")
    }

    /**
     * Stop the server: release encoder, stop WebSocket, cleanup threads.
     */
    fun stop() {
        Log.d(TAG, "Stopping H264Server...")

        stopOrientationMonitoring()

        encoderHandler?.post {
            releaseEncoder()
        }

        try {
            websocketServer.stop()
            Log.d(TAG, "WebSocket server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping WebSocket server", e)
        }

        encoderThread?.quitSafely()
        encoderThread = null
        encoderHandler = null

        Log.d(TAG, "H264Server stopped")
    }

    /**
     * Set target FPS and restart encoder to apply.
     */
    fun setTargetFPS(fps: Int) {
        this.targetFPS = fps
        Log.d(TAG, "Target FPS set to: $targetFPS")
        restartEncoder()
    }

    /**
     * Set bitrate (takes effect on next encoder restart).
     */
    fun setBitrate(bitrate: Int) {
        this.bitrate = bitrate
        Log.d(TAG, "Bitrate set to: $bitrate")
    }

    /**
     * Set scaling factor (takes effect on next encoder restart).
     */
    fun setScalingFactor(scalingFactor: Int) {
        this.scalingFactor = scalingFactor
        Log.d(TAG, "Scaling factor set to: $scalingFactor")
    }

    /**
     * Set I-frame interval and restart encoder to apply.
     */
    fun setIFrameInterval(interval: Int) {
        this.iFrameInterval = interval
        Log.d(TAG, "I-frame interval set to: $iFrameInterval seconds")
        restartEncoder()
    }

    /**
     * Get cached SPS/PPS config frames.
     */
    fun getCachedConfigFrames(): Pair<ByteArray?, ByteArray?> {
        return Pair(cachedSPS, cachedPPS)
    }

    /**
     * Restart the encoder (e.g., for orientation change or config change).
     */
    private fun restartEncoder() {
        encoderHandler?.post {
            releaseEncoder()
            createVirtualDisplay()
        }
    }

    /**
     * Create VirtualDisplay using either MediaProjection or shell privilege path.
     */
    @SuppressLint("WrongConstant")
    private fun createVirtualDisplay() {
        Log.d(TAG, "Creating VirtualDisplay (useMediaProjection=$useMediaProjection)...")

        // Get display metrics
        val metrics = getDisplayMetrics()
        val metricsWidth = metrics.widthPixels
        val metricsHeight = metrics.heightPixels

        // Scale down resolution to reduce encoding load and bandwidth
        // Align to multiples of 16 for hardware encoder compatibility
        var targetWidth = (metricsWidth / scalingFactor / 16) * 16
        var targetHeight = (metricsHeight / scalingFactor / 16) * 16

        // Apply maxSize limit if set (for resolution fallback)
        if (maxSize > 0) {
            val currentMaxDimension = maxOf(targetWidth, targetHeight)
            if (currentMaxDimension > maxSize) {
                val scale = maxSize.toFloat() / currentMaxDimension
                targetWidth = ((targetWidth * scale).toInt() / 16) * 16
                targetHeight = ((targetHeight * scale).toInt() / 16) * 16
                Log.d(TAG, "Applied maxSize limit $maxSize: ${targetWidth}x${targetHeight}")
            }
        }

        mWidth = targetWidth
        mHeight = targetHeight
        mDensity = metrics.densityDpi

        val orientation = if (metricsWidth > metricsHeight) "LANDSCAPE" else "PORTRAIT"
        Log.d(TAG, "Creating encoder: ${mWidth}x${mHeight} @ ${targetFPS}fps, bitrate: $bitrate")
        Log.d(TAG, "Display metrics: ${metricsWidth}x${metricsHeight} ($orientation), density: $mDensity")

        try {
            // Create H.264 encoder
            mMediaCodec = selectEncoder()

            // Configure MediaFormat for H.264 video encoding
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, mWidth, mHeight)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, targetFPS)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval)

            // Color range for better color reproduction (API 24+)
            format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)

            // KEY_REPEAT_PREVIOUS_FRAME_AFTER causes crashes on some devices (Xiaomi/Android 15)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L) // 100ms
            }

            // Set actual FPS control (API 29+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                format.setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, targetFPS.toFloat())
                Log.d(TAG, "Set max FPS to encoder: $targetFPS")
            }

            mMediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            mInputSurface = mMediaCodec?.createInputSurface()
            mMediaCodec?.start()

            // Start encoder output thread
            startEncoderOutputThread()

            // Create VirtualDisplay based on mode
            if (useMediaProjection && mediaProjection != null) {
                createVirtualDisplayWithMediaProjection()
            } else {
                createVirtualDisplayWithShellPrivileges()
            }

            Log.d(TAG, "VirtualDisplay and encoder created successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Error creating VirtualDisplay and encoder", e)
            throw e
        }
    }

    /**
     * Create VirtualDisplay using MediaProjection (Activity mode).
     */
    private fun createVirtualDisplayWithMediaProjection() {
        if (mediaProjection == null) {
            throw IllegalStateException("MediaProjection is null but useMediaProjection=true")
        }

        mVirtualDisplay = mediaProjection.createVirtualDisplay(
            "GADSScreenCapture",
            mWidth,
            mHeight,
            mDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
            mInputSurface,
            null,
            encoderHandler
        )

        Log.d(TAG, "VirtualDisplay created via MediaProjection")
    }

    // SurfaceControl display token (for fallback)
    private var surfaceControlDisplay: android.os.IBinder? = null

    // Orientation monitoring
    private var orientationMonitorThread: Thread? = null
    @Volatile
    private var monitoringOrientation = false

    /**
     * Create VirtualDisplay using hidden APIs (app_process mode).
     * Simple scrcpy approach: try DisplayManager, fallback to SurfaceControl.
     * Reference: scrcpy ScreenCapture.java lines 127-144
     */
    private fun createVirtualDisplayWithShellPrivileges() {
        // Try DisplayManager approach (preferred) - scrcpy's simple method
        Log.d(TAG, "Attempting DisplayManager.createVirtualDisplay()...")
        mVirtualDisplay = DisplayManagerWrapper.createVirtualDisplay(
            name = "GADSScreenCapture",
            width = mWidth,
            height = mHeight,
            displayIdToMirror = 0,  // Default display
            surface = mInputSurface!!
        )

        if (mVirtualDisplay != null) {
            Log.d(TAG, "✓ VirtualDisplay created via DisplayManager")
            startOrientationMonitoring()
            return
        }

        // Fallback to SurfaceControl
        Log.w(TAG, "DisplayManager failed, trying SurfaceControl...")
        createVirtualDisplayWithSurfaceControl()
    }

    /**
     * Fallback: Create display using SurfaceControl (lower-level API).
     * This works on older Android versions or when DisplayManager fails.
     */
    private fun createVirtualDisplayWithSurfaceControl() {
        try {
            val displayToken = com.gads.settings.server.wrappers.SurfaceControlWrapper.createDisplay(
                "GADSScreenCapture",
                secure = false
            )

            if (displayToken == null) {
                throw RuntimeException("SurfaceControl.createDisplay() returned null")
            }

            surfaceControlDisplay = displayToken

            // Get display info for layer stack
            val metrics = getDisplayMetrics()
            val layerStack = 0 // Default display layer stack

            // Configure the display in a transaction
            com.gads.settings.server.wrappers.SurfaceControlWrapper.openTransaction()
            try {
                // Set the surface to render to
                com.gads.settings.server.wrappers.SurfaceControlWrapper.setDisplaySurface(displayToken, mInputSurface!!)

                // Set the projection (maps source rect to display rect)
                val sourceRect = android.graphics.Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
                val displayRect = android.graphics.Rect(0, 0, mWidth, mHeight)
                com.gads.settings.server.wrappers.SurfaceControlWrapper.setDisplayProjection(
                    displayToken,
                    orientation = 0, // 0 degrees rotation
                    layerStackRect = sourceRect,
                    displayRect = displayRect
                )

                // Set the layer stack
                com.gads.settings.server.wrappers.SurfaceControlWrapper.setDisplayLayerStack(displayToken, layerStack)

                Log.d(TAG, "SurfaceControl display configured: ${mWidth}x${mHeight}")
            } finally {
                com.gads.settings.server.wrappers.SurfaceControlWrapper.closeTransaction()
            }

            Log.d(TAG, "VirtualDisplay created via SurfaceControl (fallback)")

            // Start monitoring orientation changes for SurfaceControl too
            startOrientationMonitoring()

        } catch (e: Exception) {
            Log.e(TAG, "SurfaceControl fallback also failed", e)
            e.printStackTrace()
            throw RuntimeException("Both DisplayManager and SurfaceControl approaches failed. Cannot create display. Error: ${e.message}", e)
        }
    }

    /**
     * Get display metrics from context or via reflection.
     */
    private fun getDisplayMetrics(): DisplayMetrics {
        // If context is available, use it
        if (context != null) {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val display = windowManager.defaultDisplay
            val metrics = DisplayMetrics()
            display.getRealMetrics(metrics)
            return metrics
        }

        // Otherwise, use reflection (for app_process mode)
        val metrics = DisplayManagerWrapper.getDisplayInfo(displayId = 0)
        if (metrics != null) {
            return metrics
        }

        // Fallback to default
        Log.w(TAG, "Could not get display metrics, using defaults")
        return DisplayMetrics().apply {
            widthPixels = config.width
            heightPixels = config.height
            densityDpi = 240
        }
    }

    /**
     * Select the best available H.264 encoder with fallback logic.
     * Pattern from H264ScreenCaptureService.kt lines 534-594.
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
     * Start encoder output thread that reads H.264 frames and broadcasts to WebSocket.
     * Pattern from H264ScreenCaptureService.kt lines 172-344.
     */
    private fun startEncoderOutputThread() {
        isEncoding = true
        Thread {
            // Some devices (Meizu) deadlock if the video encoding thread has no Looper
            android.os.Looper.prepare()

            val bufferInfo = MediaCodec.BufferInfo()
            var consecutiveErrors = 0
            val maxConsecutiveErrors = 5

            Log.d(TAG, "Encoder output thread started")

            while (isEncoding) {
                try {
                    // Blocking dequeue (timeout -1) for efficiency
                    val outputBufferIndex = mMediaCodec?.dequeueOutputBuffer(bufferInfo, -1) ?: -1

                    when {
                        // MediaCodec signals format changed, extract SPS/PPS
                        outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val newFormat = mMediaCodec?.outputFormat
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
                            val encodedData = mMediaCodec?.getOutputBuffer(outputBufferIndex)

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

                                        websocketServer.broadcast(outputStream.toByteArray())
                                        Log.d(TAG, "Sent I-frame with PTS=$pts and SPS/PPS (${outputStream.size()} bytes)")
                                    } else {
                                        // P-frames
                                        outputStream.write(data)
                                        websocketServer.broadcast(outputStream.toByteArray())
                                    }
                                }
                            }

                            mMediaCodec?.releaseOutputBuffer(outputBufferIndex, false)
                        }
                    }

                } catch (e: Exception) {
                    consecutiveErrors++
                    Log.e(TAG, "Error in encoder output thread ($consecutiveErrors/$maxConsecutiveErrors): ${e.message}")

                    if (consecutiveErrors >= maxConsecutiveErrors) {
                        Log.e(TAG, "Encoder crashed after $consecutiveErrors consecutive errors")

                        // Try resolution fallback before first frame
                        if (!firstFrameSent) {
                            val newMaxSize = chooseMaxSizeFallback(mWidth, mHeight)
                            if (newMaxSize > 0) {
                                Log.w(TAG, "Retrying with reduced resolution: max dimension = $newMaxSize")
                                maxSize = newMaxSize
                                consecutiveErrors = 0

                                encoderHandler?.post {
                                    releaseEncoder()
                                    createVirtualDisplay()
                                }
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

                            encoderHandler?.post {
                                releaseEncoder()
                                createVirtualDisplay()
                            }
                        }
                        break
                    }

                    Thread.sleep(100)
                }
            }

            Log.d(TAG, "Encoder output thread stopped")
        }.start()
    }

    /**
     * Choose next smaller resolution from fallback list for encoder retry.
     * Pattern from H264ScreenCaptureService.kt lines 155-167.
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

    /**
     * Start orientation monitoring.
     *
     * Android 14+ has broken DisplayListener, so we use polling as a reliable fallback.
     * Reference: scrcpy DisplaySizeMonitor.java lines 24-28
     */
    private fun startOrientationMonitoring() {
        // For now, use simple polling approach (works on all Android versions)
        // TODO: Implement DisplayWindowListener for Android 14+ (requires WindowManager wrapper)

        val initialMetrics = getDisplayMetrics()
        var lastWidth = initialMetrics.widthPixels
        var lastHeight = initialMetrics.heightPixels

        monitoringOrientation = true
        orientationMonitorThread = Thread {
            Log.d(TAG, "Orientation monitoring started (polling every 500ms)")

            while (monitoringOrientation) {
                try {
                    Thread.sleep(500)  // Poll every 500ms

                    val metrics = getDisplayMetrics()
                    val currentWidth = metrics?.widthPixels ?: lastWidth
                    val currentHeight = metrics?.heightPixels ?: lastHeight

                    // Check if dimensions changed (rotation occurred)
                    if (currentWidth != lastWidth || currentHeight != lastHeight) {
                        Log.d(TAG, "Display size changed: ${lastWidth}x${lastHeight} -> ${currentWidth}x${currentHeight}")
                        Log.d(TAG, "Restarting encoder for new orientation...")

                        lastWidth = currentWidth
                        lastHeight = currentHeight

                        encoderHandler?.post {
                            releaseEncoder()
                            createVirtualDisplay()
                        }
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error in orientation monitoring", e)
                }
            }

            Log.d(TAG, "Orientation monitoring stopped")
        }
        orientationMonitorThread?.start()
    }

    /**
     * Stop orientation monitoring.
     */
    private fun stopOrientationMonitoring() {
        monitoringOrientation = false
        orientationMonitorThread?.interrupt()
        orientationMonitorThread = null
        Log.d(TAG, "Orientation monitoring stopped")
    }

    /**
     * Release encoder and virtual display resources.
     */
    private fun releaseEncoder() {
        try {
            isEncoding = false

            // Release VirtualDisplay (if using DisplayManager approach)
            mVirtualDisplay?.release()
            mVirtualDisplay = null

            // Release SurfaceControl display (if using SurfaceControl fallback)
            surfaceControlDisplay?.let {
                try {
                    com.gads.settings.server.wrappers.SurfaceControlWrapper.destroyDisplay(it)
                    Log.d(TAG, "SurfaceControl display destroyed")
                } catch (e: Exception) {
                    Log.e(TAG, "Error destroying SurfaceControl display", e)
                }
            }
            surfaceControlDisplay = null

            mMediaCodec?.stop()
            mMediaCodec?.release()
            mMediaCodec = null

            mInputSurface?.release()
            mInputSurface = null

            firstFrameSent = false

            Log.d(TAG, "Encoder released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing encoder", e)
        }
    }
}
