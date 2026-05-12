package com.gads.settings.server

import android.content.Context
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.gads.settings.server.utils.EncoderConfig
import java.util.concurrent.atomic.AtomicBoolean

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
    private val websocketServer = H264WebSocketServer(1991, this)

    // Display manager for VirtualDisplay lifecycle
    private val displayManager: VirtualDisplayManager = VirtualDisplayManager(
        context = context,
        useMediaProjection = useMediaProjection,
        mediaProjection = mediaProjection,
        onOrientationChanged = {
            restartEncoder()
            // Trigger screen refresh after encoder restart to get immediate frame
            encoderHandler?.postDelayed({
                triggerScreenRefresh()
            }, TOTAL_RESTART_REFRESH_DELAY_MS)
        }
    )

    // H.264 encoder instance
    private var encoder: H264Encoder? = null

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

    // Restart guard to prevent race conditions
    private var isRestarting = AtomicBoolean(false)

    companion object {
        // Timing constants for encoder lifecycle management
        private const val ENCODER_RESTART_DELAY_MS = 500L // Delay to ensure MediaCodec resources are fully released
        private const val SCREEN_REFRESH_DELAY_MS = 200L // Delay for encoder to be ready before triggering refresh
        private const val TOTAL_RESTART_REFRESH_DELAY_MS = ENCODER_RESTART_DELAY_MS + SCREEN_REFRESH_DELAY_MS // Total delay for restart + refresh

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

        // Cancel any pending restart operations and reset the flag
        isRestarting.set(false)
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
    fun setScalingFactor(percentage: Int) {
        this.scalingFactor = when (percentage) {
            25 -> 4
            50 -> 2
            else -> 2
        }
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
     */
    fun triggerScreenRefresh() {
        displayManager.triggerScreenRefresh()
    }

    /**
     * Restart the encoder (e.g., for orientation change or config change).
     */
    fun restartEncoder() {
        // Prevent concurrent restarts (race condition during rapid orientation changes)
        if (!isRestarting.compareAndSet(false, true)) {
            Log.d(TAG, "Restart already in progress, ignoring")
            return
        }

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
                isRestarting.set(false)
                Log.d(TAG, "Encoder restart complete")
            }
        }, ENCODER_RESTART_DELAY_MS)
    }

    /**
     * Create VirtualDisplay using either MediaProjection or shell privilege path.
     */
    private fun createVirtualDisplay() {
        Log.d(TAG, "Creating VirtualDisplay...")

        // Get display metrics
        val metrics = displayManager.getDisplayMetrics(
            defaultWidth = config.width,
            defaultHeight = config.height
        )
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
                        displayManager.triggerScreenRefresh()
                    }, TOTAL_RESTART_REFRESH_DELAY_MS)
                }
            })

            // Start encoder and get input surface
            val inputSurface = encoder?.start(mWidth, mHeight, bitrate, targetFPS, iFrameInterval)
                ?: throw RuntimeException("Failed to start encoder")

            displayManager.createVirtualDisplay(
                name = "GADSScreenCapture",
                width = mWidth,
                height = mHeight,
                density = mDensity,
                surface = inputSurface,
                handler = encoderHandler
            )

            Log.d(TAG, "VirtualDisplay and encoder created successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Error creating VirtualDisplay and encoder", e)
            throw e
        }
    }

    /**
     * Release encoder and virtual display resources.
     */
    private fun releaseEncoder() {
        try {
            // Release the encoder
            encoder?.release()
            encoder = null

            // Release VirtualDisplay and SurfaceControl resources
            displayManager.release()

            Log.d(TAG, "Encoder released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing encoder", e)
        }
    }
}
