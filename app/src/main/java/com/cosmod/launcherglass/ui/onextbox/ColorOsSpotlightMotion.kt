// From ONextBox by MiToverG422 and contributors. SPDX-License-Identifier: GPL-3.0-only
package com.cosmod.launcherglass.ui.onextbox

internal fun colorOsSpotlightRadius(
    startRadius: Float,
    endRadius: Float,
    progress: Float,
    expandOnRelease: Boolean,
): Float {
    val fraction = progress.coerceIn(0f, 1f)
    return startRadius + (endRadius - startRadius) * fraction +
        if (expandOnRelease) endRadius * 2f * (1f - fraction) else 0f
}
