package com.gads.settings.streaming

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.ImageReader.OnImageAvailableListener
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.WindowManager
import com.gads.settings.streaming.NotificationUtils.getNotification
import java.io.ByteArrayOutputStream
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingDeque

class MjpegScreenCaptureService: Service() {
    private var mMediaProjection: MediaProjection? = null
    private var mImageReader: ImageReader? = null
    private var mHandler: Handler? = null
    private var mDisplay: Display? = null
    private var mVirtualDisplay: VirtualDisplay? = null
    private var mDensity = 0
    private var mWidth = 0
    private var mHeight = 0

    private var targetFPS = 15
    private var jpegQuality = 90
    private var scalingFactor = 2
    private var frameIntervalMs = (1000 / targetFPS).toLong()

    // Image consumer thread
    private var imageConsumerThread: Thread? = null
    @Volatile
    private var isConsumerRunning = false

    // Orientation monitoring (polling approach - reliable on all Android versions)
    private var orientationMonitorThread: Thread? = null
    @Volatile
    private var monitoringOrientation = false

    var server: MjpegWebsocketServer? = null

    val TAG: String = "GADS Mjpeg"

    init {
        server = MjpegWebsocketServer(1991, this)
        server?.setReuseAddr(true)
        server?.start()
    }

    companion object {
        fun getStartIntent(context: Context?, resultCode: Int, data: Intent?): Intent {
            val intent = Intent(context, MjpegScreenCaptureService::class.java)
            intent.putExtra("ACTION", "START")
            intent.putExtra("RESULT_CODE", resultCode)
            intent.putExtra("DATA", data)
            return intent
        }

        fun getStopIntent(context: Context?): Intent {
            val intent = Intent(context, MjpegScreenCaptureService::class.java)
            intent.putExtra("ACTION", "STOP")
            return intent
        }
    }

    fun setTargetFPS(fps: Int) {
        this.targetFPS = fps
        this.frameIntervalMs = (1000 / fps).toLong()
        Log.d(TAG, "Target FPS set to: " + this.targetFPS)
    }

    fun setJpegQuality(jpegQuality: Int) {
        this.jpegQuality = jpegQuality
        Log.d(TAG, "Jpeg quality set to: " + this.jpegQuality)
    }

    fun setScalingFactor(scalingFactor: Int) {
        this.scalingFactor = if (scalingFactor == 50) 2 else 4
        Log.d(TAG, "Scaling factor set to: " + this.scalingFactor)
    }

    private fun isStartCommand(intent: Intent): Boolean {
        return intent.hasExtra("RESULT_CODE") && intent.hasExtra("DATA")
                && intent.hasExtra("ACTION") && intent.getStringExtra("ACTION") == "START"
    }

    private fun isStopCommand(intent: Intent): Boolean {
        return intent.hasExtra("ACTION") && intent.getStringExtra("ACTION") == "STOP"
    }

    private fun getVirtualDisplayFlags(): Int {
        return DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
    }

    private fun imageToBitmap(image: Image): Bitmap {
        // Use actual image dimensions instead of stored mWidth/mHeight
        // This handles timing issues during rotation
        val imageWidth = image.width
        val imageHeight = image.height

        // Retrieve the image planes from the image object
        val planes = image.planes
        // Extract the pixel data from the first plane by calling getBuffer() on the first element of the planes array
        // As far as I've observed the images we receive have only 1 plane
        val buffer = planes[0].buffer
        // Get distance between adjacent pixels in the same row
        val pixelStride = planes[0].pixelStride
        // distance between adjacent rows in bytes
        val rowStride = planes[0].rowStride
        // Calculate the padding between rows, which is the difference between rowStride and pixelStride multiplied by the image width
        val rowPadding = rowStride - pixelStride * imageWidth

        // Calculate the initial bitmap width to create a valid image
        val newWidth = imageWidth + (rowPadding / pixelStride)

        // Create the initial bitmap object
        // And copy into it the pixels from the buffer declared above
        var bitmap = Bitmap.createBitmap(newWidth, imageHeight, Bitmap.Config.ARGB_4444)
        bitmap.copyPixelsFromBuffer(buffer)

        // Close the Image to free up memory
        image.close()

        // We reinitialize the bitmap object using the original bitmap
        // but with the display width and height
        // essentially cropping it properly
        bitmap = Bitmap.createBitmap(bitmap, 0, 0, imageWidth, imageHeight)

        return bitmap
    }

