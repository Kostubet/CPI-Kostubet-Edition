package com.cosmod.launcherglass.util

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Row-spring model from ba31k's v1.5.2 GenieEngine. The three vertices in each row
 * share a response, so one spring per row describes their horizontal motion.
 * ColorOS projects the outer vertices through its window transaction matrix.
 */
internal class Ba31kGenieMotion {
    private val bounds = Spring()
    private val rows = Array(3) { Spring() }
    private var lastTime = Long.MIN_VALUE
    private var nominalDirection = 0.0
    private var destination = 0.0
    private var furthest = 0.0

    @Synchronized
    fun sample(openFraction: Double, iconLeft: Double, iconWidth: Double, iconRow: Double,
        strength: Double, response: Double, damping: Double, initialVelocity: Double,
        opening: Boolean, now: Long, tilt: Double = 0.0): AppWindowDeformation? {
        if (!openFraction.isFinite() || !iconLeft.isFinite() || !iconWidth.isFinite() ||
            !iconRow.isFinite() || !strength.isFinite() || !tilt.isFinite() || (strength <= 0 && tilt <= 0)) return null
        val minimized = 1 - openFraction.coerceIn(0.0, 1.0)
        val elapsed = if (lastTime == Long.MIN_VALUE) Double.POSITIVE_INFINITY else (now - lastTime) * 1e-9
        if (elapsed < 0) return null
        val fresh = elapsed > .25
        lastTime = now
        val nominal = if (opening) 0.0 else 1.0
        if (fresh || nominal != nominalDirection) {
            nominalDirection = nominal
            destination = nominal
            furthest = minimized
        } else {
            val direction = if (destination == 0.0) -1.0 else 1.0
            if ((minimized - furthest) * direction >= 0) {
                furthest = minimized
            } else if (abs(minimized - furthest) > .025 && minimized in .02.. .98) {
                destination = 1 - destination
                furthest = minimized
            }
        }
        if (fresh) {
            val start = if (abs(minimized - destination) < .002) destination else 1 - destination
            bounds.reset(start)
            for (spring in rows) {
                spring.reset(start)
                spring.velocity = (destination - start) * (initialVelocity - 1) / response
            }
        }

        val dt = if (fresh) 1.0 / 120 else elapsed.coerceAtMost(.05)
        val direction = if (destination == 0.0) -1.0 else 1.0
        val cropTrails = (minimized - bounds.position) * direction < -.018 &&
            bounds.velocity * direction >= -.001 && abs(minimized - destination) >= .001
        val step = followCrop(minimized, direction, response, damping, dt, fresh)
        bounds.advance(destination, response, damping, step)
        if (cropTrails) bounds.position = minimized
        val y = iconRow.coerceIn(-1.0, 1.0)
        val bias = if (y < 0) .75 * y else y
        rows.forEachIndexed { index, spring ->
            val row = index * .5
            val weight = if (bias >= 0) sqrt(row) else 1 - sqrt(1 - row)
            val ratio = 1 + .5 * direction * bias * (1 - 2 * weight)
            spring.advance(destination, response * ratio, damping, step)
        }

        val openFractionNow = 1 - minimized
        val handoff = ease(minimized / .12) * ease(openFractionNow / .13)
        if (handoff == 0.0) return null
        val gain = strength * (1.10 - .46 * ease((openFractionNow - .34) / .55)) * handoff
        val tiltEnvelope = tilt * handoff * (4 * minimized * openFractionNow)
        val horizontalTilt = (2 * iconLeft + iconWidth - 1).coerceIn(-1.0, 1.0) * .080 * tiltEnvelope
        val verticalTilt = -y * .061 * tiltEnvelope
        // The stock transaction supplies the crop scale. Subtract the uniformly moving
        // grid so only the shape and offset from the row springs are applied to it.
        val b = bounds.position.coerceIn(0.0, 1.0)
        val minScale = (iconWidth / .4).coerceIn(.05, .95)
        val counterScale = (1 - b + minScale * b) / (1 - b + .4 * minScale * b)
        val anchor = (iconLeft.coerceIn(0.0, 1.0) - .5) * .6
        val center = rows[1].position
        val top = rows[0].position
        val bottom = rows[2].position
        val topInset = (gain * .3 * counterScale * (top - b) + verticalTilt).coerceIn(-.18, .18)
        val bottomInset = (gain * .3 * counterScale * (bottom - b) - verticalTilt).coerceIn(-.18, .18)
        val centerShift = (center - b) * anchor
        val topShift = (gain * (centerShift + counterScale * (top - center) * anchor) + horizontalTilt).coerceIn(-.24, .24)
        val bottomShift = (gain * (centerShift + counterScale * (bottom - center) * anchor) - horizontalTilt).coerceIn(-.24, .24)
        if (maxOf(abs(topInset), abs(bottomInset), abs(topShift), abs(bottomShift)) < .0002) return null
        return AppWindowDeformation(topInset.toFloat(), -bottomInset.toFloat(),
            (1 - 2 * topInset).toFloat(), (1 - 2 * bottomInset).toFloat(),
            (.5 + topShift).toFloat(), (.5 + bottomShift).toFloat())
    }

