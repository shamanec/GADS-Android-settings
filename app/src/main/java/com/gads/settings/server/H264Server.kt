package com.gads.settings.server

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import com.gads.settings.server.utils.EncoderConfig
import com.gads.settings.server.wrappers.DisplayManagerWrapper

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
    private val config: EncoderConfig = EncoderConfig()
) {
    private val TAG = "GADS H264 Server"

    // WebSocket server for broadcasting frames
    private val websocketServer = H264WebSocketServer(1991, this)

    // H.264 encoder instance
    private var encoder: H264Encoder? = null

    // Virtual display
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

    // Threading
    private var encoderThread: HandlerThread? = null
    private var encoderHandler: Handler? = null

    companion object {
        // Timing constants for encoder lifecycle management
        private const val ENCODER_RESTART_DELAY_MS = 500L // Delay to ensure MediaCodec resources are fully released
        private const val SCREEN_REFRESH_DELAY_MS = 200L // Delay for encoder to be ready before triggering refresh
        private const val TOTAL_RESTART_REFRESH_DELAY_MS = ENCODER_RESTART_DELAY_MS + SCREEN_REFRESH_DELAY_MS // Total delay for restart + refresh
        private const val ORIENTATION_POLL_INTERVAL_MS = 500L // Polling interval for orientation changes

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

                val audioInputType = args.find { it.startsWith("audio_input_type=") }?.substringAfter("=") ?: "microphone"
                Log.d(TAG, "Audio input type: $audioInputType (handled by AudioService — H264Server does not bind port 1992)")

                val server = H264Server(config = config)

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
            Log.d(TAG, "Video WebSocket server started on port 1991")
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

        // Cancel any pending restart operations and reset the flag
        isRestarting = false
        encoderHandler?.removeCallbacksAndMessages(null)

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
     * Set target FPS (takes effect on next encoder restart).
     */
    fun setTargetFPS(fps: Int) {
        this.targetFPS = fps
        Log.d(TAG, "Target FPS set to: $targetFPS")
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
     * Set I-frame interval (takes effect on next encoder restart).
     */
    fun setIFrameInterval(interval: Int) {
        this.iFrameInterval = interval
        Log.d(TAG, "I-frame interval set to: $iFrameInterval seconds")
    }

    /**
     * Trigger a screen refresh by showing volume UI.
     * Volume level doesn't matter for remote control, but the UI forces display update.
     */
    fun triggerScreenRefresh() {
        try {
            // Press volume down - shows volume UI overlay which forces display update
            Runtime.getRuntime().exec(arrayOf("input", "keyevent", "KEYCODE_VOLUME_DOWN"))
            Log.d(TAG, "Triggered screen refresh via volume UI")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to trigger screen refresh: ${e.message}")
        }
    }

    /**
     * Restart the encoder (e.g., for orientation change or config change).
     */
    fun restartEncoder() {
        // Prevent concurrent restarts (race condition during rapid orientation changes)
        if (isRestarting) {
            Log.d(TAG, "Restart already in progress, ignoring")
            return
        }

        isRestarting = true
        Log.d(TAG, "Starting encoder restart")

        // Cancel any pending createVirtualDisplay tasks to prevent multiple instances
        encoderHandler?.removeCallbacksAndMessages(null)

        encoderHandler?.post {
            releaseEncoder()
        }
        // Add delay to ensure MediaCodec native resources are fully released
        // before creating new encoder (prevents Error 0xffffffe0)
        encoderHandler?.postDelayed({
            try {
                createVirtualDisplay()
            } finally {
                isRestarting = false
                Log.d(TAG, "Encoder restart complete")
            }
        }, ENCODER_RESTART_DELAY_MS)
    }

    /**
     * Create VirtualDisplay using either MediaProjection or shell privilege path.
     */
    @SuppressLint("WrongConstant")
    private fun createVirtualDisplay() {
        Log.d(TAG, "Creating VirtualDisplay...")

        // Get display metrics
        val metrics = getDisplayMetrics()
        val metricsWidth = metrics.widthPixels
        val metricsHeight = metrics.heightPixels

        // Scale down resolution to reduce encoding load and bandwidth
        // Align to multiples of 16 for hardware encoder compatibility
        mWidth = (metricsWidth / scalingFactor / 16) * 16
        mHeight = (metricsHeight / scalingFactor / 16) * 16
        mDensity = metrics.densityDpi

        val orientation = if (metricsWidth > metricsHeight) "LANDSCAPE" else "PORTRAIT"
        Log.d(TAG, "Creating encoder: ${mWidth}x${mHeight} @ ${targetFPS}fps, bitrate: $bitrate")
        Log.d(TAG, "Display metrics: ${metricsWidth}x${metricsHeight} ($orientation), density: $mDensity")

        try {
            // Create H.264 encoder with callback
            encoder = H264Encoder(object : H264Encoder.FrameCallback {
                override fun onFrameEncoded(data: ByteArray, isKeyFrame: Boolean, pts: Long) {
                    websocketServer.broadcast(data)
                }

                override fun onEncoderRestartNeeded() {
                    // restartEncoder() already includes proper delays and guards
                    restartEncoder()
                    // Trigger screen refresh after recovery
                    encoderHandler?.postDelayed({
                        triggerScreenRefresh()
                    }, TOTAL_RESTART_REFRESH_DELAY_MS)
                }
            })

            // Start encoder and get input surface
            val inputSurface = encoder?.start(mWidth, mHeight, bitrate, targetFPS, iFrameInterval)
            if (inputSurface == null) {
                throw RuntimeException("Failed to start encoder")
            }

            createVirtualDisplayWithShellPrivileges(inputSurface)

            Log.d(TAG, "VirtualDisplay and encoder created successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Error creating VirtualDisplay and encoder", e)
            throw e
        }
    }

    // SurfaceControl display token (for fallback)
    private var surfaceControlDisplay: android.os.IBinder? = null

    // Orientation monitoring
    private var orientationMonitorThread: Thread? = null
    @Volatile
    private var monitoringOrientation = false

    // Restart guard to prevent race conditions
    @Volatile
    private var isRestarting = false

    /**
     * Create VirtualDisplay using hidden APIs (app_process mode).
     * Simple scrcpy approach: try DisplayManager, fallback to SurfaceControl.
     * Reference: scrcpy ScreenCapture.java lines 127-144
     */
    private fun createVirtualDisplayWithShellPrivileges(inputSurface: Surface) {
        // Try DisplayManager approach (preferred) - scrcpy's simple method
        Log.d(TAG, "Attempting DisplayManager.createVirtualDisplay()...")
        mVirtualDisplay = DisplayManagerWrapper.createVirtualDisplay(
            name = "GADSScreenCapture",
            width = mWidth,
            height = mHeight,
            displayIdToMirror = 0,  // Default display
            surface = inputSurface
        )

        if (mVirtualDisplay != null) {
            Log.d(TAG, "✓ VirtualDisplay created via DisplayManager")
            startOrientationMonitoring()
            return
        }

        // Fallback to SurfaceControl
        Log.w(TAG, "DisplayManager failed, trying SurfaceControl...")
        createVirtualDisplayWithSurfaceControl(inputSurface)
    }

    /**
     * Fallback: Create display using SurfaceControl (lower-level API).
     * This works on older Android versions or when DisplayManager fails.
     */
    private fun createVirtualDisplayWithSurfaceControl(inputSurface: Surface) {
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
                com.gads.settings.server.wrappers.SurfaceControlWrapper.setDisplaySurface(displayToken, inputSurface)

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
            Log.d(TAG, "Orientation monitoring started (polling every ${ORIENTATION_POLL_INTERVAL_MS}ms)")

            while (monitoringOrientation) {
                try {
                    Thread.sleep(ORIENTATION_POLL_INTERVAL_MS)

                    val metrics = getDisplayMetrics()
                    val currentWidth = metrics?.widthPixels ?: lastWidth
                    val currentHeight = metrics?.heightPixels ?: lastHeight

                    // Check if dimensions changed (rotation occurred)
                    if (currentWidth != lastWidth || currentHeight != lastHeight) {
                        Log.d(TAG, "Display size changed: ${lastWidth}x${lastHeight} -> ${currentWidth}x${currentHeight}")
                        Log.d(TAG, "Restarting encoder for new orientation...")

                        lastWidth = currentWidth
                        lastHeight = currentHeight

                        // Use restartEncoder() to benefit from race condition protection
                        restartEncoder()

                        // Trigger screen refresh after encoder restart to get immediate frame
                        encoderHandler?.postDelayed({
                            triggerScreenRefresh()
                        }, TOTAL_RESTART_REFRESH_DELAY_MS)
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
            // Stop orientation monitoring first to prevent it from triggering another restart
            stopOrientationMonitoring()

            // Release the encoder
            encoder?.release()
            encoder = null

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

            Log.d(TAG, "Encoder released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing encoder", e)
        }
    }
}
