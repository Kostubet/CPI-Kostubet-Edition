package com.cosmod.launcherglass.xposed

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import android.view.View
import com.cosmod.launcherglass.util.GlassLogger
import com.cosmod.launcherglass.util.PerformancePolicy
import com.cosmod.launcherglass.util.ShadeGlassRenderer.Frame
import java.util.WeakHashMap
import java.util.concurrent.Executors
import kotlin.math.ceil

/** Main-thread lifecycle, one shared frame and one compositor request in flight. */
internal class ShadeBackdrop(context: Context, private val onHidden: (View) -> Unit,
    private val onIdle: () -> Unit, private val onFailure: (Throwable) -> Unit) {
    private class Reader(val visible: (View) -> Boolean) {
        val bounds = RectF()
        var padding = 0
    }

    private val power = context.getSystemService(PowerManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val worker by lazy { Executors.newSingleThreadExecutor { Thread(it,"CPI-ShadeBackdrop") } }
    private val readers = WeakHashMap<View,Reader>()
    private val visibleRect = Rect()
    private val metrics = DisplayMetrics()
    private val tick = Runnable { runCatching(::sample).onFailure(onFailure) }
    private var frame: Frame? = null
    private var rotation = -1
    private var epoch = 0
    private var queued = false
    private var busy = false
    private var retryAt = 0L
    private val clock = SampleFrameClock()
    private var captures = 0
    private var consecutiveFailures = 0

    fun diagnostics() = "frames=$captures, readers=${readers.size}, busy=$busy, misses=$consecutiveFailures"

    fun frameFor(view: View, bounds: RectF, padding: Int, visible: (View) -> Boolean): Frame? {
        if (view.display?.displayId != Display.DEFAULT_DISPLAY) return null
        val reader = readers[view]?.takeIf { it.visible === visible } ?: Reader(visible).also { readers[view] = it }
        reader.padding = padding
        reader.bounds.set(bounds)
        schedule()
        return frame?.takeIf { rotation == view.display?.rotation }
    }

    fun clear() {
        epoch++
        main.removeCallbacks(tick)
        queued = false
        val notify = if (frame != null) readers.keys.weakSnapshot() else emptyList()
        readers.clear()
        frame = null
        rotation = -1
        retryAt = 0L
        clock.reset()
        consecutiveFailures = 0
        onIdle()
        notify.forEach(onHidden)
    }

    private fun schedule() {
        if (queued || busy || readers.isEmpty()) return
        queued = true
        val remaining = clock.remainingNs(System.nanoTime(), PerformancePolicy.captureFps())
        val delay = (remaining + 999_999L) / 1_000_000L
        main.postAtTime(tick, maxOf(SystemClock.uptimeMillis() + delay, retryAt))
    }

    private fun isVisible(view: View): Boolean {
        if (!view.isAttachedToWindow || !view.isShown || view.windowVisibility != View.VISIBLE ||
            !view.getGlobalVisibleRect(visibleRect) || visibleRect.isEmpty) return false
        var current: View? = view
        while (current != null) {
            if (current.alpha < .025f) return false
            current = current.parent as? View
        }
        return true
    }

    @Suppress("DEPRECATION")
    private fun sample() {
        queued = false
        if (power?.isInteractive != true || keyguard?.isKeyguardLocked != false) {
            clear()
            return
        }
        val snapshot = readers.keys.weakSnapshot()
        for (view in snapshot) {
            val reader = readers[view] ?: continue
            if (!isVisible(view) || !reader.visible(view)) {
                readers.remove(view)
                if (frame != null) onHidden(view)
            }
        }
        if (readers.isEmpty()) {
            clear()
            return
        }
        val first = readers.keys.firstOrNull() ?: return
        val display = first.display ?: return
        display.getRealMetrics(metrics)
        val requestedRotation = display.rotation
        val area = Rect(0,0,metrics.widthPixels,metrics.heightPixels)
        if (area.isEmpty) return
        // Keep a blur/refraction margin around all live readers, then snap the
        // crop to stable bands so small animation steps reuse capture arguments.
        var top = area.bottom
        var bottom = 0
        for (reader in readers.values) {
            top = minOf(top,reader.bounds.top.toInt()-reader.padding)
            bottom = maxOf(bottom,ceil(reader.bounds.bottom).toInt()+reader.padding)
        }
        val band = (area.height()/8).coerceAtLeast(1)
        val displayHeight = area.bottom
        area.top = (top.coerceIn(0,displayHeight)/band)*band
        area.bottom = minOf(displayHeight,((bottom.coerceIn(0,displayHeight)+band-1)/band)*band)
        if (area.isEmpty) return
        val economy = power.isPowerSaveMode || PerformancePolicy.economy
        val width = if (economy) 640 else 1080
        val layers = AlertBackdrop.excludedSurfaces()
        if (layers.isEmpty()) {
            // A capture without exclusions would feed back into the material.
            retryAt = SystemClock.uptimeMillis() + 80L
            schedule()
            return
        }
        val token = epoch
        clock.record(System.nanoTime(), PerformancePolicy.captureFps())
        busy = true
        worker.execute {
            val result = runCatching { AlertBackdrop.capture(area,minOf(1f,width.toFloat()/area.width()),layers) }
            main.post {
                busy = false
                if (token == epoch && readers.isNotEmpty()) {
                    val image = result.getOrElse {
                        if (++consecutiveFailures >= 5) {
                            onFailure(it)
                        } else {
                            retryAt = SystemClock.uptimeMillis() + (80L shl (consecutiveFailures-1))
                            if (frame != null) {
                                frame = null
                                readers.keys.weakSnapshot().forEach(onHidden)
                            }
                            GlassLogger.limited("ShadeGlass","transient-backdrop") { "Backdrop unavailable: ${it.javaClass.simpleName}" }
                            schedule()
                        }
                        return@post
                    }
                    consecutiveFailures = 0
                    frame = Frame(image,area)
                    rotation = requestedRotation
                    captures++
                    retryAt = 0L
                    readers.keys.weakSnapshot().forEach { it.postInvalidateOnAnimation() }
                } else result.getOrNull()?.recycle()
                schedule()
            }
        }
    }
}
