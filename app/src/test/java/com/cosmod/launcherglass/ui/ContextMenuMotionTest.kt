package com.cosmod.launcherglass.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextMenuMotionTest {
    private fun cardPhase(time: Float): Float = time * ContextMenuMotion.CARD_CLOSE_MS / ContextMenuMotion.CLOSE_MS

    @Test fun openingMatchesTrackedPeakAndSettlesInTheExistingBounds() {
        val start = ContextMenuMotion.opening(0f, .1f)
        assertEquals(0f, start.alpha, 0f)
        assertEquals(1f, start.rowAlpha, 0f)
        val peak = ContextMenuMotion.opening(.19f, .1f)
        assertEquals(1.09f, peak.scale, .0001f)
        assertEquals(.060f, peak.y, .0001f)
        assertTrue(peak.alpha > peak.menuAlpha)
        assertEquals(ContextMenuMotion.Frame(), ContextMenuMotion.opening(1f, .1f))
    }

    @Test fun releaseBeginsAtTheLastRenderedPoseAndKeepsMoving() {
        val release = ContextMenuMotion.dragging(ContextMenuMotion.Frame(), -.013f, .24f, 1f)
        assertEquals(release, ContextMenuMotion.closing(0f, release, 1.2f, 1f, 800f))
        val first = ContextMenuMotion.closing(cardPhase(.001f), release, 1.2f, 1f, 800f)
        assertEquals(1.2f, (first.y - release.y) / .001f, .015f)
        val frames = (0..55).map { ContextMenuMotion.closing(cardPhase(it / 100f), release, 1.2f, 1f, 800f) }
        assertTrue(frames.zipWithNext().all { (a, b) -> b.y > a.y && b.scale <= a.scale })
    }

    @Test fun menuDisappearsBeforeTheCardAndBackdrop() {
        val start = ContextMenuMotion.Frame()
        val departing = ContextMenuMotion.closing(cardPhase(.3f), start, 0f, 1f, 0f)
        assertEquals(0f, departing.menuAlpha, 0f)
        assertTrue(departing.alpha in .5f.. .9f && departing.y > 0f && departing.scale < 1f)
        val gone = ContextMenuMotion.closing(cardPhase(.61f), start, 0f, 1f, 0f)
        assertEquals(0f, gone.alpha, 0f)
        assertTrue(gone.backdrop > 0f && gone.rowAlpha > 0f)
        assertEquals(.44f, ContextMenuMotion.closing(.56f, start, 0f, 1f, 0f).backdrop, .001f)
    }

    @Test fun lowerRowsOvershootDownwardAndFasterGesturesTravelFarther() {
        val start = ContextMenuMotion.Frame()
        val slow = ContextMenuMotion.closing(cardPhase(.885f), start, 0f, 1f, 100f)
        val fast = ContextMenuMotion.closing(cardPhase(.885f), start, 0f, 1f, 1800f)
        assertTrue(slow.rowY > 0f && fast.rowY > slow.rowY)
        val rest = ContextMenuMotion.closing(1f, start, 0f, 1f, 1800f)
        assertEquals(0f, rest.rowY, 0f)
        assertEquals(0f, rest.backdrop, 0f)
        assertEquals(1f, rest.rowAlpha, 0f)
    }
}
