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
import android.util.Log
import com.cosmod.launcherglass.BuildConfig
import com.cosmod.launcherglass.R
import com.cosmod.launcherglass.ui.UiLanguage
import java.util.concurrent.TimeUnit

class CpiUpdateService : JobService() {
    private var worker: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        if (!enabled(this) || !canNotify(this)) return false
        worker = Thread({
            val result = runCatching { CpiReleases.latest(fresh = true) }
            val current = Thread.currentThread()
            main.post {
                if (worker !== current) return@post
                worker = null
                result.onSuccess { onCheckCompleted(this, it) }
                    .onFailure { Log.w("CPI.Updates", "Scheduled release check failed", it) }
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
        internal const val CHANNEL = "cpi_release_updates"
        internal const val RELEASE_ID = 21020
        private const val ENABLED = "allow_update_alerts"
        private const val LAST = "notified_build"
        private const val INSTALLED = "installed_build"
        private const val LAST_CHECK = "last_check"
        private val period = TimeUnit.HOURS.toMillis(4)
        private val foregroundDelay = TimeUnit.MINUTES.toMillis(30)
        private val main = Handler(Looper.getMainLooper())
        private var checking = false
        private fun prefs(context: Context) = context.getSharedPreferences("cpi_update_state", Context.MODE_PRIVATE)

        fun enabled(context: Context) = prefs(context).getBoolean(ENABLED, true)

        fun canNotify(context: Context): Boolean {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return false
            return manager.areNotificationsEnabled() &&
                manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
        }

        fun setEnabled(context: Context, enabled: Boolean) {
            prefs(context).edit().putBoolean(ENABLED, enabled).apply()
            if (!enabled) context.getSystemService(NotificationManager::class.java)?.cancel(RELEASE_ID)
            reconcile(context)
        }

        fun reconcile(context: Context, foreground: Boolean = false) {
            val state = prefs(context)
            val manager = context.getSystemService(NotificationManager::class.java)
            if (state.getInt(INSTALLED, 0) != BuildConfig.VERSION_CODE) {
                state.edit().putInt(INSTALLED, BuildConfig.VERSION_CODE).putInt(LAST, BuildConfig.VERSION_CODE)
                    .remove(LAST_CHECK).apply()
                manager?.cancel(RELEASE_ID)
            }
            if (enabled(context) && manager != null && manager.getNotificationChannel(CHANNEL) == null) {
                manager.createNotificationChannel(NotificationChannel(CHANNEL,
                    UiLanguage.wrap(context).getString(R.string.cpi_updates_title), NotificationManager.IMPORTANCE_DEFAULT))
            }
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (!enabled(context) || !canNotify(context)) {
                scheduler.cancel(JOB_ID)
                return
            }
            if (scheduler.getPendingJob(JOB_ID)?.intervalMillis != period) {
                scheduler.schedule(JobInfo.Builder(JOB_ID, ComponentName(context, CpiUpdateService::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(period)
                    .setPersisted(true)
                    .build())
            }
            if (foreground) checkForAlert(context)
        }

        internal fun checkForAlert(context: Context, force: Boolean = false) {
            if (checking || !enabled(context) || !canNotify(context)) return
            val app = context.applicationContext
            val state = prefs(app)
            val now = System.currentTimeMillis()
            if (!force && now - state.getLong(LAST_CHECK, 0L) in 0L until foregroundDelay) return
            checking = true
            state.edit().putLong(LAST_CHECK, now).apply()
            CpiUpdateInstaller.executor.execute {
                val result = runCatching { CpiReleases.latest(fresh = true) }
                main.post {
                    checking = false
                    result.onSuccess { onCheckCompleted(app, it) }
                        .onFailure { Log.w("CPI.Updates", "Foreground release check failed", it) }
                }
            }
        }

        internal fun onCheckCompleted(context: Context, release: CpiReleases.Release?) {
            if (!enabled(context) || !canNotify(context)) return
            prefs(context).edit().putLong(LAST_CHECK, System.currentTimeMillis()).apply()
            if (release != null) notifyRelease(context, release)
        }

        private fun notifyRelease(context: Context, release: CpiReleases.Release) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val prefs = prefs(context)
            if (!enabled(context) || !canNotify(context) ||
                release.build <= maxOf(BuildConfig.VERSION_CODE, prefs.getInt(LAST, 0))) return
            val translated = UiLanguage.wrap(context)
            val title = translated.getString(R.string.cpi_updates_available)
            val open = PendingIntent.getActivity(context, RELEASE_ID, Intent(context, CpiUpdateActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            try {
                manager.notify(RELEASE_ID, Notification.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_catalog_bell)
                    .setContentTitle(title)
                    .setContentText(translated.getString(R.string.cpi_updates_version, release.version, release.build))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build())
                prefs.edit().putInt(LAST, release.build).apply()
            } catch (error: SecurityException) {
                Log.w("CPI.Updates", "Notification permission changed during the release check", error)
            }
        }
    }
}
