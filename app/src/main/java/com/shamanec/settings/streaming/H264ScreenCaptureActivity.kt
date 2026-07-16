package com.shamanec.settings.streaming

import com.shamanec.settings.BuildConfig

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.shamanec.settings.R

class H264ScreenCaptureActivity: Activity() {
    private val TAG = "${BuildConfig.LOG_PREFIX} H264Activity"

    // The onCreate method is an Android activity lifecycle method that gets called when the activity is created.
    // In this case, it sets the layout for the activity and calls startProjection().
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate() called")
        try {
            setContentView(R.layout.activity_main)
            Log.d(TAG, "Content view set successfully")
            startProjection()
            Log.d(TAG, "startProjection() called")
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate: ${e.message}", e)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        Log.d(TAG, "onActivityResult() called - requestCode: $requestCode, resultCode: $resultCode")

        // Check if the Activity REQUEST_CODE and result are as expected
        // And start the ScreenCaptureService
        if (requestCode == REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                Log.d(TAG, "Permission granted, starting H264ScreenCaptureService")
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(
                            H264ScreenCaptureService.getStartIntent(
                                this,
                                resultCode,
                                data
                            )
                        )
                        Log.d(TAG, "Foreground service start requested")
                    } else {
                        startService(H264ScreenCaptureService.getStartIntent(this, resultCode, data))
                        Log.d(TAG, "Service start requested")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting service: ${e.message}", e)
                }
            } else {
                Log.w(TAG, "Permission denied or cancelled - resultCode: $resultCode")
            }
        } else {
            Log.w(TAG, "Unexpected request code: $requestCode")
        }

        // Comment this to run via Android Studio, we use it to directly close the activity after the service was started
        Log.d(TAG, "Finishing activity")
        finish()
    }

    /****************************************** UI Widget Callbacks  */ // The startProjection method gets called when the user wants to start screen capture.
    // It gets the MediaProjectionManager from the system, creates a screen capture intent with createScreenCaptureIntent(),
    // and starts an activity for result using startActivityForResult.
    private fun startProjection() {
        val mProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mProjectionManager.createScreenCaptureIntent(), REQUEST_CODE)
    }

    // The stopProjection method gets called when the user wants to stop screen capture.
    // It starts a ScreenCaptureService with an intent that tells the service to stop.
    // TODO: Currently unused, don't really want to give option to stop screen capture service, will look into possibly using it
    private fun stopProjection() {
        startService(H264ScreenCaptureService.getStopIntent(this))
    }

    companion object {
        private const val REQUEST_CODE = 100
    }
}
