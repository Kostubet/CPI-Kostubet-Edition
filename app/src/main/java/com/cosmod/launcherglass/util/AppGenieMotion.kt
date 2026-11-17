package com.cosmod.launcherglass.util

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/** Spring and projection equations from CherryPick Icons 0.3.20-dev's AppGenieMotion. */
internal class AppGenieMotion {
    private val bounds = Axis()
    private val rows = Array(3) { Axis() }
    private var lastFrame = 0L
    private var lastTarget = Double.NaN
    private var lastMeasured = Double.NaN
    private var directionAnchor = Double.NaN
    private var nominalOpening = false

    private class Axis {
        var position = 0.0
            private set
        var velocity = 0.0
            private set
        var evaluatedVelocity = 0.0
            private set

        fun evaluate(target: Double, frequency: Double, damping: Double, dt: Double): Double {
            val displacement = position - target
            if (damping == 1.0) {
                val impulse = velocity + frequency * displacement
                val decay = exp(-frequency * dt)
                evaluatedVelocity = (velocity - frequency * impulse * dt) * decay
                return target + (displacement + impulse * dt) * decay
            }
            val root = sqrt(damping * damping - 1.0)
            val slow = (root - damping) * frequency
            val fast = -(root + damping) * frequency
            val slowStart = (velocity - fast * displacement) / (slow - fast)
            val slowPart = slowStart * exp(slow * dt)
            val fastPart = (displacement - slowStart) * exp(fast * dt)
            evaluatedVelocity = slow * slowPart + fast * fastPart
            return target + slowPart + fastPart
        }

        fun advance(target: Double, frequency: Double, damping: Double, dt: Double) {
            position = evaluate(target, frequency, damping, dt)
            velocity = evaluatedVelocity
        }

        fun reset(value: Double) {
            position = value
            velocity = 0.0
        }

        fun pin(value:Double){position=value}
    }

    @Synchronized
    fun update(minimized: Double, iconLeft: Double, iconWidth: Double, screenY: Double,
               intensity: Double, opening: Boolean, openResponse: Double, closeResponse: Double,
               damping: Double, frameTime: Long): AppWindowDeformation? {
        val elapsed = frameTime - lastFrame
        val fresh = lastFrame == 0L || elapsed > 250_000_000L
        val dt = if (fresh) 1.0 / 120.0 else (elapsed * 1e-9).coerceIn(0.0, 0.05)
        val target = targetForBounds(minimized, opening, fresh)
        if (fresh) {
            val initial = if (abs(minimized - target) < .001) target else 1.0 - target
            bounds.reset(initial)
            rows.forEach { it.reset(initial) }
        }
        if (fresh || target != lastTarget || minimized != lastMeasured || dt >= .003) {
            lastFrame = frameTime
            lastTarget = target
            lastMeasured = minimized
            val frequency = 2 * PI / if (target == 0.0) openResponse else closeResponse
            val direction = if (target == 0.0) -1.0 else 1.0
            val trailingCrop=!fresh && abs(minimized-target)>=.001 &&
                bounds.velocity*direction>=-.001 && (minimized-bounds.position)*direction<=-.02
            val step = followBounds(minimized, target, direction, frequency, damping, dt, fresh)
            bounds.advance(target, frequency, damping, step)
            if(trailingCrop)bounds.pin(minimized)
            val y = screenY.coerceIn(-1.0, 1.0)
            val bias = if (y < 0.0) .75 * y else y
            rows.forEachIndexed { index, axis ->
                val row = index * .5
                val weight = if (bias >= 0.0) sqrt(row) else 1.0 - sqrt(1.0 - row)
                val responseRatio = 1.0 + .5 * bias * direction * (1.0 - 2.0 * weight)
                axis.advance(target, frequency / responseRatio, damping, step)
            }
        }
        return if (intensity > 0.0) project(iconLeft, iconWidth, intensity) else null
    }

    private fun targetForBounds(measured: Double, opening: Boolean, fresh: Boolean): Double {
        if (fresh || opening != nominalOpening) {
            nominalOpening = opening
            directionAnchor = measured
            return if (opening) 0.0 else 1.0
        }
        if (lastTarget == 0.0) {
            directionAnchor = minOf(directionAnchor, measured)
            if (measured - directionAnchor > .018 && measured > .025) {
                directionAnchor = measured
                return 1.0
            }
        } else {
            directionAnchor = maxOf(directionAnchor, measured)
            if (directionAnchor - measured > .018 && measured < .975) {
                directionAnchor = measured
                return 0.0
            }
        }
        return lastTarget
    }

