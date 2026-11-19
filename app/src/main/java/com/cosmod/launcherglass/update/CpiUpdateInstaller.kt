package com.cosmod.launcherglass.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.IntentCompat
import com.cosmod.launcherglass.R
import com.cosmod.launcherglass.ui.UiLanguage
import java.io.File

internal object CpiUpdateInstaller {
    const val STATUS = "cpi.private.UPDATE_STATUS"
    var confirmation: Intent? = null
    var failure = false
    val executor = java.util.concurrent.Executors.newSingleThreadExecutor()

    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            if (Build.VERSION.SDK_INT >= 33) setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
        }
        val id = installer.createSession(params)
        try {
            val callback = PendingIntent.getBroadcast(context, id, Intent(context, CpiUpdateReceiver::class.java),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0L, apk.length()).use { stream ->
                    apk.inputStream().use { it.copyTo(stream) }
                    session.fsync(stream)
                }
                session.commit(callback.intentSender)
            }
        } catch (error: Exception) {
            runCatching { installer.abandonSession(id) }
            throw error
        }
    }
}

class CpiUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val manager = context.getSystemService(NotificationManager::class.java)
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
                CpiUpdateInstaller.confirmation = confirm
                val localized = UiLanguage.wrap(context)
                val text = localized.getString(R.string.cpi_updates_confirm)
                manager?.createNotificationChannel(NotificationChannel("cpi_install", localized.getString(R.string.cpi_updates_title),
                    NotificationManager.IMPORTANCE_DEFAULT))
                val open = PendingIntent.getActivity(context, INSTALL_ID, confirm,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                manager?.notify(INSTALL_ID, Notification.Builder(context, "cpi_install")
                    .setSmallIcon(R.drawable.ic_catalog_bell).setContentTitle(text).setContentIntent(open).setAutoCancel(true).build())
            }
            PackageInstaller.STATUS_SUCCESS -> {
                CpiUpdateInstaller.confirmation = null
                manager?.cancel(CpiUpdateService.RELEASE_ID)
                manager?.cancel(INSTALL_ID)
            }
            else -> {
                CpiUpdateInstaller.failure = true
                CpiUpdateInstaller.confirmation = null
                manager?.cancel(INSTALL_ID)
            }
        }
        context.sendBroadcast(Intent(CpiUpdateInstaller.STATUS).setPackage(context.packageName))
    }

    companion object { internal const val INSTALL_ID = 21021 }
}
