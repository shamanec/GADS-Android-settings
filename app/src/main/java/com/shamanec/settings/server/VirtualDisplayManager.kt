package com.shamanec.settings.server

import com.shamanec.settings.BuildConfig

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Handler
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import com.shamanec.settings.server.wrappers.DisplayManagerWrapper
import com.shamanec.settings.server.wrappers.SurfaceControlWrapper
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages VirtualDisplay creation, orientation monitoring, and display metrics.
 *
 * Shared between H264Server and MjpegServer to avoid duplicating:
 * - Display metrics retrieval (context vs reflection fallback)
 * - VirtualDisplay creation (MediaProjection vs shell privileges vs SurfaceControl)
 * - Orientation change polling and restart coordination
 * - Screen refresh triggering
 */
class VirtualDisplayManager(
    private val context: Context? = null,
    private val useMediaProjection: Boolean = false,
    private val mediaProjection: MediaProjection? = null,
    private val onOrientationChanged: () -> Unit
) {
    private val TAG = "${BuildConfig.LOG_PREFIX} VirtualDisplay"

    // Virtual display
    private var virtualDisplay: VirtualDisplay? = null

    // SurfaceControl display token (for fallback)
    private var surfaceControlDisplay: android.os.IBinder? = null

    // Orientation monitoring
    private var orientationMonitorThread: Thread? = null
    @Volatile
    private var monitoringOrientation = false

    // Client check callback for skipping orientation polls when no clients connected
    private var hasClientsCheck: (() -> Boolean)? = null

    companion object {
        private const val ORIENTATION_POLL_INTERVAL_MS = 500L
    }

    /**
     * Set a callback to check if any clients are connected.
     * When set, orientation monitoring skips polling when no clients are connected.
     */
    fun setClientCheck(check: () -> Boolean) {
        hasClientsCheck = check
    }

    /**
     * Get display metrics from context or via reflection (for app_process mode).
     */
    fun getDisplayMetrics(defaultWidth: Int = 1280, defaultHeight: Int = 720): DisplayMetrics {
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
            widthPixels = defaultWidth
            heightPixels = defaultHeight
            densityDpi = 240
        }
    }

    /**
     * Create a VirtualDisplay that mirrors the default display onto the given surface.
     */
    @SuppressLint("WrongConstant")
    fun createVirtualDisplay(
        name: String,
        width: Int,
        height: Int,
        density: Int,
        surface: Surface,
        handler: Handler?
    ) {
        if (useMediaProjection && mediaProjection != null) {
            createWithMediaProjection(name, width, height, density, surface, handler)
        } else {
            createWithShellPrivileges(name, width, height, surface)
        }
    }

    /**
     * Release VirtualDisplay and SurfaceControl resources, stop orientation monitoring.
     */
    fun release() {
        try {
            stopOrientationMonitoring()

            virtualDisplay?.release()
            virtualDisplay = null

            surfaceControlDisplay?.let {
                try {
                    SurfaceControlWrapper.destroyDisplay(it)
                    Log.d(TAG, "SurfaceControl display destroyed")
                } catch (e: Exception) {
                    Log.e(TAG, "Error destroying SurfaceControl display", e)
                }
            }
            surfaceControlDisplay = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing display resources", e)
        }
    }

    /**
     * Trigger a screen refresh by showing volume UI.
     */
    fun triggerScreenRefresh() {
        try {
            Runtime.getRuntime().exec(arrayOf("input", "keyevent", "KEYCODE_VOLUME_DOWN"))
            Log.d(TAG, "Triggered screen refresh via volume UI")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to trigger screen refresh: ${e.message}")
        }
    }

    @SuppressLint("WrongConstant")
    private fun createWithMediaProjection(
        name: String,
        width: Int,
        height: Int,
        density: Int,
        surface: Surface,
        handler: Handler?
    ) {
        if (mediaProjection == null) {
            throw IllegalStateException("MediaProjection is null but useMediaProjection=true")
        }

        virtualDisplay = mediaProjection.createVirtualDisplay(
            name,
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
            surface,
            null,
            handler
        )

        Log.d(TAG, "VirtualDisplay created via MediaProjection")
    }

    private fun createWithShellPrivileges(name: String, width: Int, height: Int, surface: Surface) {
        Log.d(TAG, "Attempting DisplayManager.createVirtualDisplay()...")
        virtualDisplay = DisplayManagerWrapper.createVirtualDisplay(
            name = name,
            width = width,
            height = height,
            displayIdToMirror = 0,
            surface = surface
        )

        if (virtualDisplay != null) {
            Log.d(TAG, "VirtualDisplay created via DisplayManager")
            startOrientationMonitoring()
            return
        }

        Log.w(TAG, "DisplayManager failed, trying SurfaceControl...")
        createWithSurfaceControl(name, width, height, surface)
    }

    private fun createWithSurfaceControl(name: String, width: Int, height: Int, surface: Surface) {
        try {
            val displayToken = SurfaceControlWrapper.createDisplay(name, secure = false)
                ?: throw RuntimeException("SurfaceControl.createDisplay() returned null")

            surfaceControlDisplay = displayToken

            val metrics = getDisplayMetrics()

            SurfaceControlWrapper.openTransaction()
            try {
                SurfaceControlWrapper.setDisplaySurface(displayToken, surface)

                val sourceRect = Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
                val displayRect = Rect(0, 0, width, height)
                SurfaceControlWrapper.setDisplayProjection(
                    displayToken,
                    orientation = 0,
                    layerStackRect = sourceRect,
                    displayRect = displayRect
                )

                SurfaceControlWrapper.setDisplayLayerStack(displayToken, 0)

                Log.d(TAG, "SurfaceControl display configured: ${width}x${height}")
            } finally {
                SurfaceControlWrapper.closeTransaction()
            }

            Log.d(TAG, "VirtualDisplay created via SurfaceControl (fallback)")
            startOrientationMonitoring()

        } catch (e: Exception) {
            Log.e(TAG, "SurfaceControl fallback also failed", e)
            e.printStackTrace()
            throw RuntimeException(
                "Both DisplayManager and SurfaceControl approaches failed. Error: ${e.message}", e
            )
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

                    val check = hasClientsCheck
                    if (check != null && !check()) continue

                    val metrics = getDisplayMetrics()
                    val currentWidth = metrics.widthPixels
                    val currentHeight = metrics.heightPixels

                    if (currentWidth != lastWidth || currentHeight != lastHeight) {
                        Log.d(TAG, "Display size changed: ${lastWidth}x${lastHeight} -> ${currentWidth}x${currentHeight}")

                        lastWidth = currentWidth
                        lastHeight = currentHeight

                        onOrientationChanged()
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
}
