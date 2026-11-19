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
    val speedIn: Double = 2.2,
    val speedOut: Double = 2.45,
    val maxSpeed: Double = 2.8,
    val smoothing: Double = 10.0,
    val acceleration: Double = 10.5,
    val braking: Double = 15.0,
    val jerk: Double = 80.0,
    val inertia: Double = .045,
    val response: Double = .022,
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
            ZoomMode.RESPONSIVE -> ZoomTuning(speedIn = 3.0, speedOut = 3.3, maxSpeed = 3.6, acceleration = 14.0,
                braking = 17.0, jerk = 95.0, smoothing = 13.0, inertia = .032, response = .016)
            ZoomMode.ADAPTIVE -> ZoomTuning(adaptive = true)
            ZoomMode.CUSTOM -> custom
        }
    }
}

/** Speed and acceleration operate in log-ratio space so equal perceived zoom steps take equal time. */
internal class ZoomSmoothing(initial: Double) {
    init { require(initial.isFinite() && initial > 0) }

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
        if (!ratio.isFinite() || ratio <= 0 || !now.isFinite()) return
        val next = ln(ratio)
        if (abs(next - position) < 1e-9) {
            settle(next)
            return
        }
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
        // A resumed preview should accelerate again from the last delivered ratio.
        // Do not present a long stretch of missing motion in one camera frame.
        var remaining = elapsed.coerceAtMost(.05)
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
        val endDistance = target - position
        val direction = endDistance.sign
        val distance = ((filtered - position) * direction).coerceAtLeast(0.0)
        val approach = velocity * direction
        val boost = if (tuning.adaptive) (1 + .18 * targetRate + .45 * abs(endDistance)).coerceIn(1.0, 2.25) else 1.0
        targetRate *= exp(-dt * 3)
        val limit = if (direction > 0) tuning.speedIn else tuning.speedOut
        val progress = (position - origin) / (target - origin).let { if (abs(it) < .0001) .0001 else it }
        val rate = tuning.curve.rate(progress, tuning.bezier.first, tuning.bezier.second)
        val speedCap = tuning.maxSpeed * boost
        val braking = tuning.braking * boost
        val jerk = tuning.jerk * boost
        val brakeLimit = brakingSpeed(distance, approach, acceleration * direction, braking, jerk, tuning.inertia)
        val desired = direction * minOf(limit * boost * rate, speedCap, brakeLimit, distance * tuning.smoothing * boost)
        val accelerating = velocity == 0.0 || desired.sign == velocity.sign && abs(desired) > abs(velocity)
        val accelerationCap = (if (accelerating) tuning.acceleration else tuning.braking) * boost
        val desiredAcceleration = ((desired - velocity) / tuning.inertia.coerceAtLeast(.02))
            .coerceIn(-accelerationCap, accelerationCap)
        val rise = if (direction < 0 && approach >= 0) -velocity else speedCap - velocity
        val fall = if (direction > 0 && approach >= 0) velocity else speedCap + velocity
        val availableIn = sqrt(2 * jerk * rise.coerceAtLeast(0.0))
        val availableOut = sqrt(2 * jerk * fall.coerceAtLeast(0.0))
        val cappedAcceleration = desiredAcceleration.coerceIn(-availableOut, availableIn)
        val maxChange = jerk * dt
        acceleration += (cappedAcceleration - acceleration).coerceIn(-maxChange, maxChange)
        val nextVelocity = (velocity + acceleration * dt).coerceIn(-speedCap, speedCap)
        if (approach >= 0 && nextVelocity * direction < 0) {
            velocity = 0.0
            acceleration = 0.0
        } else velocity = nextVelocity
        val next = position + velocity * dt
        if ((next - target).sign != (position - target).sign || abs(endDistance) < .0005 && abs(velocity) < .02) {
            settle(target)
        } else position = next
    }

    private fun brakingSpeed(distance: Double, velocity: Double, acceleration: Double,
        braking: Double, jerk: Double, inertia: Double): Double {
        // Reserve distance and speed while releasing the current acceleration and the actuator settles.
        val positive = acceleration.coerceAtLeast(0.0)
        val release = positive / jerk
        val reserve = velocity.coerceAtLeast(0.0) * (release + 1.35 * inertia + .025) +
            positive * release * release / 3
        val available = (distance - reserve).coerceAtLeast(0.0)
        // A triangular deceleration ramp fits short stops; longer stops reach the braking limit.
        val triangular = Math.cbrt(jerk * available * available)
        val cap = if (triangular <= braking * braking / jerk) triangular else {
            val margin = braking * braking / (2 * jerk)
            sqrt(margin * margin + 2 * braking * available) - margin
        }
        return (cap - .5 * positive * release).coerceAtLeast(0.0)
    }

    private fun settle(value: Double) {
        position = value
        target = value
        filtered = value
        velocity = 0.0
        acceleration = 0.0
    }
}
