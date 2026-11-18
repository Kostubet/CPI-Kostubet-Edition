package com.cosmod.launcherglass.camera

import kotlin.math.ln

internal class ZoomTrajectory(target: Double, tuning: ZoomTuning) {
    private val samples = FloatArray(MAX_SECONDS * SAMPLE_HZ + 1)
    private val last: Int
    val durationSeconds: Int

    init {
        val engine = ZoomSmoothing(1.0)
        engine.aim(target, 0.0, gesture = true)
        val low = ln(minOf(1.0, engine.targetRatio))
        val span = ln(maxOf(1.0, engine.targetRatio)) - low
        var frame = 0
        do {
            val ratio = engine.step(frame.toDouble() / SAMPLE_HZ, tuning)
            samples[frame] = if (span < .001) .5f else ((ln(ratio) - low) / span).toFloat().coerceIn(0f, 1f)
            if (!engine.active || frame == samples.lastIndex) break
            frame++
        } while (true)
        last = frame
        durationSeconds = (((last + 4 * SAMPLE_HZ - 1) / (4 * SAMPLE_HZ)).coerceAtLeast(1)) * 4
    }

    fun sampleAt(fraction: Float): Float =
        samples[(fraction.coerceIn(0f, 1f) * durationSeconds * SAMPLE_HZ).toInt().coerceAtMost(last)]

    private companion object {
        const val SAMPLE_HZ = 60
        const val MAX_SECONDS = 60
    }
}
