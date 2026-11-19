package com.cosmod.launcherglass.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.PowerManager
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import java.lang.ref.WeakReference
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** One overlay/display list per window; no touch target, bitmap capture or per-particle animator. */
internal class WinterSnowfall(decor: ViewGroup) : Drawable(), Choreographer.FrameCallback {
    private class Flake(var x: Float, var y: Float, val size: Float, val speed: Float,
        val phase: Float, val rotation: Float)
    private class Ledge(val view: WeakReference<View>, val radius: Float, val priority: Int) {
        val bounds = RectF()
        var opacity = 1f
    }
    private class Drift(val ledge: Ledge, val fraction: Float, val size: Float, val life: Float,
        var age: Float = 0f)

    private val root = WeakReference(decor)
    private val density = decor.resources.displayMetrics.density
    private val power = decor.context.getSystemService(PowerManager::class.java)
    private val clock = Choreographer.getInstance()
    private val random = Random.Default
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val crystal = Path().apply {
        repeat(6) { i ->
            val a = i * PI / 3
            val x = cos(a).toFloat(); val y = sin(a).toFloat()
            moveTo(0f, 0f); lineTo(x, y)
            moveTo(x * .62f + y * .22f, y * .62f - x * .22f)
            lineTo(x * .48f, y * .48f)
            lineTo(x * .62f - y * .22f, y * .62f + x * .22f)
        }
    }
    private val flakes = Array(34) { Flake(random.nextFloat(), random.nextFloat(),
        .9f + random.nextFloat() * 2.4f, 15f + random.nextFloat() * 23f,
        random.nextFloat() * 6.28f, random.nextFloat() * 360f) }
    private val ledges = ArrayList<Ledge>(24)
    private val drifts = ArrayList<Drift>(10)
    private val visible = Rect()
    private val location = IntArray(2)
    private val origin = IntArray(2)
    private var active = false
    private var scheduled = false
    private var enabled = false
    private var lastFrame = 0L
    private var lastDiscovery = 0L
    private var seconds = 0f
    private var opacity = 255
    private var batterySaver = false
    private var lastPowerCheck = 0L

    fun resume() { active = true; refreshEnabled() }
    fun pause() {
        active = false
        clock.removeFrameCallback(this)
        scheduled = false
        lastFrame = 0L
    }
    fun refreshEnabled() {
        val host = root.get() ?: return
        enabled = WinterEffects.snowEnabled(host.context)
        if (!enabled) {
            clock.removeFrameCallback(this)
            scheduled = false
            drifts.clear()
            lastFrame = 0L
        }
        invalidateSelf()
        schedule()
    }
    fun dispose() {
        pause()
        root.get()?.overlay?.remove(this)
        ledges.clear(); drifts.clear()
        root.clear()
    }
    private fun schedule() {
        if (!active || !enabled || scheduled) return
        scheduled = true
        clock.postFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        scheduled = false
        val host = root.get() ?: return
        if (!active || !enabled) return
        if (!host.isAttachedToWindow || host.windowVisibility != View.VISIBLE || !host.isShown) {
            lastFrame = 0L
            scheduled = true
            clock.postFrameCallbackDelayed(this, 250)
            return
        }
        if (frameTimeNanos - lastPowerCheck > 1_000_000_000L) {
            batterySaver = power?.isPowerSaveMode == true
            lastPowerCheck = frameTimeNanos
        }
        val interval = if (batterySaver) 33_000_000L else 16_000_000L
        if (lastFrame != 0L && frameTimeNanos - lastFrame < interval) { schedule(); return }
        val dt = if (lastFrame == 0L) 0f else min((frameTimeNanos - lastFrame) / 1e9f, .05f)
        lastFrame = frameTimeNanos
        setBounds(0, 0, host.width, host.height)
        if (frameTimeNanos - lastDiscovery > 300_000_000L) {
            discover(host)
            lastDiscovery = frameTimeNanos
        }
        positionLedges(host)
        seconds += dt
        val w = host.width.toFloat().coerceAtLeast(1f)
        val h = host.height.toFloat().coerceAtLeast(1f)
        for (flake in flakes) {
            val before = flake.y * h
            flake.y += flake.speed * density * dt / h
            val x = flake.x * w + sin(seconds * .65f + flake.phase) * 12f * density
            if (drifts.size < 10) {
                val landing = ledges.firstOrNull { ledge ->
                    val r = ledge.bounds
                    val edge = r.top + arcHeight(x - r.left, r.width(), ledge.radius)
                    !r.isEmpty && x > r.left + 5 * density && x < r.right - 5 * density &&
                        before <= edge && flake.y * h >= edge &&
                        drifts.none { it.ledge === ledge && kotlin.math.abs(it.fraction * r.width() + r.left - x) < 14 * density }
                }
                if (landing != null && random.nextFloat() < .48f) {
                    drifts.add(Drift(landing, ((x - landing.bounds.left) / landing.bounds.width()).coerceIn(.04f, .96f),
                        2.5f + flake.size, 1.6f + random.nextFloat() * 1.5f))
                    flake.y = -.02f; flake.x = random.nextFloat()
                }
            }
            if (flake.y > 1.02f) { flake.y = -.02f; flake.x = random.nextFloat() }
        }
        val iterator = drifts.iterator()
        while (iterator.hasNext()) {
            val drift = iterator.next()
            drift.age += dt
            if (drift.age > drift.life + 1.5f || drift.ledge.bounds.isEmpty) iterator.remove()
        }
        invalidateSelf()
        schedule()
    }

