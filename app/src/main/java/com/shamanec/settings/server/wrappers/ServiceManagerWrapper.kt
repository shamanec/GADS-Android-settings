package com.shamanec.settings.server.wrappers

import com.shamanec.settings.BuildConfig

import android.annotation.SuppressLint
import android.os.IBinder
import android.os.IInterface

/**
 * Wrapper for accessing the hidden android.os.ServiceManager class via reflection.
 * ServiceManager provides access to system services that are normally hidden from apps.
 *
 * This works when running with shell privileges (via app_process) because shell user (UID 2000)
 * has permission to access these hidden APIs.
 *
 * Pattern based on RemoteControlServer.kt reflection approach.
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
object ServiceManagerWrapper {
    private const val TAG = "${BuildConfig.LOG_PREFIX} ServiceManager"

    // Cache the ServiceManager class and getService method for performance
    private val serviceManagerClass: Class<*> by lazy {
        Class.forName("android.os.ServiceManager")
    }

    private val getServiceMethod by lazy {
        serviceManagerClass.getDeclaredMethod("getService", String::class.java)
    }

    /**
     * Get a system service by name using reflection.
     *
     * @param serviceName The name of the service (e.g., "display", "input", "window")
     * @param interfaceType The fully qualified interface type (e.g., "android.hardware.display.IDisplayManager")
     * @return The service interface object
     * @throws Exception if the service cannot be accessed
     */
    fun getService(serviceName: String, interfaceType: String): IInterface {
        try {
            // Get the IBinder for the service
            val binder = getServiceMethod.invoke(null, serviceName) as? IBinder
                ?: throw IllegalStateException("Service '$serviceName' returned null binder")

            // Convert IBinder to the proper interface using Stub.asInterface()
            val stubClass = Class.forName("$interfaceType\$Stub")
            val asInterfaceMethod = stubClass.getMethod("asInterface", IBinder::class.java)

            return asInterfaceMethod.invoke(null, binder) as IInterface
        } catch (e: Exception) {
            throw RuntimeException("Failed to get service '$serviceName' with interface '$interfaceType'", e)
        }
    }

    /**
     * Check if a service is available without throwing an exception.
     *
     * @param serviceName The name of the service to check
     * @return true if the service is available, false otherwise
     */
    fun isServiceAvailable(serviceName: String): Boolean {
        return try {
            val binder = getServiceMethod.invoke(null, serviceName) as? IBinder
            binder != null
        } catch (e: Exception) {
            false
        }
    }
}
