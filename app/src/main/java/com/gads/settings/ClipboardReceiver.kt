package com.gads.settings

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Base64

class ClipboardReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE)
                as ClipboardManager

        when (intent.action) {

            "com.gads.settings.CLIPBOARD_GET" -> {
                val txt = cm.primaryClip
                    ?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)
                    ?.coerceToText(ctx)
                    ?.toString() ?: ""
                val b64 = Base64.encodeToString(
                    txt.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                resultCode = Activity.RESULT_OK
                resultData = b64
            }

            "com.gads.settings.CLIPBOARD_SET" -> {
                val b64 = intent.getStringExtra("text") ?: ""
                val plain = String(Base64.decode(b64, Base64.NO_WRAP))
                cm.setPrimaryClip(ClipData.newPlainText("remote", plain))
            }
        }
    }
}