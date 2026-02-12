package com.gads.settings.streaming

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.WindowManager
import com.gads.settings.audio.AudioCapturer
import com.gads.settings.audio.AudioWebSocketSender
import com.gads.settings.server.H264Encoder
import com.gads.settings.streaming.NotificationUtils.getNotification

// Service that captures the device screen using MediaProjection and encodes it to H.264 video in real-time.
// The encoded H.264 NALUs are streamed over WebSocket in Annex-B format for consumption by Pion WebRTC.
//
// Flow: MediaProjection API -> VirtualDisplay -> Surface -> MediaCodec (H.264) -> WebSocket -> Pion
//
// Key features:
// - Hardware-accelerated H.264 encoding using MediaCodec with Surface input
// - Outputs Annex-B formatted NALUs with start codes (0x00 0x00 0x00 0x01)
// - Automatically sends SPS/PPS config frames to new WebSocket clients
// - Configurable FPS, bitrate, resolution scaling, and I-frame interval
// - Handles orientation changes by restarting encoder with new dimensions
//
// References:
// - MediaProjection: https://developer.android.com/reference/android/media/projection/MediaProjection
// - MediaCodec: https://developer.android.com/reference/android/media/MediaCodec
// - H.264/AVC: https://developer.android.com/reference/android/media/MediaFormat#MIMETYPE_VIDEO_AVC
class H264ScreenCaptureService: Service() {
    private var mMediaProjection: MediaProjection? = null
    private var encoder: H264Encoder? = null
    private var mHandler: Handler? = null
    private var mDisplay: Display? = null
    private var mVirtualDisplay: VirtualDisplay? = null
    private var mDensity = 0
    private var mWidth = 0
    private var mHeight = 0
    private var mRotation = 0

    private var targetFPS = 30
    private var bitrate = 4000000 // 4 Mbps default
    private var scalingFactor = 2
    private var iFrameInterval = 1 // Keyframe every 2 seconds
    private var configurationChangeReceiver: BroadcastReceiver? = null

    private var encoderThread: HandlerThread? = null
    private var encoderHandler: Handler? = null

    var server: H264WebSocketServer? = null

    // Audio capture members
    private var audioCapturer: AudioCapturer? = null
    private var audioSender: AudioWebSocketSender? = null

    val TAG: String = "GADS H264"

    init {
        Log.d(TAG, "Service init block started")
        try {
            server = H264WebSocketServer(1991, this)
            server?.isReuseAddr = true
            server?.start()
            Log.d(TAG, "WebSocket server started successfully on port 1991")
        } catch (e: Exception) {
            Log.e(TAG, "CRITICAL: Failed to start WebSocket server in init: ${e.message}", e)
        }
    }

    companion object {
        fun getStartIntent(context: Context?, resultCode: Int, data: Intent?): Intent {
            val intent = Intent(context, H264ScreenCaptureService::class.java)
            intent.putExtra("ACTION", "START")
            intent.putExtra("RESULT_CODE", resultCode)
            intent.putExtra("DATA", data)
            return intent
        }

        fun getStopIntent(context: Context?): Intent {
            val intent = Intent(context, H264ScreenCaptureService::class.java)
            intent.putExtra("ACTION", "STOP")
            return intent
        }
    }

    fun setTargetFPS(fps: Int) {
        this.targetFPS = fps
        Log.d(TAG, "Target FPS set to: $targetFPS")
        restartEncoder()
    }

    fun setBitrate(bitrate: Int) {
        this.bitrate = bitrate
        Log.d(TAG, "Bitrate set to: $bitrate")
    }

    fun setScalingFactor(scalingFactor: Int) {
//        this.scalingFactor = if (scalingFactor == 50) 2 else 4
        Log.d(TAG, "Scaling factor set to: $scalingFactor")
    }

    fun setIFrameInterval(interval: Int) {
        this.iFrameInterval = interval
        Log.d(TAG, "I-frame interval set to: $iFrameInterval seconds")
    }

