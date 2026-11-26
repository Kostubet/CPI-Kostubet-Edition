package com.cosmod.launcherglass.util

import kotlin.math.roundToInt

internal object ContourCoverage {
    fun alpha(coverage: Int, depth: Float, width: Float, feather: Float): Int {
        if (coverage == 0) return 0
        val outer = coverage / 255f
        // The native alpha already describes where a partial pixel crosses the edge.
        // Opaque pixels use the distance field to locate the inner boundary.
        val edge = if (coverage == 255) depth else outer
        if (edge >= width + 1f) return 0

        val innerEnd = (edge - width).coerceIn(0f, 1f)
        if (feather <= 0f) return ((outer - innerEnd) * 255f).roundToInt().coerceIn(0, coverage)

        // Integrate the coverage of the inset shape across the feather, including
        // the fully covered part before the pixel's antialiasing ramp.
        val insetStart = width - feather
        val innerStart = (edge - insetStart).coerceIn(0f, 1f)
        val opaqueSpan = (edge - insetStart - 1f).coerceIn(0f, feather)
        val inner = (opaqueSpan + (innerStart + innerEnd) * (innerStart - innerEnd) * .5f) / feather
        return ((outer - inner) * 255f).roundToInt().coerceIn(0, coverage)
    }
}
