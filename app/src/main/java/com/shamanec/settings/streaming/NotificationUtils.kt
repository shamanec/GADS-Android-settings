package com.shamanec.settings.streaming

import com.shamanec.settings.BuildConfig

import android.annotation.TargetApi
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.util.Pair
import com.shamanec.settings.R

object NotificationUtils {
    private const val NOTIFICATION_ID: Int = 1991
    private const val NOTIFICATION_CHANNEL_ID = "com.shamanec.settings"
    private const val NOTIFICATION_CHANNEL_NAME = "com.shamanec.settings"

    // The getNotification method creates the notification channel and creates a notification with a low importance level.
    // It returns a Pair object containing the notification ID and the created notification.
    fun getNotification(context: Context): Pair<Int, Notification> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel(context)
        }
        val notification = createNotification(context)
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
        return Pair(NOTIFICATION_ID, notification)
    }

    // The createNotificationChannel method creates a notification channel if the device is running Android 8.0 or higher.
    // The channel has a name, ID, and low importance level. It also sets the lock screen visibility to private.
    @TargetApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel(context: Context) {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            NOTIFICATION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        )
        channel.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    // The createNotification method creates the notification using a NotificationCompat.Builder.
    // It sets the notification icon, text, and other attributes such as priority and whether it should be shown continuously (ongoing).
    // The method returns the created Notification object.
    private fun createNotification(context: Context): Notification {
        val builder = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
        builder.setSmallIcon(R.drawable.ic_gads_notification_logo)
        builder.setContentText("${BuildConfig.LOG_PREFIX} Settings is recording this device's screen..")
        builder.setOngoing(true)
        builder.setCategory(Notification.CATEGORY_SERVICE)
        builder.setPriority(Notification.PRIORITY_HIGH)
        builder.setShowWhen(true)
        return builder.build()
    }
}
