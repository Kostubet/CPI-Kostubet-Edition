package com.cosmod.launcherglass.util

import kotlin.math.pow

/** Follows native resize progress, including a gesture reversed before it reaches either endpoint. */
internal class AppGenieTrajectory {
    data class Position(val x: Float, val y: Float, val blend: Float)
    private var initial = Float.NaN

    fun sample(progress: Float, opening: Boolean): Position? {
        if (!progress.isFinite() || progress !in 0f..1f) return null
        if (initial.isNaN()) initial = progress
        val distance = if (opening) 1f - initial else initial
        val travel = if (opening) progress - initial else initial - progress
        val release = smooth(travel / (distance * .18f).coerceAtLeast(.01f))
        // The native icon takes over near both endpoints. Return to the same
        // system path before the handoff, including after a quick swipe.
        val handoff = smooth((if (opening) 1f - progress else progress) / .28f)
        return Position(curve(progress, .68f), curve(progress, .52f), release * handoff)
    }

    private fun smooth(progress: Float): Float {
        val t = progress.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun curve(progress: Float, power: Float): Float {
        // Join the curved path to a zero-velocity endpoint rather than a root's infinite slope.
        val join = .08f
        if (progress >= join) return progress.pow(power)
        val t = progress / join
        val value = join.pow(power)
        return value * ((3f - power) * t * t + (power - 2f) * t * t * t)
    }
}
