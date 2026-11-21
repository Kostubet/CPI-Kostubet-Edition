package com.cosmod.launcherglass.util

internal object LauncherGridIconScale {
    fun interpolate(normal: Float, enlarged: Float, progress: Float): Float =
        normal + (enlarged - normal) * progress

    fun fit(
        requested: Float, nativeRatio: Float, slotWidth: Int, slotHeight: Int,
        centerX: Float, centerY: Float, iconWidth: Int, iconHeight: Int, gap: Float,
    ): Float {
        if (requested <= nativeRatio || iconWidth <= 0 || iconHeight <= 0) return requested
        val width = 2f * (minOf(centerX, slotWidth - centerX) - gap)
        val height = 2f * (minOf(centerY, slotHeight - centerY) - gap)
        val available = minOf(width / iconWidth, height / iconHeight)
        return minOf(requested, maxOf(nativeRatio, available))
    }
}
