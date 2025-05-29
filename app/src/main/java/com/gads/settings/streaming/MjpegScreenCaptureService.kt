package com.gads.settings.streaming

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.ImageReader.OnImageAvailableListener
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.WindowManager
import com.gads.settings.streaming.NotificationConstants.ORIENTATION_CHANGED_ACTION
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
    private var mRotation = 0

    private var targetFPS = 15
    private var jpegQuality = 90
    private var scalingFactor = 2
    private var frameIntervalMs = (1000 / targetFPS).toLong()
    private var configurationChangeReceiver: BroadcastReceiver? = null

    var server: MjpegWebsocketServer? = null

    val TAG: String = "ScreenCaptureService"

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
        Log.d("ScreenCaptureService", "Target FPS set to: " + this.targetFPS)
    }

    fun setJpegQuality(jpegQuality: Int) {
        this.jpegQuality = jpegQuality
        Log.d("ScreenCaptureService", "Jpeg quality set to: " + this.jpegQuality)
    }

    fun setScalingFactor(scalingFactor: Int) {
        this.scalingFactor = if (scalingFactor == 50) 2 else 4
        Log.d("ScreenCaptureService", "Scaling factor set to: " + this.scalingFactor)
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
        val rowPadding = rowStride - pixelStride * mWidth

        // Calculate the initial bitmap width to create a valid image
        val newWidth = mWidth + (rowPadding / pixelStride)

        // Create the initial bitmap object
        // And copy into it the pixels from the buffer declared above
        var bitmap = Bitmap.createBitmap(newWidth, mHeight, Bitmap.Config.ARGB_4444)
        bitmap.copyPixelsFromBuffer(buffer)

        // Close the Image to free up memory
        image.close()

        // We reinitialize the bitmap object using the original bitmap
        // but with the display width and height
        // essentially cropping it properly
        bitmap = Bitmap.createBitmap(bitmap, 0, 0, mWidth, mHeight)

        return bitmap
    }

    var imageQueue: BlockingQueue<Bitmap> = LinkedBlockingDeque(3)

    inner class ImageConsumer : Runnable {
        override fun run() {
            while (true) {
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

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        super.onCreate()

        configurationChangeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (ORIENTATION_CHANGED_ACTION.equals(intent.action)) {
                    imageQueue.clear()
                    // When orientation changes get the display rotation
                    val rotation = mDisplay!!.rotation
                    // If the current rotation is different than the previous rotation
                    // Re-assign it
                    if (rotation != mRotation) {
                        mRotation = rotation
                        try {
                            // Clean up the previous virtual display if it exists
                            if (mVirtualDisplay != null) mVirtualDisplay!!.release()

                            // Start the onImageAvailableListener on the image reader
                            if (mImageReader != null) mImageReader!!.setOnImageAvailableListener(
                                null,
                                null
                            )

                            // re-create virtual display depending on device width / height
                            createVirtualDisplay()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        }

        val orientationChangeFilter: IntentFilter = IntentFilter(ORIENTATION_CHANGED_ACTION)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { // API 26+
                registerReceiver(
                    configurationChangeReceiver,
                    orientationChangeFilter,
                    Context.RECEIVER_NOT_EXPORTED
                )
                Log.d(TAG, "Receiver registered with RECEIVER_NOT_EXPORTED (API 26+).")
            } else { // API below 26
                registerReceiver(configurationChangeReceiver, orientationChangeFilter)
                Log.d(TAG, "Receiver registered without flags (API < 26).")
            }
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Error registering receiver: " + e.message)
        }

        // start capture handling thread
        object : Thread() {
            override fun run() {
                Looper.prepare()
                mHandler = Handler()
                Looper.loop()
            }
        }.start()
    }

    override fun onDestroy() {
        unregisterReceiver(configurationChangeReceiver)
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
        // We get the real display metrics on the display object
        val metrics = DisplayMetrics()
        mDisplay!!.getRealMetrics(metrics)
        // Set up the width and height for the image reader to be half of the real display metrics
        // This significantly increases the FPS even with JPEG quality of 100
        // Instead of rescaling bitmaps which reduces quality even further
        val metricsWidth = metrics.widthPixels
        val metricsHeight = metrics.heightPixels

        mWidth = metricsWidth / scalingFactor
        mHeight = metricsHeight / scalingFactor
        mDensity = metrics.densityDpi

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

        val imageConsumerThread: Thread = Thread(ImageConsumer())
        imageConsumerThread.start()

        // Set an ImageAvailableListener on the ImageReader object
        mImageReader!!.setOnImageAvailableListener(
            ImageAvailableListener(),
            mHandler
        )
    }
}