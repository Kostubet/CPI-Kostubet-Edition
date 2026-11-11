package com.cosmod.launcherglass.ui

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.cosmod.launcherglass.R

internal object BannerTestNotification {
    private const val CHANNEL = "cpi_banner_test"
    private const val ID = 42001

    fun hasPermission(activity: Activity): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun manager(activity: Activity): NotificationManager =
        activity.getSystemService(NotificationManager::class.java).apply {
            createNotificationChannel(NotificationChannel(
                CHANNEL, activity.getString(R.string.banner_test_channel), NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setSound(null, null)
                enableVibration(false)
            })
        }

    fun openSettings(activity: Activity) {
        manager(activity)
        activity.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL))
    }

    fun post(activity: Activity) {
        val manager = manager(activity)
        if (!manager.areNotificationsEnabled() ||
            manager.getNotificationChannel(CHANNEL).importance == NotificationManager.IMPORTANCE_NONE) {
            openSettings(activity)
            return
        }
        // Closing the previous row lets a second press play the entry again.
        val previous = manager.activeNotifications.any { it.id == ID }
        if (previous) manager.cancel(ID)
        activity.window.decorView.postDelayed({
            if (activity.isDestroyed || activity.isFinishing) return@postDelayed
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(activity,
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return@postDelayed
            val open = PendingIntent.getActivity(activity, ID,
                Intent(activity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE)
            manager.notify(ID, Notification.Builder(activity, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(activity.getString(R.string.banner_test_channel))
                .setContentText(activity.getString(R.string.banner_test_text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setTimeoutAfter(5500L)
                .build())
        }, if (previous) 350L else 0L)
    }
}
