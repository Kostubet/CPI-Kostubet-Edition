package com.cosmod.launcherglass.camera

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sign
import kotlin.math.sqrt

internal enum class ZoomCurve {
    LINEAR, EASE_IN, EASE_OUT, EASE_IN_OUT, S_CURVE, SPRING, BEZIER;

    fun rate(progress: Double, p1: Double, p2: Double): Double {
        val t = progress.coerceIn(0.0, 1.0)
        val u = 1.0 - t
        return when (this) {
            LINEAR -> 1.0
            EASE_IN -> .3 + 1.2 * t
            EASE_OUT -> .3 + 1.2 * u
            EASE_IN_OUT -> .35 + 1.25 * (1 - abs(2 * t - 1))
            S_CURVE -> .35 + 1.25 * (4 * t * u)
            SPRING -> .65 + .65 * (1 - exp(-7 * t))
            BEZIER -> .25 + 3 * (u * u * p1 + 2 * u * t * (p2 - p1) + t * t * (1 - p2))
        }
    }
}

internal enum class ZoomMode { IPHONE, CINEMATIC, RESPONSIVE, ADAPTIVE, CUSTOM }

internal data class ZoomTuning(
    val speedIn: Double = 1.05,
    val speedOut: Double = 1.15,
    val maxSpeed: Double = 1.4,
    val smoothing: Double = 6.0,
    val acceleration: Double = 4.5,
    val braking: Double = 6.0,
    val jerk: Double = 27.0,
    val inertia: Double = .08,
    val response: Double = .04,
    val sensitivity: Double = 1.0,
    val curve: ZoomCurve = ZoomCurve.S_CURVE,
    val bezier: Pair<Double, Double> = .2 to .85,
    val adaptive: Boolean = false,
) {
    companion object {
        fun forMode(mode: ZoomMode, custom: ZoomTuning) = when (mode) {
            ZoomMode.IPHONE -> ZoomTuning()
            ZoomMode.CINEMATIC -> ZoomTuning(speedIn = .55, speedOut = .62, maxSpeed = .7, acceleration = 2.5,
                braking = 3.0, jerk = 10.0, smoothing = 4.0, response = .08)
            ZoomMode.RESPONSIVE -> ZoomTuning(speedIn = 1.8, speedOut = 2.0, maxSpeed = 2.5, acceleration = 9.0,
                braking = 12.0, jerk = 55.0, smoothing = 10.0, inertia = .04, response = .02)
            ZoomMode.ADAPTIVE -> ZoomTuning(adaptive = true)
            ZoomMode.CUSTOM -> custom
        }
    }
}

/** Speed and acceleration operate in log-ratio space so equal perceived zoom steps take equal time. */
internal class ZoomSmoothing(initial: Double) {
    private var position = ln(initial)
    private var target = position
    private var filtered = position
    private var origin = position
    private var velocity = 0.0
    private var acceleration = 0.0
    private var targetRate = 0.0
    private var gestureInput = false
    private var lastInputTime = Double.NaN
    private var lastFrameTime = Double.NaN
    val ratio get() = exp(position)
    val targetRatio get() = exp(target)
    val active get() = position != target

    fun aim(ratio: Double, now: Double, gesture: Boolean) {
        if (!ratio.isFinite() || ratio <= 0) return
        val next = ln(ratio)
        val previous = target
        gestureInput = gesture
        val gap = now - lastInputTime
        if (gap in .001..0.35) targetRate = .55 * targetRate + .45 * (abs(next - previous) / gap)
        else targetRate = 0.0
        lastInputTime = now
        if (!active || (previous - position).sign != (next - position).sign) origin = position
        if (!active) lastFrameTime = now
        target = next
    }

    fun step(now: Double, tuning: ZoomTuning): Double {
        if (!active || !now.isFinite()) return ratio
        val elapsed = now - lastFrameTime
        if (!elapsed.isFinite() || elapsed <= 0) return ratio
        lastFrameTime = now
        var remaining = elapsed.coerceAtMost(.1)
        while (remaining > 0 && active) {
            val dt = remaining.coerceAtMost(.008)
            remaining -= dt
            advance(dt, tuning)
        }
        return ratio
    }

    private fun advance(dt: Double, tuning: ZoomTuning) {
        val sensitivity = if (gestureInput) tuning.sensitivity else 1.0
        val response = (tuning.response / sensitivity).coerceAtLeast(.005)
        filtered += (target - filtered) * (1 - exp(-dt / response))
        val distance = filtered - position
        val endDistance = target - position
        val boost = if (tuning.adaptive) (1 + .18 * targetRate + .45 * abs(endDistance)).coerceIn(1.0, 2.25) else 1.0
        targetRate *= exp(-dt * 3)
        val limit = if (distance >= 0) tuning.speedIn else tuning.speedOut
        val progress = (position - origin) / (target - origin).let { if (abs(it) < .0001) .0001 else it }
        val rate = tuning.curve.rate(progress, tuning.bezier.first, tuning.bezier.second)
        val stoppingSpeed = sqrt(2 * tuning.braking * boost * abs(distance))
        val desired = distance.sign * minOf(limit * boost * rate, tuning.maxSpeed * boost,
            stoppingSpeed, abs(distance) * tuning.smoothing * boost)
        val accelerating = velocity == 0.0 || desired.sign == velocity.sign && abs(desired) > abs(velocity)
        val accelerationCap = (if (accelerating) tuning.acceleration else tuning.braking) * boost
        val desiredAcceleration = ((desired - velocity) / tuning.inertia.coerceAtLeast(.02))
            .coerceIn(-accelerationCap, accelerationCap)
        val maxChange = tuning.jerk * boost * dt
        acceleration += (desiredAcceleration - acceleration).coerceIn(-maxChange, maxChange)
        velocity += acceleration * dt
        val next = position + velocity * dt
        if ((next - target).sign != (position - target).sign || abs(endDistance) < .0005 && abs(velocity) < .02) {
            position = target
            filtered = target
            velocity = 0.0
            acceleration = 0.0
        } else position = next
    }
}
