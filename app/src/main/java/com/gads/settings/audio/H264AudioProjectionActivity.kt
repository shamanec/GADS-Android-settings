package com.gads.settings.audio

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log

/**
 * Requests MediaProjection permission for internal audio capture (AudioPlaybackCapture).
 *
 * Shows the system screen recording dialog, obtains the MediaProjection token,
 * and stores it in MediaProjectionHolder. H264AudioService picks it up via getOrListen().
 *
 * Triggered by the Go provider via:
 *   adb shell am start -n com.gads.settings/.audio.H264AudioProjectionActivity
 */
class H264AudioProjectionActivity : Activity() {
    companion object {
        private const val TAG = "H264AudioProjection"
        private const val REQUEST_CODE = 200
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(com.gads.settings.R.layout.activity_main)
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = mpManager.getMediaProjection(resultCode, data)
            MediaProjectionHolder.set(mp)
            Log.i(TAG, "MediaProjection obtained for internal audio capture")
        } else {
            Log.w(TAG, "MediaProjection permission denied or cancelled (resultCode=$resultCode)")
        }
        finish()
    }
}