    private fun followCrop(measured: Double, direction: Double, response: Double, damping: Double,
        dt: Double, fresh: Boolean): Double {
        if (abs(measured - destination) < .001 || bounds.velocity * direction < -.001) return dt
        val distance = (measured - bounds.position) * direction
        if (distance <= 0) return if (distance > -.018) 0.0 else dt
        var low = 0.0
        var high = if (fresh) 3 * response else maxOf(.10, 4 * dt)
        if ((bounds.evaluate(destination, response, damping, high) - measured) * direction < 0) return dt
        repeat(14) {
            val at = (low + high) * .5
            if ((bounds.evaluate(destination, response, damping, at) - measured) * direction < 0) low = at
            else high = at
        }
        return (low + high) * .5
    }

    companion object {
        fun ease(value: Double): Double {
            val t = value.coerceIn(0.0, 1.0)
            return t * t * (3 - 2 * t)
        }

        fun droplet(fraction: Double, opening: Boolean, strength: Double): Float {
            val phase = if (opening) fraction else 1 - fraction
            val hold = if (opening) .12 else .17
            return (strength * ease(phase / .06) * (1 - ease((phase - hold) / .40))).toFloat()
        }

        fun backdrop(openFraction: Double, opening: Boolean): Double {
            val progress = if (opening) openFraction else 1 - openFraction
            val appear = if (opening) .16 else .22
            val disappear = if (opening) .10 else .20
            return ease(progress / appear) * ease((1 - progress) / disappear)
        }
    }

    internal class Spring(var position: Double = 0.0, var velocity: Double = 0.0) {
        private var evaluatedVelocity = 0.0

        fun reset(value: Double) {
            position = value
            velocity = 0.0
        }

        fun evaluate(target: Double, response: Double, damping: Double, seconds: Double): Double {
            val omega = 2 * PI / response
            val displacement = position - target
            val decayRate = omega * damping
            if (damping > 1.0) {
                val root = omega * sqrt(damping * damping - 1)
                val slow = root - decayRate
                val fast = -root - decayRate
                val slowStart = (velocity - fast * displacement) / (slow - fast)
                val a = slowStart * exp(slow * seconds)
                val b = (displacement - slowStart) * exp(fast * seconds)
                evaluatedVelocity = slow * a + fast * b
                return target + a + b
            }
            val decay = exp(-decayRate * seconds)
            val impulse = velocity + decayRate * displacement
            val rate = omega * sqrt(1 - damping * damping)
            val sine = if (rate < 1e-6) seconds else sin(rate * seconds) / rate
            val cosine = cos(rate * seconds)
            val next = displacement * cosine + impulse * sine
            evaluatedVelocity = (impulse * cosine - displacement * rate * rate * sine - decayRate * next) * decay
            return target + next * decay
        }

        fun advance(target: Double, response: Double, damping: Double, seconds: Double) {
            position = evaluate(target, response, damping, seconds)
            velocity = evaluatedVelocity
        }
    }
}
