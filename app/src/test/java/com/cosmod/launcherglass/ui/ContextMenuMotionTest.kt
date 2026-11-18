package com.cosmod.launcherglass.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextMenuMotionTest {
    @Test fun openingStartsAtTheRowAndSettlesInTheCardBounds() {
        val start = ContextMenuMotion.opening(0f)
        assertEquals(0f, start.shapeProgress, 0f)
        assertEquals(0f, start.materialAlpha, 0f)
        assertEquals(1f, start.rowAlpha, 0f)
        assertEquals(1f, start.rowLift, 0f)

        val morphing = ContextMenuMotion.opening(.4f)
        assertTrue(morphing.materialAlpha > 0f && morphing.rowAlpha > 0f)
        assertTrue(morphing.shapeProgress in .2f.. .7f)
        assertTrue(ContextMenuMotion.opening(.74f).scale > 1f)
        assertEquals(ContextMenuMotion.Frame(), ContextMenuMotion.opening(1f))
    }

    @Test fun swipeCloseStartsFromTheRenderedPoseAndPreservesReleaseVelocity() {
        val released = ContextMenuMotion.dragging(ContextMenuMotion.Frame(), 6f, 56f, 420f)
        val momentum = 160f
        val first = ContextMenuMotion.closing(.001f, released, 235f, 20f, momentum)
        assertEquals(released, ContextMenuMotion.closing(0f, released, 235f, 20f, momentum))
        assertEquals(momentum / PreviewCardMotion.LANDING, (first.offsetY - released.offsetY) / .001f, .10f)
        assertTrue(released.scale < 1f && released.menu < 1f)
    }

    @Test fun cardTransfersToTheRowBeforeReboundingWithContinuousVelocity() {
        val start = ContextMenuMotion.Frame()
        val landing = PreviewCardMotion.LANDING
        val before = ContextMenuMotion.closing(landing - .001f, start, 280f, 18f, 0f)
        val at = ContextMenuMotion.closing(landing, start, 280f, 18f, 0f)
        val after = ContextMenuMotion.closing(landing + .001f, start, 280f, 18f, 0f)
        assertTrue(before.position > 0f && after.position < 0f)
        assertEquals(before.position / .001f, -after.position / .001f, .012f)
        assertEquals(0f, at.shapeProgress, 0f)
        assertEquals(0f, at.contentAlpha, 0f)
        assertEquals(0f, at.menu, 0f)
        assertEquals(1f, at.rowAlpha, 0f)
    }

    @Test fun fasterSwipesProduceALargerBoundedOvershoot() {
        val start = ContextMenuMotion.Frame()
        val travel = 230f
        val slow = ContextMenuMotion.overrun(travel, 1f, 120f)
        val fast = ContextMenuMotion.overrun(travel, 1f, 2100f)
        assertTrue(fast > slow && fast <= 27f)
        assertTrue(ContextMenuMotion.overrun(20f, 1f, 2100f) <= 4f)
        val peak = PreviewCardMotion.LANDING + (1f - PreviewCardMotion.LANDING) * PreviewCardMotion.OVERRUN_PEAK
        assertEquals(-fast / travel, ContextMenuMotion.closing(peak, start, travel, fast, 0f).position, .001f)
        val rest = ContextMenuMotion.closing(1f, start, travel, fast, 0f)
        assertEquals(0f, rest.position, 0f)
        assertEquals(0f, rest.backdrop, 0f)
        assertEquals(1f, rest.rowAlpha, 0f)
    }
}
