package com.cosmod.launcherglass.xposed

import android.os.Bundle
import com.cosmod.launcherglass.util.Ba31kAnimationSettings as Settings
import com.cosmod.launcherglass.util.Ba31kGenieMotion
import com.cosmod.launcherglass.util.GlassLogger
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

/** Runtime of the second engine. The ColorOS transaction adapter is shared by both engines. */
internal object Ba31kAppTransitions {
    data class Config(val opening: Boolean, val closing: Boolean, val strength: Double,
        val droplet: Double, val openSpeed: Double, val closeSpeed: Double, val bounce: Double,
        val stiffness: Double, val damping: Double, val initialVelocity: Double, val backdrop: Boolean) {
        fun speed(opening: Boolean) = if (opening) openSpeed else closeSpeed
    }

    @Volatile var config: Config? = null
        private set
    private val springs = WeakHashMap<Any, Ba31kGenieMotion>()
    private val openFrames = AtomicLong()
    private val closeFrames = AtomicLong()
    private var attemptedBackdrop = false
    private var launcherLoader: ClassLoader? = null

    @Synchronized
    fun launcherResumed(loader: ClassLoader) {
        launcherLoader = loader
        attachBackdrop()
    }

    @Synchronized
    private fun attachBackdrop() {
        val loader = launcherLoader ?: return
        if (config?.backdrop != true || attemptedBackdrop) return
        attemptedBackdrop = true
        Ba31kTransitionBackdrop.install(loader)
    }

    fun recordFrame(opening: Boolean) {
        val count = if (opening) openFrames else closeFrames
        if (count.incrementAndGet() == 1L)
            GlassLogger.i("Ba31kAppTransitions", "Native deformation active, opening=$opening")
    }

    fun diagnostics() = "ba31k window frames open=${openFrames.get()}, close=${closeFrames.get()}"

    fun update(prefs: Bundle) {
        fun flag(key: String) = prefs.getBoolean(key, Settings.booleans.getValue(key))
        fun ratio(key: String) = prefs.getInt(key, Settings.integers.getValue(key))
            .coerceIn(Settings.minimum(key),Settings.maximum(key)) / 100.0
        val next = if (flag(Settings.ENABLED)) Config(
            flag(Settings.OPEN), flag(Settings.CLOSE),
            if (flag(Settings.GENIE)) ratio(Settings.GENIE_STRENGTH) else 0.0,
            if (flag(Settings.DROPLET)) ratio(Settings.DROPLET_STRENGTH) else 0.0,
            (ratio(Settings.OPEN_SPEED) * ratio(Settings.SPEED) / ratio(Settings.DURATION)).coerceIn(.35, 2.0),
            (ratio(Settings.CLOSE_SPEED) * ratio(Settings.SPEED) / ratio(Settings.DURATION)).coerceIn(.35, 2.0),
            ratio(Settings.BOUNCE),
            ratio(Settings.STIFFNESS), ratio(Settings.DAMPING), ratio(Settings.INITIAL_VELOCITY),
            flag(Settings.APP_BLUR),
        ) else null
        synchronized(springs) {
            if (next != config) springs.clear()
            config = next
        }
        attachBackdrop()
    }

    fun deformation(owner: Any, values: Config, fraction: Double, iconLeft: Double,
        iconWidth: Double, iconRow: Double, opening: Boolean, swipe: Boolean, now: Long) =
        synchronized(springs) {
            springs.getOrPut(owner) { Ba31kGenieMotion() }
        }.sample(fraction, iconLeft, iconWidth, iconRow, values.strength,
            (if (opening) .240 else .336) / sqrt(values.stiffness) / values.speed(opening),
            values.damping + if (swipe) .10 else 0.0, values.initialVelocity, opening, now)

    /** Time-scale the existing channels together. Their native stiffness ratios shape the crop. */
    fun tuneSpring(args: Array<Any?>, values: Config, opening: Boolean, swipe: Boolean) {
        val stiffness = args[1] as? Float ?: return
        val damping = args[2] as? Float ?: return
        if (!stiffness.isFinite() || stiffness <= 0 || !damping.isFinite() || damping <= 0) return
        val speed = values.speed(opening)
        args[1] = (stiffness * speed * speed).toFloat()
        if (swipe) args[2] = (damping * (1.0 - .13 * values.bounce)).coerceAtLeast(.76).toFloat()
    }
}