    fun getCachedConfigFrames(): Pair<ByteArray?, ByteArray?> {
        return encoder?.getCachedConfigFrames() ?: Pair(null, null)
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


    inner class MediaProjectionStopCallback : MediaProjection.Callback() {
        override fun onStop() {
            encoderHandler?.post {
                releaseEncoder()
                mMediaProjection?.unregisterCallback(this@MediaProjectionStopCallback)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate() called")

        // Register broadcast receiver for system configuration changes (including orientation)
        // Listen for Intent.ACTION_CONFIGURATION_CHANGED which is sent by the system
        configurationChangeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (Intent.ACTION_CONFIGURATION_CHANGED == intent.action) {
                    Log.d(TAG, "System configuration change broadcast received!")

                    // Get FRESH display to check current rotation, not cached mDisplay
                    val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    val currentDisplay = windowManager.defaultDisplay
                    val rotation = currentDisplay?.rotation

                    Log.d(TAG, "Previous rotation: $mRotation, Current rotation: $rotation")

                    if (rotation != null && rotation != mRotation) {
                        Log.d(TAG, "Rotation changed! Restarting encoder...")
                        mRotation = rotation
                        try {
                            restartEncoder()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error restarting encoder: ${e.message}")
                            e.printStackTrace()
                        }
                    } else {
                        Log.d(TAG, "Rotation unchanged, ignoring")
                    }
                }
            }
        }

        val orientationChangeFilter = IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                registerReceiver(
                    configurationChangeReceiver,
                    orientationChangeFilter,
                    Context.RECEIVER_NOT_EXPORTED
                )
                Log.d(TAG, "Receiver registered with RECEIVER_NOT_EXPORTED (API 26+).")
            } else {
                registerReceiver(configurationChangeReceiver, orientationChangeFilter)
                Log.d(TAG, "Receiver registered without flags (API < 26).")
            }
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Error registering receiver: ${e.message}")
        }

        // Create background thread for encoder operations
        // Using HandlerThread ensures encoder operations run off the main thread
        // Reference: https://developer.android.com/reference/android/os/HandlerThread
        encoderThread = HandlerThread("H264EncoderThread")
        encoderThread?.start()
        encoderHandler = Handler(encoderThread!!.looper)

