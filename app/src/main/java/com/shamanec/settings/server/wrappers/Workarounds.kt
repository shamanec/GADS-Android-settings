package com.shamanec.settings.server.wrappers

import com.shamanec.settings.BuildConfig

import android.annotation.SuppressLint
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log

/**
 * Workarounds for accessing hidden Android APIs and setting up fake app environment.
 * Based on scrcpy's Workarounds.java
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
object Workarounds {
    private const val TAG = "${BuildConfig.LOG_PREFIX} Workarounds"

    private val ACTIVITY_THREAD_CLASS: Class<*>
    private val ACTIVITY_THREAD: Any

    @Volatile
    private var isApplied = false

    init {
        try {
            // ActivityThread activityThread = new ActivityThread();
            ACTIVITY_THREAD_CLASS = Class.forName("android.app.ActivityThread")
            val activityThreadConstructor = ACTIVITY_THREAD_CLASS.getDeclaredConstructor()
            activityThreadConstructor.isAccessible = true
            ACTIVITY_THREAD = activityThreadConstructor.newInstance()

            // ActivityThread.sCurrentActivityThread = activityThread;
            val sCurrentActivityThreadField = ACTIVITY_THREAD_CLASS.getDeclaredField("sCurrentActivityThread")
            sCurrentActivityThreadField.isAccessible = true
            sCurrentActivityThreadField.set(null, ACTIVITY_THREAD)

            // activityThread.mSystemThread = true;
            val mSystemThreadField = ACTIVITY_THREAD_CLASS.getDeclaredField("mSystemThread")
            mSystemThreadField.isAccessible = true
            mSystemThreadField.setBoolean(ACTIVITY_THREAD, true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ActivityThread", e)
            throw AssertionError(e)
        }
    }

    /**
     * Apply all workarounds to set up the fake app environment.
     * Must be called before using FakeContext or DisplayManager.
     * This method is idempotent - calling it multiple times is safe.
     */
    fun apply() {
        // Return early if already applied (idempotent)
        if (isApplied) {
            return
        }

        if (Build.VERSION.SDK_INT >= 31) { // Android 12+
            // On some Samsung devices, DisplayManagerGlobal.getDisplayInfoLocked() calls
            // ActivityThread.currentActivityThread().getConfiguration(),
            // which requires a non-null ConfigurationController.
            // Must be called before fillAppContext() because it is necessary to get a valid system context.
            fillConfigurationController()
        }

        // On ONYX devices, fillAppInfo() breaks video mirroring, but we'll include it
        // for broader compatibility. Can be disabled if issues arise.
        val mustFillAppInfo = !Build.BRAND.equals("ONYX", ignoreCase = true)
        if (mustFillAppInfo) {
            fillAppInfo()
        }

        fillAppContext()

        // Mark as applied
        isApplied = true
        Log.d(TAG, "Workarounds applied successfully")
    }

    private fun fillAppInfo() {
        try {
            // ActivityThread.AppBindData appBindData = new ActivityThread.AppBindData();
            val appBindDataClass = Class.forName("android.app.ActivityThread\$AppBindData")
            val appBindDataConstructor = appBindDataClass.getDeclaredConstructor()
            appBindDataConstructor.isAccessible = true
            val appBindData = appBindDataConstructor.newInstance()

            val applicationInfo = ApplicationInfo()
            applicationInfo.packageName = FakeContext.PACKAGE_NAME

            // appBindData.appInfo = applicationInfo;
            val appInfoField = appBindDataClass.getDeclaredField("appInfo")
            appInfoField.isAccessible = true
            appInfoField.set(appBindData, applicationInfo)

            // activityThread.mBoundApplication = appBindData;
            val mBoundApplicationField = ACTIVITY_THREAD_CLASS.getDeclaredField("mBoundApplication")
            mBoundApplicationField.isAccessible = true
            mBoundApplicationField.set(ACTIVITY_THREAD, appBindData)

            Log.d(TAG, "App info filled successfully")
        } catch (throwable: Throwable) {
            // This is a workaround, so failing is not an error
            Log.d(TAG, "Could not fill app info: ${throwable.message}")
        }
    }

    private fun fillAppContext() {
        try {
            val app = Instrumentation.newApplication(Application::class.java, FakeContext.get())

            // activityThread.mInitialApplication = app;
            val mInitialApplicationField = ACTIVITY_THREAD_CLASS.getDeclaredField("mInitialApplication")
            mInitialApplicationField.isAccessible = true
            mInitialApplicationField.set(ACTIVITY_THREAD, app)

            Log.d(TAG, "App context filled successfully")
        } catch (throwable: Throwable) {
            // This is a workaround, so failing is not an error
            Log.d(TAG, "Could not fill app context: ${throwable.message}")
        }
    }

    private fun fillConfigurationController() {
        try {
            val configurationControllerClass = Class.forName("android.app.ConfigurationController")
            val activityThreadInternalClass = Class.forName("android.app.ActivityThreadInternal")

            // configurationController = new ConfigurationController(ACTIVITY_THREAD);
            val configurationControllerConstructor = configurationControllerClass.getDeclaredConstructor(
                activityThreadInternalClass
            )
            configurationControllerConstructor.isAccessible = true
            val configurationController = configurationControllerConstructor.newInstance(ACTIVITY_THREAD)

            // ACTIVITY_THREAD.mConfigurationController = configurationController;
            val configurationControllerField = ACTIVITY_THREAD_CLASS.getDeclaredField("mConfigurationController")
            configurationControllerField.isAccessible = true
            configurationControllerField.set(ACTIVITY_THREAD, configurationController)

            Log.d(TAG, "Configuration controller filled successfully")
        } catch (throwable: Throwable) {
            Log.d(TAG, "Could not fill configuration: ${throwable.message}")
        }
    }

    /**
     * Get the system context via ActivityThread.
     * This is used by FakeContext as the base context.
     *
     * @return System context, or null if unavailable
     */
    fun getSystemContext(): Context? {
        return try {
            val getSystemContextMethod = ACTIVITY_THREAD_CLASS.getDeclaredMethod("getSystemContext")
            getSystemContextMethod.invoke(ACTIVITY_THREAD) as Context
        } catch (throwable: Throwable) {
            // This is a workaround, so failing is not an error
            Log.d(TAG, "Could not get system context: ${throwable.message}")
            null
        }
    }
}
