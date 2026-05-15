package com.shamanec.settings.webrtc

import com.shamanec.settings.BuildConfig

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.Surface
import org.webrtc.CapturerObserver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.ThreadUtils
import org.webrtc.VideoCapturer

/**
 * Custom ScreenCapturer with VirtualDisplay.resize() support for Android 14+.
 *
 * Key design principles (following WebRTC best practices):
 * 1. All texture/surface operations must run on SurfaceTextureHelper's handler thread
 * 2. setTextureSize() must be called BEFORE VirtualDisplay operations
 * 3. Use ThreadUtils.invokeAtFrontUninterruptibly() for thread synchronization
 * 4. Proper Surface lifecycle management to prevent leaks
 */
class ResizableScreenCapturer(
    private val mediaProjectionPermissionResultData: Intent,
    private val mediaProjectionCallback: MediaProjection.Callback,
    private val isQualcommEncoder: Boolean = false
) : VideoCapturer {

    companion object {
        private const val TAG = "${BuildConfig.LOG_PREFIX}-Capturer"
        private const val FRAME_DIMENSION_TOLERANCE = 16

        // Timing constants based on encoder type (Qualcomm needs more time for SPS/PPS)
        private const val QUALCOMM_MIN_WAIT_TIME_MS = 300L
        private const val QUALCOMM_STABLE_FRAME_COUNT = 5
        private const val DEFAULT_MIN_WAIT_TIME_MS = 150L
        private const val DEFAULT_STABLE_FRAME_COUNT = 3

        // Timeout and limits
        private const val RESIZE_TIMEOUT_MS = 2000L
        private const val MAX_DISCARDED_FRAMES = 50
    }

    /**
     * Listener for resize events from the capturer.
     * Used to propagate dimension changes to VideoSource on Android 14+.
     */
    interface ResizeListener {
        fun onCaptureResized(width: Int, height: Int)
    }

    private var resizeListener: ResizeListener? = null

    fun setResizeListener(listener: ResizeListener?) {
        this.resizeListener = listener
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var capturerObserver: CapturerObserver? = null
    private var context: Context? = null

    // Current surface reference for proper cleanup
    private var currentSurface: Surface? = null

    private var width: Int = 0
    private var height: Int = 0
    private var density: Int = 0

    @Volatile
    private var isDisposed = false

    // Lock for resize operations to prevent concurrent modifications
    private val resizeLock = Object()

    // Frame gating variables (Hybrid: Time + Stability)
    private var targetWidth: Int = 0
    private var targetHeight: Int = 0
    private var framesDiscardedDuringResize: Int = 0
    private var resizeStartTime: Long = 0
    private var consecutiveValidFrames: Int = 0

    // Dynamic timing based on encoder type (Qualcomm needs more time for SPS/PPS)
    private var currentMinWaitTime: Long = if (isQualcommEncoder) QUALCOMM_MIN_WAIT_TIME_MS else DEFAULT_MIN_WAIT_TIME_MS
    private var currentStableFrameCount: Int = if (isQualcommEncoder) QUALCOMM_STABLE_FRAME_COUNT else DEFAULT_STABLE_FRAME_COUNT

    @Volatile
    private var isResizing = false

    // Frame delivery pause flag (for encoder reconfiguration)
    @Volatile
    private var isPaused = false

    /**
     * Pause frame delivery during encoder reconfiguration.
     * This ensures the first frame sent has correct SPS/PPS headers.
     */
    fun pauseFrameDelivery() {
        isPaused = true
        Log.d(TAG, "Frame delivery PAUSED for encoder reconfig")
    }

    /**
     * Resume frame delivery after encoder reconfiguration.
     */
    fun resumeFrameDelivery() {
        isPaused = false
        Log.d(TAG, "Frame delivery RESUMED")
    }

    private fun deliverFrame(frame: org.webrtc.VideoFrame) {
        if (isPaused) return
        if (isResizing) {
            deliverFrameWithValidation(frame)
        } else {
            capturerObserver?.onFrameCaptured(frame)
        }
    }

    // Callback for Android 14+ content resize
    private val resizeCallback = object : MediaProjection.Callback() {
        override fun onCapturedContentResize(newWidth: Int, newHeight: Int) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                Log.d(TAG, "onCapturedContentResize: ${newWidth}x${newHeight}")
                // This callback is already invoked on helper.handler thread
                synchronized(resizeLock) {
                    if (!isDisposed) {
                        resizeVirtualDisplayInternal(newWidth, newHeight)
                    }
                }
            }
        }

        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            Log.d(TAG, "onCapturedContentVisibilityChanged: $isVisible")
        }

        override fun onStop() {
            Log.d(TAG, "MediaProjection stopped")
            mediaProjectionCallback.onStop()
        }
    }

    override fun initialize(
        surfaceTextureHelper: SurfaceTextureHelper,
        context: Context,
        capturerObserver: CapturerObserver
    ) {
        this.surfaceTextureHelper = surfaceTextureHelper
        this.context = context
        this.capturerObserver = capturerObserver
    }

    override fun startCapture(width: Int, height: Int, framerate: Int) {
        this.width = width
        this.height = height

        val ctx = context ?: throw IllegalStateException("Not initialized")
        val helper = surfaceTextureHelper ?: throw IllegalStateException("SurfaceTextureHelper not initialized")

        // Get display density
        val displayManager = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = android.util.DisplayMetrics()
        display?.getRealMetrics(metrics)
        density = metrics.densityDpi

        // Get MediaProjection
        val mediaProjectionManager = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mediaProjectionManager.getMediaProjection(
            Activity.RESULT_OK,
            mediaProjectionPermissionResultData
        )

        // Register callback BEFORE createVirtualDisplay (required on Android 14+)
        mediaProjection?.registerCallback(resizeCallback, helper.handler)

        // Create VirtualDisplay on SurfaceTextureHelper's thread
        ThreadUtils.invokeAtFrontUninterruptibly(helper.handler) {
            // 1. Buffer size FIRST (SYNCHRONOUS - immediate effect)
            helper.surfaceTexture.setDefaultBufferSize(width, height)

            // 2. Create Surface with correctly sized buffer
            currentSurface = Surface(helper.surfaceTexture)

            // 3. Create VirtualDisplay with matching dimensions
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ResizableScreenCapture",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                currentSurface,
                null,
                helper.handler
            )

            // 4. Update helper internal variables (async OK now)
            helper.setTextureSize(width, height)
        }

        helper.startListening { frame -> deliverFrame(frame) }

        capturerObserver?.onCapturerStarted(virtualDisplay != null)
        Log.d(TAG, "Capture started: ${width}x${height} @ density=$density")
    }

    /**
     * v9.1: Hybrid Frame Gating (Time + Stability)
     *
     * Validates frame dimensions AND timing before delivery to encoder.
     * Requires BOTH conditions to be met:
     * 1. Frame dimensions match target (within tolerance)
     * 2. Minimum time elapsed (80ms) AND 3 consecutive valid frames
     *
     * This adaptive approach works on all devices:
     * - Fast devices: ~100ms latency (80ms + 3 frames)
     * - Slow devices: Adapts automatically (waits for encoder stability)
     *
     * Timeout protection (1.5s / 50 frames) prevents stream from freezing.
     */
    private fun deliverFrameWithValidation(frame: org.webrtc.VideoFrame) {
        synchronized(resizeLock) {

            val elapsed = System.currentTimeMillis() - resizeStartTime

            // TIMEOUT: Release gate but DON'T deliver potentially corrupted frame
            if (elapsed > RESIZE_TIMEOUT_MS || framesDiscardedDuringResize >= MAX_DISCARDED_FRAMES) {
                Log.w(TAG, "Frame gating TIMEOUT after ${elapsed}ms, discarded=$framesDiscardedDuringResize - releasing gate")
                isResizing = false
                consecutiveValidFrames = 0
                // DON'T deliver this frame (may be corrupted)
                // Next frames will be delivered normally
                return
            }

            val buffer = frame.buffer
            val frameWidth = buffer.width
            val frameHeight = buffer.height

            val widthOk = kotlin.math.abs(frameWidth - targetWidth) <= FRAME_DIMENSION_TOLERANCE
            val heightOk = kotlin.math.abs(frameHeight - targetHeight) <= FRAME_DIMENSION_TOLERANCE

            if (widthOk && heightOk) {
                consecutiveValidFrames++

                // Require BOTH - minimum time AND stability (using dynamic values for QHD+)
                val timeOk = elapsed >= currentMinWaitTime
                val stable = consecutiveValidFrames >= currentStableFrameCount

                if (timeOk && stable) {
                    Log.d(TAG, "Frame gating complete: ${frameWidth}x${frameHeight} after ${elapsed}ms, $consecutiveValidFrames stable frames")
                    isResizing = false
                    consecutiveValidFrames = 0
                    capturerObserver?.onFrameCaptured(frame)
                } else {
                    // Still waiting for stability or minimum time
                    if (consecutiveValidFrames <= currentStableFrameCount) {
                        Log.d(TAG, "Waiting: elapsed=${elapsed}ms, validFrames=$consecutiveValidFrames (need ${currentMinWaitTime}ms + $currentStableFrameCount frames)")
                    }
                }
            } else {
                // Invalid frame - RESET stability counter
                consecutiveValidFrames = 0
                framesDiscardedDuringResize++
                if (framesDiscardedDuringResize <= 5) {
                    Log.d(TAG, "Discarding frame #$framesDiscardedDuringResize: ${frameWidth}x${frameHeight} vs ${targetWidth}x${targetHeight}")
                }
            }
        }
    }

    /**
     * Resize the VirtualDisplay without recreating it.
     *
     * v9.1 FIX: Hybrid Frame Gating (Time + Stability)
     *
     * Root cause: Encoder reconfiguration takes 50-100ms (async). Frames arriving during
     * this window have correct dimensions but encoder expects OLD dimensions,
     * causing compression artifacts (50% horizontal squish).
     *
     * Solution: Discard frames until BOTH conditions are met:
     * 1. Frame dimensions match target (±16 pixels tolerance)
     * 2. Minimum time elapsed (80ms) AND 3 consecutive valid frames
     *
     * This adaptive approach works on all devices - slow devices automatically
     * wait longer for encoder stability.
     */
    private fun resizeVirtualDisplayInternal(newWidth: Int, newHeight: Int) {
        if (newWidth == width && newHeight == height) {
            Log.d(TAG, "Size unchanged, skipping resize")
            return
        }

        val vd = virtualDisplay ?: run {
            Log.w(TAG, "VirtualDisplay is null, cannot resize")
            return
        }
        val helper = surfaceTextureHelper ?: run {
            Log.w(TAG, "SurfaceTextureHelper is null, cannot resize")
            return
        }

        Log.d(TAG, "Resizing: ${width}x${height} -> ${newWidth}x${newHeight}")

        try {
            // 1. FIRST: Setup frame gating (BEFORE any changes)
            synchronized(resizeLock) {
                targetWidth = newWidth
                targetHeight = newHeight
                framesDiscardedDuringResize = 0
                consecutiveValidFrames = 0
                resizeStartTime = System.currentTimeMillis()
                isResizing = true

                // Timing based on encoder type (Qualcomm needs more time for SPS/PPS)
                if (isQualcommEncoder) {
                    currentMinWaitTime = QUALCOMM_MIN_WAIT_TIME_MS
                    currentStableFrameCount = QUALCOMM_STABLE_FRAME_COUNT
                    Log.d(TAG, "Qualcomm encoder: extended timing (${currentMinWaitTime}ms, $currentStableFrameCount frames)")
                } else {
                    currentMinWaitTime = DEFAULT_MIN_WAIT_TIME_MS
                    currentStableFrameCount = DEFAULT_STABLE_FRAME_COUNT
                }
            }

            // 2. Execute all resize operations ATOMICALLY in single block
            ThreadUtils.invokeAtFrontUninterruptibly(helper.handler) {
                helper.stopListening()

                helper.surfaceTexture.setDefaultBufferSize(newWidth, newHeight)
                vd.resize(newWidth, newHeight, density)

                val oldSurface = currentSurface
                val newSurface = Surface(helper.surfaceTexture)
                vd.surface = newSurface
                currentSurface = newSurface
                oldSurface?.release()

                helper.setTextureSize(newWidth, newHeight)

                // Update dimensions INSIDE handler (thread safety)
                width = newWidth
                height = newHeight

                helper.startListening { frame -> deliverFrame(frame) }
            }

            Log.d(TAG, "VirtualDisplay resized to: ${newWidth}x${newHeight}")

            // 3. Notify VideoSource AFTER resize is complete (fixes race condition on fast encoders like Adreno 750)
            Log.d(TAG, "Notifying encoder AFTER resize complete")
            resizeListener?.onCaptureResized(newWidth, newHeight)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to resize: ${e.message}", e)
            synchronized(resizeLock) {
                isResizing = false
                consecutiveValidFrames = 0  // v9.1: Reset on error
            }
            // Emergency recovery: try to resume listening
            try {
                helper.startListening { frame -> deliverFrame(frame) }
            } catch (e2: Exception) {
                Log.e(TAG, "Recovery failed: ${e2.message}", e2)
            }
        }
    }

    override fun stopCapture() {
        Log.d(TAG, "stopCapture")
        synchronized(resizeLock) {
            surfaceTextureHelper?.stopListening()
            virtualDisplay?.release()
            virtualDisplay = null
            currentSurface?.release()
            currentSurface = null
            mediaProjection?.stop()
            mediaProjection = null
        }
        capturerObserver?.onCapturerStopped()
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
        val helper = surfaceTextureHelper ?: run {
            Log.w(TAG, "SurfaceTextureHelper is null, cannot change format")
            return
        }

        if (isDisposed) {
            Log.w(TAG, "Capturer is disposed, cannot change format")
            return
        }

        // Android 14+: Ignore external calls - onCapturedContentResize is the source of truth
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Log.d(TAG, "changeCaptureFormat(${width}x${height}) ignored on Android 14+ - using system callback")
            return
        }

        // Android 13-: Recreate VirtualDisplay with PROPER lifecycle
        ThreadUtils.invokeAtFrontUninterruptibly(helper.handler) {
            synchronized(resizeLock) {
                if (isDisposed) return@invokeAtFrontUninterruptibly

                // 1. STOP listener FIRST (prevent stale frames)
                helper.stopListening()

                // Update dimensions
                this.width = width
                this.height = height

                // Release old VirtualDisplay and Surface
                virtualDisplay?.release()
                currentSurface?.release()

                // 2. Set BUFFER size FIRST (critical for correct frame dimensions)
                helper.surfaceTexture.setDefaultBufferSize(width, height)

                // 3. Set texture size (internal WebRTC tracking)
                helper.setTextureSize(width, height)

                // 4. Create new Surface
                currentSurface = Surface(helper.surfaceTexture)

                // 5. Create new VirtualDisplay
                virtualDisplay = mediaProjection?.createVirtualDisplay(
                    "ResizableScreenCapture",
                    width,
                    height,
                    density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    currentSurface,
                    null,
                    helper.handler
                )

                // 6. RESTART frame listener
                helper.startListening { frame -> deliverFrame(frame) }

                Log.d(TAG, "VirtualDisplay recreated: ${width}x${height}")
            }
        }
    }

    override fun dispose() {
        Log.d(TAG, "dispose")
        isDisposed = true
        stopCapture()
    }

    override fun isScreencast(): Boolean = true
}
