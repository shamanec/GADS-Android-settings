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
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import com.gads.settings.streaming.NotificationUtils.getNotification
import java.io.ByteArrayOutputStream

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
    private var mMediaCodec: MediaCodec? = null
    private var mInputSurface: Surface? = null
    private var mHandler: Handler? = null
    private var mDisplay: Display? = null
    private var mVirtualDisplay: VirtualDisplay? = null
    private var mDensity = 0
    private var mWidth = 0
    private var mHeight = 0
    private var mRotation = 0

    private var targetFPS = 40
    private var bitrate = 2000000 // 2 Mbps default
    private var scalingFactor = 2
    private var iFrameInterval = 1 // Keyframe every 2 seconds
    private var configurationChangeReceiver: BroadcastReceiver? = null

    private var encoderThread: HandlerThread? = null
    private var encoderHandler: Handler? = null
    @Volatile
    private var isEncoding = false
    private var preferredEncoderName: String? = null
    private var encoderAttempts = 0
    private val failedEncoders = mutableSetOf<String>()

    var server: H264WebsocketServer? = null

    val TAG: String = "GADS H264"

    // SPS/PPS (Sequence Parameter Set / Picture Parameter Set) are H.264 configuration frames
    // that decoders need before they can decode video frames. We cache them to send to new clients.
    // Reference: https://developer.android.com/reference/android/media/MediaCodec#CSD
    private var cachedSPS: ByteArray? = null
    private var cachedPPS: ByteArray? = null

    // Frame counter for rate limiting output to target FPS
    // VirtualDisplay captures at screen refresh rate (60Hz+), but we only want to send targetFPS
    private var frameCounter = 0L
    private var lastBroadcastTime = 0L

    init {
        Log.d(TAG, "Service init block started")
        try {
            server = H264WebsocketServer(1991, this)
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
//        this.targetFPS = fps
        Log.d(TAG, "Target FPS set to: $targetFPS")
    }

    fun setBitrate(bitrate: Int) {
        this.bitrate = bitrate
        Log.d(TAG, "Bitrate set to: $bitrate")
    }

    fun setScalingFactor(scalingFactor: Int) {
        this.scalingFactor = if (scalingFactor == 50) 2 else 4
        Log.d(TAG, "Scaling factor set to: $scalingFactor")
    }

    fun setIFrameInterval(interval: Int) {
        this.iFrameInterval = interval
        Log.d(TAG, "I-frame interval set to: $iFrameInterval seconds")
    }

    fun getCachedConfigFrames(): Pair<ByteArray?, ByteArray?> {
        return Pair(cachedSPS, cachedPPS)
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

    // Continuously reads encoded H.264 NALUs from MediaCodec output buffers and sends them to WebSocket clients.
    // This thread runs until isEncoding is set to false.
    // Reference: https://developer.android.com/reference/android/media/MediaCodec#asynchronous-processing-using-buffers
    private fun startEncoderOutputThread() {
        isEncoding = true
        Thread {
            val bufferInfo = MediaCodec.BufferInfo()
            val timeoutUs = 10000L // 10ms timeout for dequeueOutputBuffer
            var consecutiveErrors = 0
            val maxConsecutiveErrors = 5

            Log.d(TAG, "Encoder output thread started")

            while (isEncoding) {
                try {
                    // Try to get an output buffer from the encoder with encoded data
                    val outputBufferIndex = mMediaCodec?.dequeueOutputBuffer(bufferInfo, timeoutUs) ?: -1

                    // Reset error counter on successful dequeue
                    if (outputBufferIndex >= 0 || outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        consecutiveErrors = 0
                    }

                    when {
                        // MediaCodec signals format changed, which contains SPS/PPS in the codec-specific data (CSD)
                        outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val newFormat = mMediaCodec?.outputFormat
                            // csd-0 contains SPS (Sequence Parameter Set)
                            val sps = newFormat?.getByteBuffer("csd-0")
                            // csd-1 contains PPS (Picture Parameter Set)
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

                            // Broadcast SPS/PPS to all connected clients
                            if (cachedSPS != null && cachedPPS != null) {
                                sendConfigFrames()
                            }
                        }

                        // Valid output buffer with encoded frame data
                        outputBufferIndex >= 0 -> {
                            val encodedData = mMediaCodec?.getOutputBuffer(outputBufferIndex)

                            // Reset attempt counter on successful frame output
                            if (encoderAttempts > 0) {
                                encoderAttempts = 0
                            }

                            if (encodedData != null && bufferInfo.size > 0) {
                                // Position the buffer to read the encoded frame data
                                encodedData.position(bufferInfo.offset)
                                encodedData.limit(bufferInfo.offset + bufferInfo.size)

                                val data = ByteArray(bufferInfo.size)
                                encodedData.get(data)

                                // Check if this is a keyframe (I-frame)
                                val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                                if (data.isNotEmpty()) {
                                    // For I-frames, prepend SPS/PPS for robustness
                                    // This allows clients to start decoding at any I-frame without timing issues
                                    if (isKeyFrame && cachedSPS != null && cachedPPS != null) {
                                        val outputStream = ByteArrayOutputStream()
                                        // Write SPS with start code
                                        outputStream.write(byteArrayOf(0x00, 0x00, 0x00, 0x01))
                                        outputStream.write(cachedSPS)
                                        // Write PPS with start code
                                        outputStream.write(byteArrayOf(0x00, 0x00, 0x00, 0x01))
                                        outputStream.write(cachedPPS)
                                        // Write the I-frame data
                                        outputStream.write(data)

                                        server?.broadcast(outputStream.toByteArray())
                                        Log.d(TAG, "Sent I-frame with SPS/PPS prepended (${outputStream.size()} bytes)")
                                    } else {
                                        // P-frames sent as-is
                                        server?.broadcast(data)
                                    }
                                }
                            }

                            // Return the buffer to the codec for reuse
                            mMediaCodec?.releaseOutputBuffer(outputBufferIndex, false)
                        }

                        outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            // No output available yet, will try again in next iteration
                        }
                    }

                } catch (e: Exception) {
                    consecutiveErrors++
                    Log.e(TAG, "Error in encoder output thread (${consecutiveErrors}/${maxConsecutiveErrors}): ${e.message}")

                    // If we get too many consecutive errors, the encoder has crashed
                    if (consecutiveErrors >= maxConsecutiveErrors) {
                        Log.e(TAG, "Encoder crashed after $consecutiveErrors consecutive errors")

                        // Mark current encoder as failed
                        preferredEncoderName?.let {
                            Log.w(TAG, "Marking encoder as failed: $it")
                            failedEncoders.add(it)
                        }

                        // If we haven't exhausted all encoder attempts, try a different one
                        if (encoderAttempts < 2) {
                            Log.w(TAG, "Attempting to restart with alternative encoder (attempt ${encoderAttempts + 1})")
                            preferredEncoderName = null // Clear to force trying next encoder
                            encoderAttempts++

                            // Restart with next encoder in the list
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

    // Sends SPS and PPS configuration frames to all connected WebSocket clients.
    // These frames must be sent before any video frames for decoders to work properly.
    // Frames are formatted with Annex-B start codes (0x00 0x00 0x00 0x01) before each NALU.
    private fun sendConfigFrames() {
        if (cachedSPS == null || cachedPPS == null) return

        val outputStream = ByteArrayOutputStream()

        try {
            // Annex-B format requires 4-byte start code (0x00 0x00 0x00 0x01) before each NALU
            outputStream.write(byteArrayOf(0x00, 0x00, 0x00, 0x01))
            outputStream.write(cachedSPS)

            outputStream.write(byteArrayOf(0x00, 0x00, 0x00, 0x01))
            outputStream.write(cachedPPS)

            val configData = outputStream.toByteArray()
            server?.broadcast(configData)

            Log.d(TAG, "Sent SPS/PPS config frames: ${configData.size} bytes")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending config frames: ${e.message}")
            e.printStackTrace()
        }
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
            }
        }
    }

    // Stops the screen capture and releases all resources.
    private fun stopProjection() {
        encoderHandler?.post {
            releaseEncoder()
            mMediaProjection?.stop()
        }
    }

    // Restarts the encoder, typically called when orientation changes to adjust resolution.
    private fun restartEncoder() {
        encoderHandler?.post {
            releaseEncoder()
            createVirtualDisplay()
        }
    }

    // Selects the best available H.264 encoder based on device and previous failures.
    // Tries hardware encoder first, then falls back to software encoder if hardware fails.
    // Known problematic devices (Xiaomi on Android 15+) start with software encoder.
    private fun selectEncoder(): MediaCodec? {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val isProblematicDevice = manufacturer.contains("xiaomi") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM

        // If we've already found a working encoder and it hasn't failed, use it
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
            // Start with software encoder for known problematic devices
            Log.d(TAG, "Detected problematic device (Xiaomi Android 15+), preferring software encoder")
            listOf("c2.android.avc.encoder", "OMX.google.h264.encoder", null)
        } else {
            // Try hardware first, then software fallback for other devices
            listOf(null, "c2.android.avc.encoder", "OMX.google.h264.encoder")
        }

        for (encoderName in encodersToTry) {
            // Skip encoders that have previously failed
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
        mWidth = (metricsWidth / scalingFactor / 16) * 16
        mHeight = (metricsHeight / scalingFactor / 16) * 16
        mDensity = metrics.densityDpi

        val orientation = if (metricsWidth > metricsHeight) "LANDSCAPE" else "PORTRAIT"
        Log.d(TAG, "Creating encoder with resolution: ${mWidth}x${mHeight} @ ${targetFPS}fps, bitrate: $bitrate")
        Log.d(TAG, "Display metrics: ${metricsWidth}x${metricsHeight} ($orientation), density: $mDensity")

        // Configure MediaFormat for H.264 video encoding
        // MIMETYPE_VIDEO_AVC is H.264/AVC codec
        // Reference: https://developer.android.com/reference/android/media/MediaFormat
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, mWidth, mHeight)

        // COLOR_FormatSurface means we're feeding video data via a Surface (hardware accelerated)
        // instead of manually providing YUV buffers
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)

        // Bitrate in bits per second - controls output quality and size
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)

        // Target frames per second
        format.setInteger(MediaFormat.KEY_FRAME_RATE, targetFPS)

        // I-frame (keyframe) interval in seconds - lower means more keyframes (better seeking, higher bitrate)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval)

        // KEY_REPEAT_PREVIOUS_FRAME_AFTER causes crashes on some devices (Xiaomi/Android 15)
        // Only set it if Android version is less than 15
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            format.setLong(
                MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER,
                1_000_000L / targetFPS.toLong() // microseconds
            )
        }

        try {
            // Create H.264 encoder using smart selection
            // Reference: https://developer.android.com/reference/android/media/MediaCodec
            mMediaCodec = selectEncoder()
            mMediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

            // Create input surface - the virtual display will render to this surface
            // MediaCodec will encode whatever is drawn to this surface
            mInputSurface = mMediaCodec?.createInputSurface()

            // Start the encoder
            mMediaCodec?.start()

            // Start background thread to read encoded frames from MediaCodec output buffers
            startEncoderOutputThread()

            // Create virtual display that renders the screen capture to the encoder's input surface
            // Reference: https://developer.android.com/reference/android/media/projection/MediaProjection#createVirtualDisplay
            mVirtualDisplay = mMediaProjection?.createVirtualDisplay(
                "H264ScreenCapture",
                mWidth,
                mHeight,
                mDensity,
                getVirtualDisplayFlags(),
                mInputSurface, // The encoder surface - screen content is rendered here and encoded
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
            // Stop the encoder output thread
            isEncoding = false

            // Release the virtual display that captures the screen
            mVirtualDisplay?.release()
            mVirtualDisplay = null

            // Stop and release the MediaCodec encoder
            mMediaCodec?.stop()
            mMediaCodec?.release()
            mMediaCodec = null

            // Release the input surface
            mInputSurface?.release()
            mInputSurface = null

            Log.d(TAG, "Encoder released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing encoder: ${e.message}")
            e.printStackTrace()
        }
    }
}