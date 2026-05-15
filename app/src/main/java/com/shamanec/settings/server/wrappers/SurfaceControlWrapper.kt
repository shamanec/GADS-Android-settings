package com.shamanec.settings.server.wrappers

import com.shamanec.settings.BuildConfig

import android.annotation.SuppressLint
import android.graphics.Rect
import android.os.IBinder
import android.util.Log
import android.view.Surface

/**
 * Wrapper for accessing SurfaceControl hidden APIs as a fallback method for screen capture.
 *
 * SurfaceControl is a lower-level API than DisplayManager and works on older Android versions
 * or devices where DisplayManager.createVirtualDisplay() might not be available.
 *
 * This is used as a fallback when DisplayManagerWrapper fails.
 *
 * Based on IMPLEMENTATION_PLAN.md lines 260-332 (Phase 3: SurfaceControl Wrapper).
 */
@SuppressLint("PrivateApi")
object SurfaceControlWrapper {
    private const val TAG = "${BuildConfig.LOG_PREFIX} SurfaceControl"

    // Cache the SurfaceControl class for performance
    private val surfaceControlClass: Class<*> by lazy {
        Class.forName("android.view.SurfaceControl")
    }

    /**
     * Create a mirrored display using SurfaceControl.
     * This is even more privileged than VirtualDisplay and works on older devices.
     *
     * @param name Display name
     * @param secure Whether the display should be secure (DRM protected)
     * @return IBinder token for the created display
     */
    fun createDisplay(name: String, secure: Boolean = false): IBinder? {
        return try {
            // List all createDisplay methods for debugging
            Log.d(TAG, "Listing all methods on SurfaceControl:")
            for (method in surfaceControlClass.methods) {
                if (method.name.contains("Display") || method.name.contains("display")) {
                    val params = method.parameterTypes.joinToString(", ") { it.simpleName }
                    Log.d(TAG, "  ${method.name}($params)")
                }
            }

            // Try (String, boolean) signature first (older Android)
            val method = try {
                surfaceControlClass.getMethod(
                    "createDisplay",
                    String::class.java,
                    Boolean::class.javaPrimitiveType
                )
            } catch (e: NoSuchMethodException) {
                Log.d(TAG, "createDisplay(String, boolean) not found, trying (String) signature...")
                // Try (String) signature (newer Android removed the boolean parameter)
                surfaceControlClass.getMethod(
                    "createDisplay",
                    String::class.java
                )
            }

            val result = if (method.parameterTypes.size == 2) {
                method.invoke(null, name, secure) as? IBinder
            } else {
                method.invoke(null, name) as? IBinder
            }

            Log.d(TAG, "Display created via SurfaceControl: $name")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create display via SurfaceControl", e)
            e.printStackTrace()
            null
        }
    }

    /**
     * Set the surface for a display.
     *
     * @param displayToken The display token from createDisplay()
     * @param surface The surface to render to (from MediaCodec.createInputSurface())
     */
    fun setDisplaySurface(displayToken: IBinder, surface: Surface) {
        try {
            val method = surfaceControlClass.getMethod(
                "setDisplaySurface",
                IBinder::class.java,
                Surface::class.java
            )
            method.invoke(null, displayToken, surface)
            Log.d(TAG, "Display surface set successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set display surface", e)
            throw e
        }
    }

    /**
     * Set the projection (orientation and size) for a display.
     *
     * @param displayToken The display token
     * @param orientation Display orientation (0, 1, 2, 3 for 0°, 90°, 180°, 270°)
     * @param layerStackRect The source rectangle
     * @param displayRect The destination rectangle
     */
    fun setDisplayProjection(
        displayToken: IBinder,
        orientation: Int,
        layerStackRect: Rect,
        displayRect: Rect
    ) {
        try {
            val method = surfaceControlClass.getMethod(
                "setDisplayProjection",
                IBinder::class.java,
                Int::class.javaPrimitiveType,
                Rect::class.java,
                Rect::class.java
            )
            method.invoke(null, displayToken, orientation, layerStackRect, displayRect)
            Log.d(TAG, "Display projection set: orientation=$orientation")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set display projection", e)
            throw e
        }
    }

    /**
     * Set the layer stack for a display.
     *
     * @param displayToken The display token
     * @param layerStack The layer stack ID (usually 0 for default display)
     */
    fun setDisplayLayerStack(displayToken: IBinder, layerStack: Int) {
        try {
            val method = surfaceControlClass.getMethod(
                "setDisplayLayerStack",
                IBinder::class.java,
                Int::class.javaPrimitiveType
            )
            method.invoke(null, displayToken, layerStack)
            Log.d(TAG, "Display layer stack set: $layerStack")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set display layer stack", e)
            throw e
        }
    }

    /**
     * Open a transaction for atomic display updates.
     * Must be followed by closeTransaction().
     */
    fun openTransaction() {
        try {
            val method = surfaceControlClass.getMethod("openTransaction")
            method.invoke(null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open transaction", e)
            throw e
        }
    }

    /**
     * Close a transaction, applying all pending display updates atomically.
     */
    fun closeTransaction() {
        try {
            val method = surfaceControlClass.getMethod("closeTransaction")
            method.invoke(null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close transaction", e)
            throw e
        }
    }

    /**
     * Destroy a display created with createDisplay().
     *
     * @param displayToken The display token to destroy
     */
    fun destroyDisplay(displayToken: IBinder) {
        try {
            val method = surfaceControlClass.getMethod(
                "destroyDisplay",
                IBinder::class.java
            )
            method.invoke(null, displayToken)
            Log.d(TAG, "Display destroyed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to destroy display", e)
        }
    }
}
