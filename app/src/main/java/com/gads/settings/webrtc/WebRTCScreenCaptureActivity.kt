package com.gads.settings.webrtc

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import com.gads.settings.R

class WebRTCScreenCaptureActivity : Activity() {
    // The onCreate method is an Android activity lifecycle method that gets called when the activity is created.
    // In this case, it sets the layout for the activity and calls startProjection().
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        startProjection()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent) {
        // Check if the Activity REQUEST_CODE and result are as expected
        // And start the ScreenCaptureService
        if (requestCode == REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(
                        WebRTCScreenCaptureService.getStartIntent(
                            this,
                            resultCode,
                            data
                        )
                    )
                } else {
                    startService(WebRTCScreenCaptureService.getStartIntent(this, resultCode, data))
                }
            }
        }

        // Comment this to run via Android Studio, we use it to directly close the activity after the service was started
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
        startService(WebRTCScreenCaptureService.getStopIntent(this))
    }

    companion object {
        private const val REQUEST_CODE = 100
    }
}