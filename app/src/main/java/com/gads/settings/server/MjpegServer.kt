package com.gads.settings.server

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import com.gads.settings.server.wrappers.DisplayManagerWrapper
import java.io.ByteArrayOutputStream
import java.util.concurrent.LinkedBlockingDeque

/**
 * MJPEG screen capture server that can run with or without MediaProjection.
 *
 * Two operating modes:
 * 1. **Activity mode**: Uses MediaProjection (requires user consent dialog)
 * 2. **app_process mode**: Uses hidden APIs with shell privileges (no consent dialog)
 *
 * Captures screen via ImageReader, compresses frames to JPEG, and broadcasts
 * over WebSocket on port 1993.
 *
 */
class MjpegServer(
    private val context: Context? = null,
    private val useMediaProjection: Boolean = false,
    private val mediaProjection: MediaProjection? = null
) {
    private val TAG = "GADS MJPEG Server"

    // WebSocket server for broadcasting frames
    private val websocketServer = MjpegWebSocketServer(1993, this)

    // Virtual display and image capture
    private var mVirtualDisplay: VirtualDisplay? = null
    private var mImageReader: ImageReader? = null

    // Display metrics
    private var mWidth = 0
    private var mHeight = 0
    private var mDensity = 0

    // Configurable parameters (controlled via WebSocket messages)
    private var targetFPS = 15
    private var jpegQuality = 90
    private var scalingFactor = 2
    @Volatile
    private var frameIntervalNs = (1_000_000_000L / targetFPS)

    // Producer-consumer: listener acquires images, consumer thread compresses and broadcasts.
    // Blocking put() creates backpressure so the handler thread isn't wasted on bitmap
    // conversions that get thrown away.
    private val imageQueue = LinkedBlockingDeque<Bitmap>(3)
    private var imageConsumerThread: Thread? = null
    @Volatile
    private var isConsumerRunning = false

    // Threading
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    // SurfaceControl display token (for fallback)
    private var surfaceControlDisplay: android.os.IBinder? = null

    // Orientation monitoring
    private var orientationMonitorThread: Thread? = null
    @Volatile
    private var monitoringOrientation = false

    // Restart guard
    @Volatile
    private var isRestarting = false

    companion object {
        private const val CAPTURE_RESTART_DELAY_MS = 500L
        private const val ORIENTATION_POLL_INTERVAL_MS = 500L

        /**
         * Main entry point for app_process execution.
         *
         * Usage: adb shell CLASSPATH=/path/to/app.apk app_process / com.gads.settings.server.MjpegServerKt
         */
        @JvmStatic
        fun main(args: Array<String>) {
            val TAG = "GADS MJPEG Main"
            Log.d(TAG, "Starting MJPEG server via app_process")

            try {
                val server = MjpegServer(
                    context = null,
                    useMediaProjection = false,
                    mediaProjection = null
                )

                server.start()

                Log.d(TAG, "Server started successfully. Press Ctrl+C to stop.")
                Thread.currentThread().join()

            } catch (e: Exception) {
                Log.e(TAG, "Fatal error in main()", e)
                System.err.println("Error: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    fun start() {
        Log.d(TAG, "Starting MjpegServer...")

        // Create background thread for capture operations
        captureThread = HandlerThread("MjpegCaptureThread")
        captureThread?.start()
        captureHandler = Handler(captureThread!!.looper)

        // Start WebSocket server
        try {
            websocketServer.isReuseAddr = true
            websocketServer.start()
            Log.d(TAG, "WebSocket server started on port 1993")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start WebSocket server", e)
            throw e
        }

        // Start image consumer thread
        isConsumerRunning = true
        imageConsumerThread = Thread(ImageConsumer(), "MjpegConsumer")
        imageConsumerThread?.start()

        // Create virtual display and start capturing
        captureHandler?.post {
            createVirtualDisplay()
        }

        Log.d(TAG, "MjpegServer started successfully")
    }

    fun stop() {
        Log.d(TAG, "Stopping MjpegServer...")

        stopOrientationMonitoring()

        isRestarting = false
        captureHandler?.removeCallbacksAndMessages(null)

        // Stop consumer thread
        isConsumerRunning = false
        imageConsumerThread?.interrupt()
        imageConsumerThread = null

        // Recycle any queued bitmaps
        val drained = mutableListOf<Bitmap>()
        imageQueue.drainTo(drained)
        drained.forEach { it.recycle() }

        captureHandler?.post {
            releaseCapture()
        }

        try {
            websocketServer.stop()
            Log.d(TAG, "WebSocket server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping WebSocket server", e)
        }

        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null

        Log.d(TAG, "MjpegServer stopped")
    }

    fun setTargetFPS(fps: Int) {
        this.targetFPS = fps
        this.frameIntervalNs = 1_000_000_000L / fps
        Log.d(TAG, "Target FPS set to: $targetFPS")
    }

    fun setJpegQuality(quality: Int) {
        this.jpegQuality = quality
        Log.d(TAG, "JPEG quality set to: $jpegQuality")
    }

    fun setScalingFactor(factor: Int) {
        this.scalingFactor = factor
        Log.d(TAG, "Scaling factor set to: $scalingFactor")
    }

    fun triggerScreenRefresh() {
        try {
            Runtime.getRuntime().exec(arrayOf("input", "keyevent", "KEYCODE_VOLUME_DOWN"))
            Log.d(TAG, "Triggered screen refresh via volume UI")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to trigger screen refresh: ${e.message}")
        }
    }

    fun restartCapture() {
        if (isRestarting) {
            Log.d(TAG, "Restart already in progress, ignoring")
            return
        }

        isRestarting = true
        Log.d(TAG, "Starting capture restart")

        captureHandler?.removeCallbacksAndMessages(null)

        captureHandler?.post {
            releaseCapture()
        }
        captureHandler?.postDelayed({
            try {
                createVirtualDisplay()
            } finally {
                isRestarting = false
                Log.d(TAG, "Capture restart complete")
            }
        }, CAPTURE_RESTART_DELAY_MS)
    }

    /**
     * Create VirtualDisplay with ImageReader as the surface consumer.
     */
    @SuppressLint("WrongConstant")
    private fun createVirtualDisplay() {
        // Close old ImageReader if it exists
        mImageReader?.close()
        mImageReader = null

        val metrics = getDisplayMetrics()
        val metricsWidth = metrics.widthPixels
        val metricsHeight = metrics.heightPixels

        mWidth = metricsWidth / scalingFactor
        mHeight = metricsHeight / scalingFactor
        mDensity = metrics.densityDpi

        val orientation = if (metricsWidth > metricsHeight) "LANDSCAPE" else "PORTRAIT"
        Log.d(TAG, "Creating capture: ${mWidth}x${mHeight} @ ${targetFPS}fps, quality: $jpegQuality")
        Log.d(TAG, "Display metrics: ${metricsWidth}x${metricsHeight} ($orientation), density: $mDensity")

        try {
            // Create ImageReader as the surface consumer
            mImageReader = ImageReader.newInstance(mWidth, mHeight, PixelFormat.RGBA_8888, 2)
            mImageReader!!.setOnImageAvailableListener(ImageAvailableListener(), captureHandler)

            val inputSurface = mImageReader!!.surface

            // Create VirtualDisplay based on mode
            if (useMediaProjection && mediaProjection != null) {
                createVirtualDisplayWithMediaProjection(inputSurface)
            } else {
                createVirtualDisplayWithShellPrivileges(inputSurface)
            }

            Log.d(TAG, "VirtualDisplay and ImageReader created successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Error creating VirtualDisplay and ImageReader", e)
            throw e
        }
    }

    private fun createVirtualDisplayWithMediaProjection(inputSurface: Surface) {
        if (mediaProjection == null) {
            throw IllegalStateException("MediaProjection is null but useMediaProjection=true")
        }

        mVirtualDisplay = mediaProjection.createVirtualDisplay(
            "GADSMjpegCapture",
            mWidth,
            mHeight,
            mDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
            inputSurface,
            null,
            captureHandler
        )

        Log.d(TAG, "VirtualDisplay created via MediaProjection")
    }

    private fun createVirtualDisplayWithShellPrivileges(inputSurface: Surface) {
        Log.d(TAG, "Attempting DisplayManager.createVirtualDisplay()...")
        mVirtualDisplay = DisplayManagerWrapper.createVirtualDisplay(
            name = "GADSMjpegCapture",
            width = mWidth,
            height = mHeight,
            displayIdToMirror = 0,
            surface = inputSurface
        )

        if (mVirtualDisplay != null) {
            Log.d(TAG, "VirtualDisplay created via DisplayManager")
            startOrientationMonitoring()
            return
        }

        // Fallback to SurfaceControl
        Log.w(TAG, "DisplayManager failed, trying SurfaceControl...")
        createVirtualDisplayWithSurfaceControl(inputSurface)
    }

    private fun createVirtualDisplayWithSurfaceControl(inputSurface: Surface) {
        try {
            val displayToken = com.gads.settings.server.wrappers.SurfaceControlWrapper.createDisplay(
                "GADSMjpegCapture",
                secure = false
            )

            if (displayToken == null) {
                throw RuntimeException("SurfaceControl.createDisplay() returned null")
            }

            surfaceControlDisplay = displayToken

            val metrics = getDisplayMetrics()
            val layerStack = 0

            com.gads.settings.server.wrappers.SurfaceControlWrapper.openTransaction()
            try {
                com.gads.settings.server.wrappers.SurfaceControlWrapper.setDisplaySurface(displayToken, inputSurface)

                val sourceRect = android.graphics.Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
                val displayRect = android.graphics.Rect(0, 0, mWidth, mHeight)
                com.gads.settings.server.wrappers.SurfaceControlWrapper.setDisplayProjection(
                    displayToken,
                    orientation = 0,
                    layerStackRect = sourceRect,
                    displayRect = displayRect
                )

                com.gads.settings.server.wrappers.SurfaceControlWrapper.setDisplayLayerStack(displayToken, layerStack)

                Log.d(TAG, "SurfaceControl display configured: ${mWidth}x${mHeight}")
            } finally {
                com.gads.settings.server.wrappers.SurfaceControlWrapper.closeTransaction()
            }

            Log.d(TAG, "VirtualDisplay created via SurfaceControl (fallback)")
            startOrientationMonitoring()

        } catch (e: Exception) {
            Log.e(TAG, "SurfaceControl fallback also failed", e)
            e.printStackTrace()
            throw RuntimeException("Both DisplayManager and SurfaceControl approaches failed. Error: ${e.message}", e)
        }
    }

    private fun getDisplayMetrics(): DisplayMetrics {
        if (context != null) {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val display = windowManager.defaultDisplay
            val metrics = DisplayMetrics()
            display.getRealMetrics(metrics)
            return metrics
        }

        val metrics = DisplayManagerWrapper.getDisplayInfo(displayId = 0)
        if (metrics != null) {
            return metrics
        }

        Log.w(TAG, "Could not get display metrics, using defaults")
        return DisplayMetrics().apply {
            widthPixels = 1280
            heightPixels = 720
            densityDpi = 240
        }
    }

    /**
     * Convert an ImageReader Image to a Bitmap.
     * Handles row padding from the pixel buffer.
     */
    private fun imageToBitmap(image: Image): Bitmap {
        val imageWidth = image.width
        val imageHeight = image.height

        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * imageWidth

        if (rowPadding == 0) {
            // No padding — create bitmap at exact size, no crop needed
            val bitmap = Bitmap.createBitmap(imageWidth, imageHeight, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)
            image.close()
            return bitmap
        }

        // Row padding present — create oversized bitmap then crop
        val newWidth = imageWidth + (rowPadding / pixelStride)
        val fullBitmap = Bitmap.createBitmap(newWidth, imageHeight, Bitmap.Config.ARGB_8888)
        fullBitmap.copyPixelsFromBuffer(buffer)
        image.close()

        val croppedBitmap = Bitmap.createBitmap(fullBitmap, 0, 0, imageWidth, imageHeight)
        fullBitmap.recycle()
        return croppedBitmap
    }

    /**
     * Listener that acquires frames from ImageReader and queues them for compression.
     * Uses blocking put() so the handler thread pauses when the consumer can't keep up,
     * preventing wasted bitmap conversions.
     */
    private inner class ImageAvailableListener : ImageReader.OnImageAvailableListener {
        private var wasIdle = false

        override fun onImageAvailable(reader: ImageReader) {
            try {
                val image = reader.acquireLatestImage() ?: return

                if (!websocketServer.hasClients()) {
                    image.close()
                    if (!wasIdle) {
                        Log.d(TAG, "No clients connected, skipping frame processing")
                        wasIdle = true
                    }
                    return
                }

                if (wasIdle) {
                    Log.d(TAG, "Client connected, resuming frame processing")
                    wasIdle = false
                }

                val bitmap = imageToBitmap(image)
                imageQueue.put(bitmap)
            } catch (e: InterruptedException) {
                // Consumer stopped, ignore
            } catch (e: Exception) {
                Log.e(TAG, "Error processing image", e)
            }
        }
    }

    /**
     * Consumer thread: takes bitmaps from queue, compresses to JPEG, broadcasts.
     * BAOS is reused across frames to avoid per-frame allocation.
     * Sleeps only the remaining time after compression to hit target FPS.
     */
    private inner class ImageConsumer : Runnable {
        override fun run() {
            val outputStream = ByteArrayOutputStream()
            while (isConsumerRunning) {
                try {
                    val frameStart = System.nanoTime()

                    val bitmap = imageQueue.take()

                    outputStream.reset()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, outputStream)
                    bitmap.recycle()

                    websocketServer.broadcast(outputStream.toByteArray())

                    // Sleep only the remaining time to hit target FPS
                    val elapsedNs = System.nanoTime() - frameStart
                    val sleepMs = (frameIntervalNs - elapsedNs) / 1_000_000
                    if (sleepMs > 0) {
                        Thread.sleep(sleepMs)
                    }
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun startOrientationMonitoring() {
        val initialMetrics = getDisplayMetrics()
        var lastWidth = initialMetrics.widthPixels
        var lastHeight = initialMetrics.heightPixels

        monitoringOrientation = true
        orientationMonitorThread = Thread {
            Log.d(TAG, "Orientation monitoring started (polling every ${ORIENTATION_POLL_INTERVAL_MS}ms)")

            while (monitoringOrientation) {
                try {
                    Thread.sleep(ORIENTATION_POLL_INTERVAL_MS)

                    if (!websocketServer.hasClients()) continue

                    val metrics = getDisplayMetrics()
                    val currentWidth = metrics.widthPixels
                    val currentHeight = metrics.heightPixels

                    if (currentWidth != lastWidth || currentHeight != lastHeight) {
                        Log.d(TAG, "Display size changed: ${lastWidth}x${lastHeight} -> ${currentWidth}x${currentHeight}")

                        lastWidth = currentWidth
                        lastHeight = currentHeight

                        restartCapture()
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

    private fun stopOrientationMonitoring() {
        monitoringOrientation = false
        orientationMonitorThread?.interrupt()
        orientationMonitorThread = null
        Log.d(TAG, "Orientation monitoring stopped")
    }

    private fun releaseCapture() {
        try {
            stopOrientationMonitoring()

            mImageReader?.setOnImageAvailableListener(null, null)
            mImageReader?.close()
            mImageReader = null

            mVirtualDisplay?.release()
            mVirtualDisplay = null

            surfaceControlDisplay?.let {
                try {
                    com.gads.settings.server.wrappers.SurfaceControlWrapper.destroyDisplay(it)
                    Log.d(TAG, "SurfaceControl display destroyed")
                } catch (e: Exception) {
                    Log.e(TAG, "Error destroying SurfaceControl display", e)
                }
            }
            surfaceControlDisplay = null

            Log.d(TAG, "Capture released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing capture", e)
        }
    }
}
