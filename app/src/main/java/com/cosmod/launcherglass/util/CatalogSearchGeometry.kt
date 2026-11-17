package com.cosmod.launcherglass.util

internal object CatalogSearchGeometry {
    private fun ease(progress: Float): Float {
        val p = progress.coerceIn(0f, 1f)
        return p * p * (3f - 2f * p)
    }

    fun reservedWidth(progress: Float): Float =
        58f * ease(progress / .52f) + 14f * ease((progress - 1f) / .135f)

    fun top(progress: Float, expandedPx: Float, density: Float): Float {
        // Leave enough vertical clearance until the search has narrowed.
        val p = progress.coerceIn(0f, 1f)
        val travel = p * p * (.4f + .6f * p)
        return expandedPx + (6.5f * density - expandedPx) * travel
    }

    fun topWithoutOverlap(desired:Float,restartBottom:Float,horizontalGap:Int,minimumGap:Int):Float =
        if(horizontalGap>=minimumGap)desired else maxOf(desired,restartBottom)
}
