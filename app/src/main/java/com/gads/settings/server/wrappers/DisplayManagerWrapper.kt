package com.gads.settings.server.wrappers

import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface

/**
 * Wrapper for IDisplayManager accessed via ServiceManager (scrcpy's approach).
 *
 * This is a SIMPLIFIED version based directly on scrcpy's DisplayManager.java.
 * Previous implementation tried 4 different approaches - this uses only the proven one.
 *
 * Reference: scrcpy/wrappers/DisplayManager.java
 */
object DisplayManagerWrapper {
    private const val TAG = "GADS DisplayManager"

    // Cached DisplayManager instance
    private var displayManagerInstance: Any? = null

    /**
     * Get or create the IDisplayManager instance via ServiceManager.
     */
    private fun getDisplayManager(): Any {
        if (displayManagerInstance == null) {
            displayManagerInstance = ServiceManagerWrapper.getService(
                "display",
                "android.hardware.display.IDisplayManager"
            )
            Log.d(TAG, "IDisplayManager instance created")
        }
        return displayManagerInstance!!
    }

    /**
     * Create a virtual display for screen mirroring - EXACT scrcpy implementation.
     *
     * Reference: scrcpy DisplayManager.java lines 171-173
     */
    fun createVirtualDisplay(
        name: String,
        width: Int,
        height: Int,
        displayIdToMirror: Int,
        surface: Surface
    ): VirtualDisplay? {
        return try {
            val displayManager = getDisplayManager()

            // DEBUG: List ALL methods on IDisplayManager to see what exists
            Log.d(TAG, "=== ALL methods on IDisplayManager ===")
            for (method in displayManager.javaClass.methods) {
                if (method.name.contains("createVirtualDisplay") || method.name.contains("Virtual")) {
                    val params = method.parameterTypes.joinToString(", ") { it.simpleName }
                    Log.d(TAG, "  ${method.name}($params) -> ${method.returnType.simpleName}")
                }
            }
            Log.d(TAG, "=== END method list ===")

            // Try scrcpy's EXACT approach - static method on PUBLIC DisplayManager
            try {
                Log.d(TAG, "Trying PUBLIC DisplayManager.createVirtualDisplay (scrcpy lines 163-173)...")
                val method = android.hardware.display.DisplayManager::class.java.getMethod(
                    "createVirtualDisplay",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Surface::class.java
                )

                val result = method.invoke(null, name, width, height, displayIdToMirror, surface) as? VirtualDisplay

                if (result != null) {
                    Log.d(TAG, "✓ VirtualDisplay created via PUBLIC DisplayManager (scrcpy method)")
                    return result
                }
            } catch (e: NoSuchMethodException) {
                Log.d(TAG, "PUBLIC DisplayManager.createVirtualDisplay not found: ${e.message}")
            } catch (e: Exception) {
                Log.d(TAG, "PUBLIC DisplayManager failed: ${e.message}")
                e.printStackTrace()
            }

            Log.e(TAG, "✗ All methods failed - no compatible API found on this Android version")
            null

        } catch (e: Exception) {
            Log.e(TAG, "✗ Error creating VirtualDisplay", e)
            e.printStackTrace()
            null
        }
    }