    private fun discover(host: ViewGroup) {
        val candidates = WinterEffects.surfaces.entries.mapNotNull { (view, surface) ->
            if (view.isAttachedToWindow && view.rootView === host.rootView && view.isShown)
                ledges.firstOrNull { it.view.get() === view } ?: Ledge(WeakReference(view),
                    surface.radiusDp * density, surface.priority)
            else null
        }.sortedByDescending { it.priority }.take(24)
        ledges.clear(); ledges.addAll(candidates)
        drifts.removeAll { it.ledge !in ledges }
    }

    private fun positionLedges(host: ViewGroup) {
        host.getLocationOnScreen(origin)
        val status = host.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.statusBars())?.top ?: 0
        for (ledge in ledges) {
            val view = ledge.view.get()
            if (view == null || !view.isShown || view.alpha < .3f || !view.getGlobalVisibleRect(visible)) {
                ledge.bounds.setEmpty(); continue
            }
            var opacity = view.alpha
            var parent = view.parent as? View
            while (parent != null && parent !== host) {
                opacity *= parent.alpha
                parent = parent.parent as? View
            }
            ledge.opacity = opacity
            if (opacity < .3f) { ledge.bounds.setEmpty(); continue }
            view.getLocationOnScreen(location)
            if (location[1] < visible.top - density || location[1] - origin[1] < status || view.scaleX < .9f) {
                ledge.bounds.setEmpty(); continue
            }
            ledge.bounds.set((visible.left - origin[0]).toFloat(), (location[1] - origin[1]).toFloat(),
                (visible.right - origin[0]).toFloat(), (visible.bottom - origin[1]).toFloat())
            when (view) {
                is CouiRootNavigation -> view.snowBounds(ledge.bounds)
                is CouiSearchHost -> view.snowBounds(ledge.bounds)
            }
        }
    }

    private fun arcHeight(x: Float, width: Float, radius: Float): Float {
        val r = min(radius, width * .5f)
        if (r <= 0f) return 0f
        val dx = when { x < r -> r - x; x > width - r -> x - width + r; else -> 0f }
        return r - sqrt((r * r - dx * dx).coerceAtLeast(0f))
    }

    override fun draw(canvas: Canvas) {
        if (!enabled) return
        paint.color = 0xffeaf5ff.toInt()
        for (flake in flakes) {
            val x = flake.x * bounds.width() + sin(seconds * .65f + flake.phase) * 12f * density
            val y = flake.y * bounds.height()
            paint.alpha = ((.32f + flake.size * .15f) * opacity).toInt().coerceIn(0, 230)
            if (flake.size < 1.8f) {
                paint.style = Paint.Style.FILL
                canvas.drawCircle(x, y, flake.size * density * .5f, paint)
            } else {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = .3f
                val save = canvas.save()
                canvas.translate(x, y)
                canvas.rotate(flake.rotation + seconds * 9f)
                canvas.scale(flake.size * density, flake.size * density)
                canvas.drawPath(crystal, paint)
                canvas.restoreToCount(save)
            }
        }
        paint.style = Paint.Style.FILL
        for (drift in drifts) {
            val r = drift.ledge.bounds
            if (r.isEmpty) continue
            val slide = ((drift.age - drift.life) / 1.5f).coerceIn(0f, 1f)
            val ease = slide * slide * (3f - 2f * slide)
            val grow = (drift.age / .35f).coerceIn(0f, 1f)
            val x = r.left + drift.fraction * r.width() + ease * 8 * density * sin(drift.fraction * 8)
            val y = r.top + arcHeight(x - r.left, r.width(), drift.ledge.radius) - density + ease * min(r.height() * .4f, 58 * density)
            paint.alpha = (opacity * drift.ledge.opacity * .86f * grow * (1f - ease)).toInt()
            val s = drift.size * density * grow
            canvas.drawOval(x - s, y - s * .36f, x + s, y + s * .12f, paint)
            canvas.drawOval(x - s * .55f, y - s * .65f, x + s * .3f, y + s * .06f, paint)
        }
    }
    override fun setAlpha(alpha: Int) { opacity = alpha; invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