    private fun followBounds(measured: Double, target: Double, direction: Double,
                             frequency: Double, damping: Double, dt: Double, fresh: Boolean): Double {
        if (abs(measured - target) < .001 || bounds.velocity * direction < -.001) return dt
        val distance = (measured - bounds.position) * direction
        if (distance <= 0.0) return if (distance > -.02) 0.0 else dt

        var high = if (fresh) 24 / frequency else maxOf(.1, 4 * dt)
        if ((bounds.evaluate(target, frequency, damping, high) - measured) * direction < 0.0) return dt
        var low = 0.0
        var time = dt.coerceIn(low, high)
        repeat(8) {
            val error = (bounds.evaluate(target, frequency, damping, time) - measured) * direction
            if (abs(error) < 8e-5) return time
            if (error < 0.0) low = time else high = time
            val velocity = bounds.evaluatedVelocity * direction
            val candidate = if (velocity > 0.0) time - error / velocity else Double.NaN
            time = if (candidate > low && candidate < high) candidate else (low + high) * .5
        }
        return time
    }

    private fun project(iconLeft: Double, iconWidth: Double, intensity: Double): AppWindowDeformation? {
        val progress = bounds.position.coerceIn(0.0, 1.0)
        val remaining = 1.0 - progress
        val minimized = (iconWidth / .4).coerceIn(.05, .95) * progress
        val scale = (remaining + minimized) / (remaining + .4 * minimized)
        val center = rows[1].position
        val top = rows[0].position
        val bottom = rows[2].position
        val anchor = (iconLeft.coerceIn(0.0, 1.0) - .5) * .6
        val shift = (center - progress) * anchor
        val topOffset = intensity * .3 * scale * (top - progress)
        val bottomOffset = -intensity * .3 * scale * (bottom - progress)
        val topCenter = intensity * (shift + (top - center) * scale * anchor)
        val bottomCenter = intensity * (shift + (bottom - center) * scale * anchor)
        if (maxOf(abs(topOffset), abs(bottomOffset), abs(topCenter), abs(bottomCenter)) < 2e-5) return null
        return AppWindowDeformation(topOffset.toFloat(), bottomOffset.toFloat(),
            (1 - 2 * topOffset).coerceAtLeast(.2).toFloat(), (1 + 2 * bottomOffset).coerceAtLeast(.2).toFloat(),
            (.5 + topCenter).toFloat(), (.5 + bottomCenter).toFloat())
    }
}

internal data class AppWindowDeformation(
    val topOffset: Float, val bottomOffset: Float, val topWidth: Float, val bottomWidth: Float,
    val topCenter: Float, val bottomCenter: Float
) {
    fun isVisible(width: Float, height: Float): Boolean =
        maxOf(abs(topOffset), abs(bottomOffset)) * height >= .35f ||
            maxOf(abs(topCenter - .5f), abs(bottomCenter - .5f),
                abs(topWidth - 1f) * .5f, abs(bottomWidth - 1f) * .5f) * width >= .35f

    fun writeMatrix(out: FloatArray, left: Float, top: Float, width: Float, height: Float) {
        val topFactor = 1f / topWidth
        val bottomFactor = 1f / bottomWidth
        val perspective = (bottomFactor - topFactor) / height
        val topY = (top + topOffset * height) * topFactor
        val bottomY = (top + height + bottomOffset * height) * bottomFactor
        val centerX = left + .5f * width
        val topX = (left + topCenter * width) * topFactor - centerX
        val bottomX = (left + bottomCenter * width) * bottomFactor - centerX
        val dx = (bottomX - topX) / height
        val dy = (bottomY - topY) / height
        out.fill(0f)
        out[0] = 1f
        out[1] = dx
        out[3] = topX - dx * top
        out[5] = dy
        out[7] = topY - dy * top
        out[10] = 1f
        out[13] = perspective
        out[15] = topFactor - perspective * top
    }

    fun writeCenteredMatrix(out: FloatArray, left: Float, top: Float, width: Float, height: Float): Boolean {
        writeMatrix(out, left, top, width, height)
        val x = left + width * .5f
        val y = top + height * .5f
        val w = out[13] * y + out[15]
        if (!w.isFinite() || w <= 0f) return false
        val dx = (x + out[1] * y + out[3]) / w - x
        val dy = (out[5] * y + out[7]) / w - y
        out[1] -= dx * out[13]
        out[3] -= dx * out[15]
        out[5] -= dy * out[13]
        out[7] -= dy * out[15]
        return out.all(Float::isFinite)
    }

    companion object {
        fun merge(base: AppWindowDeformation?, landing: AppWindowDeformation): AppWindowDeformation {
            if (base == null) return landing
            return AppWindowDeformation(
                base.topOffset + landing.topOffset,
                base.bottomOffset + landing.bottomOffset,
                (base.topWidth + landing.topWidth - 1f).coerceAtLeast(.2f),
                (base.bottomWidth + landing.bottomWidth - 1f).coerceAtLeast(.2f),
                base.topCenter + landing.topCenter - .5f,
                base.bottomCenter + landing.bottomCenter - .5f,
            )
        }

        fun droplet(progress:Float,opening:Boolean,elapsedMs:Float,speed:Float):Float {
            val time=elapsedMs*speed
            val phase=if(opening)progress else 1f-progress
            val hold=if(opening)60f else 120f
            return smoothstep(phase/.065f) * smoothstep((1f-phase)/.20f) *
                (1f-smoothstep((time-hold)/60f))
        }

        private fun smoothstep(value: Float): Float {
            val t = value.coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
    }
}