    /**
     * Get display information for a given display ID using IDisplayManager.
     *
     * Reference: scrcpy DisplayManager.java lines 126-153
     *
     * @param displayId The display ID (0 for default display)
     * @return DisplayMetrics for the display, or null if not available
     */
    fun getDisplayInfo(displayId: Int = 0): DisplayMetrics? {
        return try {
            val displayManager = getDisplayManager()

            // Call IDisplayManager.getDisplayInfo(int displayId)
            val method = displayManager.javaClass.getMethod("getDisplayInfo", Int::class.javaPrimitiveType)
            val displayInfo = method.invoke(displayManager, displayId)

            if (displayInfo != null) {
                // Extract width, height, density from DisplayInfo object via reflection
                // Reference: scrcpy DisplayManager.java lines 136-141
                val cls = displayInfo.javaClass
                val width = cls.getDeclaredField("logicalWidth").getInt(displayInfo)
                val height = cls.getDeclaredField("logicalHeight").getInt(displayInfo)
                val dpi = cls.getDeclaredField("logicalDensityDpi").getInt(displayInfo)

                Log.d(TAG, "Display info retrieved: ${width}x${height}@${dpi}dpi")

                DisplayMetrics().apply {
                    widthPixels = width
                    heightPixels = height
                    densityDpi = dpi
                }
            } else {
                Log.w(TAG, "Display with ID $displayId not found")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get display info", e)
            e.printStackTrace()
            null
        }
    }

    /**
     * Interface for display change callbacks.
     * Reference: scrcpy DisplayManager.java lines 31-38
     */
    interface DisplayListener {
        /**
         * Called whenever the properties of a logical display have changed.
         * @param displayId The id of the logical display that changed.
         */
        fun onDisplayChanged(displayId: Int)
    }

    /**
     * Handle for registered display listener (used for unregistration).
     */
    class DisplayListenerHandle(internal val displayListenerProxy: Any)

    /**
     * Register a display listener for orientation/size change notifications.
     * This is event-driven, unlike polling - much more efficient.
     *
     * Reference: scrcpy DisplayManager.java lines 202-240
     *
     * @param listener The listener to receive callbacks
     * @param handler The handler on which to execute callbacks
     * @return Handle for later unregistration, or null if registration fails
     */
    fun registerDisplayListener(listener: DisplayListener, handler: Handler): DisplayListenerHandle? {
        return try {
            val displayManager = getDisplayManager()

            // DEBUG: List all register methods
            Log.d(TAG, "=== Looking for registerDisplayListener methods ===")
            for (method in displayManager.javaClass.methods) {
                if (method.name.contains("register") || method.name.contains("Display")) {
                    val params = method.parameterTypes.joinToString(", ") { it.simpleName }
                    Log.d(TAG, "  ${method.name}($params)")
                }
            }
            Log.d(TAG, "=== END register methods ===")

            // Create dynamic proxy for android.hardware.display.DisplayManager$DisplayListener
            val displayListenerClass = Class.forName("android.hardware.display.DisplayManager\$DisplayListener")
            val displayListenerProxy = java.lang.reflect.Proxy.newProxyInstance(
                ClassLoader.getSystemClassLoader(),
                arrayOf(displayListenerClass)
            ) { _, method, args ->
                when (method.name) {
                    "onDisplayAdded" -> {
                        Log.d(TAG, "Display added: ${args[0]}")
                        null
                    }
                    "onDisplayRemoved" -> {
                        Log.d(TAG, "Display removed: ${args[0]}")
                        null
                    }
                    "onDisplayChanged" -> {
                        Log.d(TAG, "Display changed: ${args[0]}")
                        listener.onDisplayChanged(args[0] as Int)
                        null
                    }
                    "toString" -> "DisplayListener"
                    else -> null
                }
            }

            // Try different registerDisplayListener method signatures
            var registered = false

            // Try 4-param: registerDisplayListener(listener, handler, long eventsMask, String packageName)
            try {
                val method = displayManager.javaClass.getMethod(
                    "registerDisplayListener",
                    displayListenerClass,
                    Handler::class.java,
                    Long::class.javaPrimitiveType,
                    String::class.java
                )
                method.invoke(displayManager, displayListenerProxy, handler, EVENT_FLAG_DISPLAY_CHANGED, "com.android.shell")
                Log.d(TAG, "✓ Registered display listener with 4-param signature")
                registered = true
            } catch (e: NoSuchMethodException) {
                Log.d(TAG, "4-param signature not found")
            }

            // Try 3-param: registerDisplayListener(listener, handler, long eventsMask)
            if (!registered) {
                try {
                    val method = displayManager.javaClass.getMethod(
                        "registerDisplayListener",
                        displayListenerClass,
                        Handler::class.java,
                        Long::class.javaPrimitiveType
                    )
                    method.invoke(displayManager, displayListenerProxy, handler, EVENT_FLAG_DISPLAY_CHANGED)
                    Log.d(TAG, "✓ Registered display listener with 3-param signature")
                    registered = true
                } catch (e: NoSuchMethodException) {
                    Log.d(TAG, "3-param signature not found")
                }
            }

            // Try 2-param: registerDisplayListener(listener, handler)
            if (!registered) {
                try {
                    val method = displayManager.javaClass.getMethod(
                        "registerDisplayListener",
                        displayListenerClass,
                        Handler::class.java
                    )
                    method.invoke(displayManager, displayListenerProxy, handler)
                    Log.d(TAG, "✓ Registered display listener with 2-param signature")
                    registered = true
                } catch (e: NoSuchMethodException) {
                    Log.d(TAG, "2-param signature not found")
                }
            }

            if (registered) {
                DisplayListenerHandle(displayListenerProxy)
            } else {
                Log.w(TAG, "No compatible registerDisplayListener signature found")
                null
            }

        } catch (e: Exception) {
            Log.e(TAG, "Could not register display listener", e)
            e.printStackTrace()
            null
        }
    }

    /**
     * Unregister a previously registered display listener.
     *
     * @param handle The handle returned from registerDisplayListener()
     */
    fun unregisterDisplayListener(handle: DisplayListenerHandle?) {
        if (handle == null) return

        try {
            val displayManager = getDisplayManager()
            val displayListenerClass = Class.forName("android.hardware.display.DisplayManager\$DisplayListener")
            displayManager.javaClass
                .getMethod("unregisterDisplayListener", displayListenerClass)
                .invoke(displayManager, handle.displayListenerProxy)
            Log.d(TAG, "Display listener unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "Could not unregister display listener", e)
        }
    }

    // Constants
    // Reference: scrcpy DisplayManager.java line 29
    private const val EVENT_FLAG_DISPLAY_CHANGED = 1L shl 2
}
