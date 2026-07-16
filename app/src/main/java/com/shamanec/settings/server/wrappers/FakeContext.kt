package com.shamanec.settings.server.wrappers

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Process

/**
 * Fake Context implementation that provides just enough functionality for DisplayManager.
 * Based on scrcpy's FakeContext.java
 *
 * This context uses "com.android.shell" as its package name, which allows it to bypass
 * MediaProjection requirements when running with shell privileges via app_process.
 */
@SuppressLint("PrivateApi")
class FakeContext private constructor() : ContextWrapper(Workarounds.getSystemContext()) {

    companion object {
        const val PACKAGE_NAME = "com.android.shell"

        private val INSTANCE = FakeContext()

        /**
         * Get the singleton FakeContext instance.
         * Must call Workarounds.apply() before first use.
         */
        @JvmStatic
        fun get(): FakeContext = INSTANCE
    }

    override fun getPackageName(): String {
        return PACKAGE_NAME
    }

    override fun getOpPackageName(): String {
        return PACKAGE_NAME
    }

    /**
     * Get the AttributionSource for this context (Android 12+).
     * Returns an AttributionSource with SHELL_UID and "com.android.shell" package.
     */
    @SuppressLint("NewApi")
    override fun getAttributionSource(): AttributionSource {
        val builder = AttributionSource.Builder(Process.SHELL_UID)
        builder.setPackageName(PACKAGE_NAME)
        return builder.build()
    }

    override fun getSystemService(name: String): Any? {
        // This is used for creating services like DisplayManager.
        // Use FakeContext to ensure PACKAGE_NAME is used.
        return super.getSystemService(name)
    }

    @SuppressLint("Recycle")
    override fun getContentResolver(): ContentResolver {
        // Return the base context's ContentResolver
        // We don't need a custom implementation for DisplayManager use case
        return baseContext.contentResolver
    }
}
