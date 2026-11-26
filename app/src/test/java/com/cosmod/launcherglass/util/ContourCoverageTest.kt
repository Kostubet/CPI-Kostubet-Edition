package com.cosmod.launcherglass.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContourCoverageTest {
    @Test fun subpixelOutlinePreservesCoverageAtDifferentEdgePositions() {
        assertEquals(64, ContourCoverage.alpha(255, 1f, .25f, 0f))
        assertEquals(64, ContourCoverage.alpha(128, .5f, .25f, 0f))
        assertEquals(32, ContourCoverage.alpha(32, 0f, .25f, 0f))
        assertEquals(32, ContourCoverage.alpha(255, 1.125f, .25f, 0f))
        assertEquals(0, ContourCoverage.alpha(255, 1.25f, .25f, 0f))
        assertEquals(0, ContourCoverage.alpha(0, 0f, .25f, 0f))
    }

    @Test fun innerFeatherReducesOpacityAndNeverExtendsOutsideTheNativeMask() {
        assertEquals(32, ContourCoverage.alpha(32, 0f, .5f, .25f))
        assertEquals(96, ContourCoverage.alpha(255, 1f, .5f, .25f))
        val fullyCovered = ContourCoverage.alpha(255, 1.3f, .5f, .25f)
        assertTrue(fullyCovered in 1..50)
        assertEquals(0, ContourCoverage.alpha(255, 1.5f, .5f, .25f))
    }
}
