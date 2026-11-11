package com.cosmod.launcherglass.util

import kotlin.math.pow

/** Two editable keyframes with zero tilt and zero slope at both ends. */
internal data class TiltCurve(
    val firstTime: Int,
    val firstTilt: Int,
    val secondTime: Int,
    val secondTilt: Int
) {
    fun move(first: Boolean, time: Int, tilt: Int): TiltCurve =
        if (first) copy(firstTime = time.coerceIn(5, secondTime - 10), firstTilt = tilt.coerceIn(0, 100))
        else copy(secondTime = time.coerceIn(firstTime + 10, 95), secondTilt = tilt.coerceIn(0, 100))

    fun amount(phase: Float, speed: Float = 1f): Float {
        val progress = phase.coerceIn(0f, 1f)
        val time = (if (speed == 1f) progress else 1f - (1f - progress).pow(speed)) * 100f
        val tilt = when {
            time < firstTime -> interpolate(0, firstTilt, time / firstTime)
            time < secondTime -> interpolate(firstTilt, secondTilt, (time - firstTime) / (secondTime - firstTime))
            else -> interpolate(secondTilt, 0, (time - secondTime) / (100 - secondTime))
        }
        return tilt * .01f
    }

    private fun interpolate(from: Int, to: Int, fraction: Float): Float {
        val t = fraction.coerceIn(0f, 1f)
        val ease = t * t * t * (10f + t * (6f * t - 15f))
        return from + (to - from) * ease
    }

    fun values() = listOf(firstTime, firstTilt, secondTime, secondTilt)
}

internal enum class TiltDirection(
    prefix: String,
    val defaults: TiltCurve,
    private val start: Float,
    private val end: Float
) {
    OPEN("app_tilt_curve_open", TiltCurve(32, 100, 43, 96), .03f, .94f),
    CLOSE("app_tilt_curve_close", TiltCurve(49, 95, 63, 100), .06f, .92f);

    val keys = listOf("time1", "tilt1", "time2", "tilt2").map { "${prefix}_$it" }

    fun read(value: (String, Int) -> Int): TiltCurve {
        val first = value(keys[0], defaults.firstTime).coerceIn(5, 85)
        return TiltCurve(first, value(keys[1], defaults.firstTilt).coerceIn(0, 100),
            value(keys[2], defaults.secondTime).coerceIn(first + 10, 95),
            value(keys[3], defaults.secondTilt).coerceIn(0, 100))
    }

    fun amount(windowSize: Float, speed: Float, curve: TiltCurve): Float {
        val nativePhase = if (this == OPEN) windowSize else 1f - windowSize
        return curve.amount((nativePhase - start) / (end - start), speed)
    }

    fun amount(windowSize:Float,speed:Float,smoothness:Float):Float {
        val nativePhase=if(this==OPEN)windowSize else 1f-windowSize
        val phase=((nativePhase-start)/(end-start)).coerceIn(0f,1f)
        val time=1f-(1f-phase).pow(speed)
        val wave=kotlin.math.sin(Math.PI*time).toFloat()
        val soft=wave*wave
        val current=defaults.amount(phase,speed)
        return current+(soft-current)*smoothness.coerceIn(0f,1f)
    }

    companion object {
        val preferenceDefaults = entries.flatMap { it.keys.zip(it.defaults.values()) }.toMap()
    }
}