        // Create separate handler thread for MediaProjection callbacks
        val handlerThread = HandlerThread("MediaProjectionHandler")
        handlerThread.start()
        mHandler = Handler(handlerThread.looper)
    }

    override fun onDestroy() {
        unregisterReceiver(configurationChangeReceiver)
        encoderThread?.quitSafely()
        super.onDestroy()
    }

    // The onStartCommand() method is called by the Android system when the service is started
    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand() called")

        if (isStartCommand(intent)) {
            Log.d(TAG, "START command received")
            try {
                // Create notification
                val notification = getNotification(this)
                Log.d(TAG, "Notification created")
                // Start it in the foreground
                startForeground(notification.first, notification.second)
                Log.d(TAG, "Service started in foreground")
                // Start projection
                val resultCode = intent.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED)
                val data = intent.getParcelableExtra<Intent>("DATA")
                Log.d(TAG, "Starting projection with resultCode: $resultCode")
                startProjection(resultCode, data)
            } catch (e: Exception) {
                Log.e(TAG, "Error in onStartCommand: ${e.message}", e)
            }
        } else if (isStopCommand(intent)) {
            Log.d(TAG, "STOP command received")
            stopProjection()
            stopSelf()
        } else {
            Log.w(TAG, "Unknown command, stopping service")
            stopSelf()
        }

        return Service.START_NOT_STICKY
    }

    // Starts audio capture and WebSocket sender
    private fun startAudioCapture() {
        try {
            Log.i(TAG, "Starting audio capture")

            // Start audio WebSocket server
            audioSender = AudioWebSocketSender(1992)
            audioSender?.start()

            // Start audio capturer
            audioCapturer = AudioCapturer()
            audioCapturer?.startCapture { pcmData, pts ->
                // Send audio frame to provider via WebSocket
                audioSender?.sendAudioFrame(pcmData, pts)
            }

            Log.i(TAG, "Audio capture started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio capture", e)
            stopAudioCapture()
        }
    }

    // Stops audio capture and releases resources
    private fun stopAudioCapture() {
        try {
            Log.i(TAG, "Stopping audio capture")

            audioCapturer?.stopCapture()
            audioCapturer = null

            audioSender?.stop()
            audioSender = null

            Log.i(TAG, "Audio capture stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio capture", e)
        }
    }

    // Initializes MediaProjection for screen capture and starts the encoding pipeline.
    // This is called when the service receives a START command with MediaProjection permission.
    // Reference: https://developer.android.com/reference/android/media/projection/MediaProjection
    private fun startProjection(resultCode: Int, data: Intent?) {
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        if (mMediaProjection == null) {
            // Get MediaProjection using the result from the permission dialog
            mMediaProjection = mpManager.getMediaProjection(resultCode, data!!)
            if (mMediaProjection != null) {
                // Register callback to handle when projection stops
                mMediaProjection?.registerCallback(MediaProjectionStopCallback(), encoderHandler)

                // Get the default display to capture
                val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                mDisplay = windowManager.defaultDisplay

                // Create virtual display and start encoding
                createVirtualDisplay()

                // Start audio capture and WebSocket sender
                startAudioCapture()
            }
        }
    }

    // Stops the screen capture and releases all resources.
    private fun stopProjection() {
        encoderHandler?.post {
            releaseEncoder()
            mMediaProjection?.stop()
        }
        // Stop audio capture
        stopAudioCapture()
    }

    // Restarts the encoder, typically called when orientation changes to adjust resolution.
    private fun restartEncoder() {
        encoderHandler?.post {
            releaseEncoder()
            createVirtualDisplay()
        }
    }

    // Creates and configures the H.264 encoder and virtual display for screen capture.
    // Flow: MediaProjection -> VirtualDisplay -> Surface -> MediaCodec (H.264 encoder) -> WebSocket
    // Reference: https://developer.android.com/reference/android/media/MediaCodec#using-an-input-surface
    @SuppressLint("WrongConstant")
    private fun createVirtualDisplay() {
        // Refresh display reference to get current orientation/dimensions
        // The Display object doesn't auto-update on rotation, so we must re-fetch it
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        mDisplay = windowManager.defaultDisplay

        // Get the real display dimensions for current orientation
        val metrics = DisplayMetrics()
        mDisplay?.getRealMetrics(metrics)

        val metricsWidth = metrics.widthPixels
        val metricsHeight = metrics.heightPixels

        // Scale down resolution to reduce encoding load and bandwidth
        // Align to multiples of 16 for hardware encoder compatibility
        val targetWidth = (metricsWidth / scalingFactor / 16) * 16
        val targetHeight = (metricsHeight / scalingFactor / 16) * 16

        mWidth = targetWidth
        mHeight = targetHeight
        mDensity = metrics.densityDpi

        val orientation = if (metricsWidth > metricsHeight) "LANDSCAPE" else "PORTRAIT"
        Log.d(TAG, "Creating encoder with resolution: ${mWidth}x${mHeight} @ ${targetFPS}fps, bitrate: $bitrate")
        Log.d(TAG, "Display metrics: ${metricsWidth}x${metricsHeight} ($orientation), density: $mDensity")

        try {
            // Create H.264 encoder with callback
            encoder = H264Encoder(object : H264Encoder.FrameCallback {
                override fun onFrameEncoded(data: ByteArray, isKeyFrame: Boolean, pts: Long) {
                    server?.broadcast(data)
                }

                override fun onEncoderRestartNeeded() {
                    // Restart encoder after delay
                    encoderHandler?.postDelayed({
                        releaseEncoder()
                        createVirtualDisplay()
                    }, 500)
                }
            })

            // Start encoder and get input surface
            val inputSurface = encoder?.start(mWidth, mHeight, bitrate, targetFPS, iFrameInterval)
            if (inputSurface == null) {
                throw RuntimeException("Failed to start encoder")
            }

            // Create virtual display that renders the screen capture to the encoder's input surface
            // Reference: https://developer.android.com/reference/android/media/projection/MediaProjection#createVirtualDisplay
            mVirtualDisplay = mMediaProjection?.createVirtualDisplay(
                "H264ScreenCapture",
                mWidth,
                mHeight,
                mDensity,
                getVirtualDisplayFlags(),
                inputSurface, // The encoder surface - screen content is rendered here and encoded
                null,
                encoderHandler
            )

            Log.d(TAG, "Virtual display and H.264 encoder created successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error creating encoder: ${e.message}")
            e.printStackTrace()
        }
    }

    // Stops and releases all encoding resources.
    // Must be called to prevent memory leaks when stopping capture or changing configuration.
    private fun releaseEncoder() {
        try {
            // Release the encoder
            encoder?.release()
            encoder = null

            // Release the virtual display that captures the screen
            mVirtualDisplay?.release()
            mVirtualDisplay = null

            Log.d(TAG, "Encoder released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing encoder: ${e.message}")
            e.printStackTrace()
        }
    }
}