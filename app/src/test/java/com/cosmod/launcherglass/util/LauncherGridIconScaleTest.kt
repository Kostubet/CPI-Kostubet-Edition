package com.cosmod.launcherglass.util

import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherGridIconScaleTest {
    @Test
    fun keepsStretchedShortcutInsideBothCells() {
        assertEquals(1.03f, LauncherGridIconScale.fit(1.18f, 1f, 420, 245, 210f, 119f, 392, 177, 8f), .001f)
    }

    @Test
    fun checksBothAxesAroundTheExistingCenter() {
        assertEquals(1.05f, LauncherGridIconScale.fit(1.18f, 1f, 245, 410, 113f, 212f, 200, 280, 8f), .001f)
        assertEquals(1.18f, LauncherGridIconScale.fit(1.18f, 1f, 245, 245, 122f, 122f, 180, 180, 8f), .001f)
    }

    @Test
    fun capsManualEnlargementButPreservesNativeAndSmallerSizes() {
        assertEquals(.92f, LauncherGridIconScale.fit(1f, .8f, 200, 200, 100f, 100f, 200, 200, 8f), .001f)
        assertEquals(1f, LauncherGridIconScale.fit(1.18f, 1f, 200, 200, 100f, 100f, 200, 200, 8f), .001f)
        assertEquals(.9f, LauncherGridIconScale.fit(.9f, 1f, 200, 200, 100f, 100f, 200, 200, 8f), .001f)
    }
}