    var imageQueue: BlockingQueue<Bitmap> = LinkedBlockingDeque(3)

    inner class ImageConsumer : Runnable {
        override fun run() {
            while (isConsumerRunning) {
                try {
                    // Take the next image from the queue (this will block if the queue is empty)
                    val bitmap: Bitmap = imageQueue.take()

                    val outputStream = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, outputStream)
                    bitmap.recycle()

                    val compressedImage = outputStream.toByteArray()
                    server?.broadcast(compressedImage)

                    // Wait for the frame interval before capturing the next frame
                    Thread.sleep(frameIntervalMs)
                } catch (e: InterruptedException) {
                    // Handle interruption or exit the loop
                    break
                }
            }
        }
    }

    inner class ImageAvailableListener : OnImageAvailableListener {
        override fun onImageAvailable(reader: ImageReader) {
            var bitmap: Bitmap? = null
            // Get the latest image from the image reader
            try {
                mImageReader?.acquireLatestImage().use { image ->
                    if (image != null) {
                        bitmap = imageToBitmap(image)
                        imageQueue.put(bitmap)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // The MediaProjection.Callback class is a callback interface for receiving updates about changes to a media projection.
    // this code ensures that resources used by the media projection are properly released when the media projection stops, which helps to avoid memory leaks and other issues.
    inner class MediaProjectionStopCallback : MediaProjection.Callback() {
        // onStop is called when the MediaProjection stops
        override fun onStop() {
            mHandler?.post(Runnable {
                // Release virtual display, image reader and orientation change callback
                if (mVirtualDisplay != null) mVirtualDisplay?.release()
                if (mImageReader != null) mImageReader?.setOnImageAvailableListener(null, null)
                // Unregister the MediaProjectionStopCallback object (this) from the media projection.
                // This ensures that the onStop() method is not called again if the media projection is stopped again in the future.
                mMediaProjection?.unregisterCallback(this@MediaProjectionStopCallback)
            })
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()

        // Start capture handling thread
        object : Thread() {
            override fun run() {
                Looper.prepare()
                mHandler = Handler()
                Looper.loop()
            }
        }.start()
    }

    override fun onDestroy() {
        // Stop orientation monitoring
        stopOrientationMonitoring()

        // Stop the consumer thread
        isConsumerRunning = false
        imageConsumerThread?.interrupt()
        imageConsumerThread = null

        super.onDestroy()
    }

    // The onStartCommand() method is called by the Android system when the service is started
    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        if (isStartCommand(intent)) {
            // Create notification
            val notification = getNotification(this)
            // Start it in the foreground
            startForeground(notification.first, notification.second)
            // Start projection
            val resultCode = intent.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED)
            val data = intent.getParcelableExtra<Intent>("DATA")
            startProjection(resultCode, data)
        } else if (isStopCommand(intent)) {
            stopProjection()
            stopSelf()
        } else {
            stopSelf()
        }

        return Service.START_NOT_STICKY
    }

    // Set up a media projection by obtaining a MediaProjection object and creating a virtual display
    private fun startProjection(resultCode: Int, data: Intent?) {
        // Get a reference to the MediaProjectionManager
        val mpManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        // If mMediaProjection is null we obtain it from the MediaProjectionManager
        if (mMediaProjection == null) {
            mMediaProjection = mpManager.getMediaProjection(resultCode, data!!)
            if (mMediaProjection != null) {
                // Register media projection stop callback
                mMediaProjection!!.registerCallback(MediaProjectionStopCallback(), mHandler)

                // If MediaProjection object is successfully obtained we create a virtual display
                val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                // Get the actual display
                mDisplay = windowManager.defaultDisplay
                createVirtualDisplay()
            }
        }
    }

    private fun stopProjection() {
        if (mHandler != null) {
            mHandler!!.post {
                if (mMediaProjection != null) {
                    mMediaProjection!!.stop()
                }
            }
        }
    }

    @SuppressLint("WrongConstant")
    private fun createVirtualDisplay() {
        // Close old ImageReader if it exists
        mImageReader?.close()
        mImageReader = null

        // We get the real display metrics on the display object
        val metrics = DisplayMetrics()
        mDisplay!!.getRealMetrics(metrics)
        // Set up the width and height for the image reader to be half of the real display metrics
        // This significantly increases the FPS even with JPEG quality of 100
        // Instead of rescaling bitmaps which reduces quality even further
        val metricsWidth = metrics.widthPixels
        val metricsHeight = metrics.heightPixels
        mDensity = metrics.densityDpi

        mWidth = metricsWidth / scalingFactor
        mHeight = metricsHeight / scalingFactor

        Log.d(TAG, "Creating virtual display: ${mWidth}x${mHeight}, metrics: ${metricsWidth}x${metricsHeight}")

        // Create an ImageReader object with the proper display dimensions and PixelFormat
        // Couldn't get it to work with any other PixelFormat than RGBA
        mImageReader = ImageReader.newInstance(mWidth, mHeight, PixelFormat.RGBA_8888, 2)
        // Create the VirtualDisplay object with all properties we have obtained until now
        mVirtualDisplay = mMediaProjection!!.createVirtualDisplay(
            "screencapture",
            mWidth,
            mHeight,
            mDensity,
            getVirtualDisplayFlags(),
            mImageReader!!.surface,
            null,
            mHandler
        )

        // Only start the consumer thread once
        if (!isConsumerRunning) {
            isConsumerRunning = true
            imageConsumerThread = Thread(ImageConsumer())
            imageConsumerThread?.start()
        }

        // Set an ImageAvailableListener on the ImageReader object
        mImageReader!!.setOnImageAvailableListener(
            ImageAvailableListener(),
            mHandler
        )

        // Start orientation monitoring (only once)
        if (!monitoringOrientation) {
            startOrientationMonitoring()
        }
    }

    /**
     * Start orientation monitoring using polling approach.
     * Polls display metrics every 500ms and resizes virtual display if dimensions change.
     * This is more reliable than DisplayListener on Android 14+.
     */
    private fun startOrientationMonitoring() {
        val initialMetrics = DisplayMetrics()
        mDisplay!!.getRealMetrics(initialMetrics)
        var lastWidth = initialMetrics.widthPixels
        var lastHeight = initialMetrics.heightPixels

        monitoringOrientation = true
        orientationMonitorThread = Thread {
            Log.d(TAG, "Orientation monitoring started (polling every 500ms)")

            while (monitoringOrientation) {
                try {
                    Thread.sleep(500)  // Poll every 500ms

                    val metrics = DisplayMetrics()
                    mDisplay?.getRealMetrics(metrics)
                    val currentWidth = metrics.widthPixels
                    val currentHeight = metrics.heightPixels

                    // Check if dimensions changed (rotation occurred)
                    if (currentWidth != lastWidth || currentHeight != lastHeight) {
                        Log.d(TAG, "Display size changed: ${lastWidth}x${lastHeight} -> ${currentWidth}x${currentHeight}")

                        lastWidth = currentWidth
                        lastHeight = currentHeight

                        // Clear the image queue to avoid processing stale images
                        imageQueue.clear()

                        // Resize virtual display instead of recreating (required for Android 16+)
                        mHandler?.post {
                            try {
                                resizeVirtualDisplay(currentWidth, currentHeight)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error resizing virtual display", e)
                            }
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
     * Resize the virtual display for orientation change.
     * Android 16+ doesn't allow recreating VirtualDisplay on the same MediaProjection,
     * so we resize the existing one and update its surface.
     */
    private fun resizeVirtualDisplay(metricsWidth: Int, metricsHeight: Int) {
        val newWidth = metricsWidth / scalingFactor
        val newHeight = metricsHeight / scalingFactor

        Log.d(TAG, "Resizing virtual display: ${mWidth}x${mHeight} -> ${newWidth}x${newHeight}")

        // Store old ImageReader to close after switching
        val oldImageReader = mImageReader

        // Remove listener from old ImageReader
        oldImageReader?.setOnImageAvailableListener(null, null)

        // Create new ImageReader with new dimensions
        mImageReader = ImageReader.newInstance(newWidth, newHeight, PixelFormat.RGBA_8888, 2)

        // Update stored dimensions
        mWidth = newWidth
        mHeight = newHeight

        // Resize the virtual display and update its surface
        mVirtualDisplay?.resize(newWidth, newHeight, mDensity)
        mVirtualDisplay?.surface = mImageReader!!.surface

        // Set listener on new ImageReader
        mImageReader!!.setOnImageAvailableListener(
            ImageAvailableListener(),
            mHandler
        )

        // Close old ImageReader
        oldImageReader?.close()

        Log.d(TAG, "Virtual display resized successfully")
    }

    /**
     * Stop orientation monitoring.
     */
    private fun stopOrientationMonitoring() {
        monitoringOrientation = false
        orientationMonitorThread?.interrupt()
        orientationMonitorThread = null
    }
}