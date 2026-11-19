package com.cosmod.launcherglass.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.cosmod.launcherglass.BuildConfig
import com.cosmod.launcherglass.R
import com.cosmod.launcherglass.ui.UiLanguage
import java.util.concurrent.TimeUnit

class CpiUpdateService : JobService() {
    private var worker: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        if (!enabled(this)) return false
        val main = Handler(Looper.getMainLooper())
        worker = Thread({
            val result = runCatching { CpiReleases.latest() }
            val current = Thread.currentThread()
            main.post {
                if (worker !== current) return@post
                worker = null
                result.getOrNull()?.let { release -> notifyRelease(this, release) }
                jobFinished(params, result.isFailure)
            }
        }, "CPI release check").also(Thread::start)
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        worker?.interrupt()
        worker = null
        return enabled(this)
    }

    companion object {
        private const val JOB_ID = 21019
        private const val CHANNEL = "cpi_release_updates"
        internal const val RELEASE_ID = 21020
        private const val ENABLED = "allow_update_alerts"
        private const val LAST = "notified_build"
        private fun prefs(context: Context) = context.getSharedPreferences("cpi_update_state", Context.MODE_PRIVATE)
        fun enabled(context: Context) = prefs(context).getBoolean(ENABLED, true)
        fun setEnabled(context: Context, enabled: Boolean) {
            prefs(context).edit().putBoolean(ENABLED, enabled).apply()
            reconcile(context)
        }

        fun reconcile(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (!enabled(context) || context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() != true) {
                scheduler.cancel(JOB_ID)
                return
            }
            if (scheduler.getPendingJob(JOB_ID) != null) return
            scheduler.schedule(JobInfo.Builder(JOB_ID, ComponentName(context, CpiUpdateService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(TimeUnit.HOURS.toMillis(8))
                .setPersisted(true)
                .build())
        }

        private fun notifyRelease(context: Context, release: CpiReleases.Release) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val prefs = prefs(context)
            if (!enabled(context) || !manager.areNotificationsEnabled() ||
                release.build <= maxOf(BuildConfig.VERSION_CODE, prefs.getInt(LAST, 0))) return
            val translated = UiLanguage.wrap(context)
            val title = translated.getString(R.string.cpi_updates_available)
            val channel = NotificationChannel(CHANNEL, translated.getString(R.string.cpi_updates_title), NotificationManager.IMPORTANCE_DEFAULT)
            manager.createNotificationChannel(channel)
            if (manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return
            val open = PendingIntent.getActivity(context, RELEASE_ID, Intent(context, CpiUpdateActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            manager.notify(RELEASE_ID, Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_catalog_bell)
                .setContentTitle(title)
                .setContentText(translated.getString(R.string.cpi_updates_version, release.version, release.build))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build())
            prefs.edit().putInt(LAST, release.build).apply()
        }
    }
}
