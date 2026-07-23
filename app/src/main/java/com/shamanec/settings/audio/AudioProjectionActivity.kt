package com.shamanec.settings.audio

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Requests MediaProjection permission for internal audio capture (AudioPlaybackCapture).
 *
 * Shows the system screen recording dialog and, on approval, forwards the resultCode +
 * data Intent to AudioService via startForegroundService. Android 14+ requires
 * getMediaProjection() to be called while an active foreground service of type
 * mediaProjection is running — AudioService is that owner.
 *
 * Triggered by the Go provider via (applicationId is per-flavor: com.gads.settings / com.heliofleet.settings;
 * the class must be fully qualified because the code namespace is com.shamanec.settings):
 *   adb shell am start -n com.gads.settings/com.shamanec.settings.audio.AudioProjectionActivity
 */
class AudioProjectionActivity : Activity() {
    companion object {
        private const val TAG = "AudioProjectionActivity"
        private const val REQUEST_CODE = 200
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(com.shamanec.settings.R.layout.activity_main)
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            val serviceIntent = Intent(this, AudioService::class.java).apply {
                putExtra(AudioService.EXTRA_MP_RESULT_CODE, resultCode)
                putExtra(AudioService.EXTRA_MP_DATA, data)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            Log.i(TAG, "Forwarded MediaProjection token payload to AudioService")
        } else {
            Log.w(TAG, "MediaProjection permission denied or cancelled (resultCode=$resultCode)")
        }
        finish()
    }
}
