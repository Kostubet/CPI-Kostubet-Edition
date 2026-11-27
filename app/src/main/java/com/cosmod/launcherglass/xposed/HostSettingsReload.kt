package com.cosmod.launcherglass.xposed

import android.database.ContentObserver
import android.os.Handler
import com.cosmod.launcherglass.util.GlassLogger
import java.util.concurrent.Executors

/** Observed settings are read off the host UI thread and applied in revision order. */
internal class HostSettingsReload<T : Any>(
    private val main: Handler,
    private val read: () -> T?,
    private val apply: (T) -> Unit,
) : ContentObserver(main) {
    private var revision = 0
    private var reading = false
    private var pending = false
    private val reload = Runnable { readPending() }

    override fun onChange(selfChange: Boolean) {
        revision++
        pending = true
        if (!reading && !main.hasCallbacks(reload)) main.post(reload)
    }

    fun reloadImmediately() {
        revision++
        pending = false
        main.removeCallbacks(reload)
        readSafely()?.let(apply)
    }

    private fun readPending() {
        if (reading || !pending) return
        pending = false
        reading = true
        val requested = revision
        reader.execute {
            val snapshot = readSafely()
            main.post {
                reading = false
                if (requested == revision && snapshot != null) apply(snapshot)
                if (pending) readPending()
            }
        }
    }

    private fun readSafely(): T? = runCatching(read).onFailure { error ->
        GlassLogger.limited("HostSettings","read") { "Could not refresh settings: ${error.javaClass.simpleName}" }
    }.getOrNull()

    private companion object {
        val reader = Executors.newSingleThreadExecutor { task ->
            Thread(task, "CPI host settings")
        }
    }
}
