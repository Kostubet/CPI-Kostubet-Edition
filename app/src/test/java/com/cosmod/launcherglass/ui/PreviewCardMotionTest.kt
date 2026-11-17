package com.cosmod.launcherglass.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewCardMotionTest {
    @Test fun opensPastCardSizeAndReturnsToRest() {
        val start=PreviewCardMotion.openingFrame(0f)
        assertEquals(0f,start.position,.00001f)
        assertEquals(1f,start.rowAlpha,.00001f)
        assertEquals(0f,start.cardAlpha,.00001f)
        val peak=PreviewCardMotion.openingFrame(.79f)
        assertTrue(peak.scale>1f)
        val restored=PreviewCardMotion.openingFrame(1f)
        assertEquals(1f,restored.position,.00001f)
        assertEquals(1f,restored.scale,.00001f)
        assertEquals(0f,restored.rowAlpha,.00001f)
    }

    @Test fun actionMenuEntersAfterContentAndLeavesBeforeCard() {
        val opening=PreviewCardMotion.openingFrame(.37f)
        assertTrue(opening.cardAlpha>.5f)
        assertTrue(PreviewCardMotion.menuReveal(.37f,opening=true)<.05f)
        assertEquals(0f,PreviewCardMotion.menuReveal(.28f,opening=false),.00001f)
        val full=PreviewCardMotion.openingFrame(1f)
        val closing=PreviewCardMotion.closingFrame(.28f,full,150f,20f)
        assertTrue(closing.position>0f)
        assertEquals(1f,closing.cardAlpha,.00001f)
    }

    @Test fun closesInRowShapeBeforeOvershooting() {
        val land=PreviewCardMotion.LANDING
        val travel=150f
        val distance=20f
        val peak=land+(1f-land)*PreviewCardMotion.OVERRUN_PEAK
        assertEquals(1f,PreviewCardMotion.closingProgress(0f,travel,distance),.00001f)
        assertEquals(0f,PreviewCardMotion.closingProgress(land,travel,distance),.00001f)
        assertEquals(0f,PreviewCardMotion.closingProgress(peak,travel,distance),.00001f)
        assertEquals(0f,PreviewCardMotion.closingOverrun(land,travel,distance),.00001f)
        assertEquals(distance,PreviewCardMotion.closingOverrun(peak,travel,distance),.00001f)
        assertEquals(0f,PreviewCardMotion.closingOverrun(1f,travel,distance),.00001f)

        val step=.001f
        val before=travel*PreviewCardMotion.closingProgress(land-step,travel,distance)/step
        val after=PreviewCardMotion.closingOverrun(land+step,travel,distance)/step
        assertEquals(before,after,.75f)
    }

    @Test fun shrinkingCardAndRowAreBothVisibleForTheHandoff() {
        val full=PreviewCardMotion.openingFrame(1f)
        val handoff=PreviewCardMotion.closingFrame(.54f,full,150f,20f)
        assertTrue(handoff.scale in .75f.. .90f)
        assertTrue(handoff.cardAlpha in .2f.. .8f)
        assertTrue(handoff.rowAlpha in .2f.. .9f)
        val peakTime=PreviewCardMotion.LANDING+(1f-PreviewCardMotion.LANDING)*PreviewCardMotion.OVERRUN_PEAK
        val peak=PreviewCardMotion.closingFrame(peakTime,full,150f,20f)
        assertEquals(0f,peak.position,.00001f)
        assertEquals(1f,peak.rowAlpha,.00001f)
        assertEquals(20f,peak.overrun,.00001f)
        val end=PreviewCardMotion.closingFrame(1f,full,150f,20f)
        assertEquals(0f,end.overrun,.00001f)
        assertEquals(0f,end.cardAlpha,.00001f)
    }

    @Test fun swipeContinuesDownAndTheRowEntersOnlyAfterTheCardFades() {
        val full=PreviewCardMotion.openingFrame(1f)
        val departure=(0..100).map { i ->
            PreviewCardMotion.swipeDeparture(i/100f,92f,236f,1.1f,590)
        }
        assertEquals(92f,departure.first(),.00001f)
        assertTrue(departure.zipWithNext().all { (a,b) -> b>=a })
        assertEquals(328f,departure.last(),.00001f)

        val leaving=PreviewCardMotion.swipeFrame(.37f,full,68f,10f)
        assertTrue(leaving.cardAlpha>0f)
        assertEquals(0f,leaving.rowAlpha,.00001f)
        val returning=PreviewCardMotion.swipeFrame(.60f,full,68f,10f)
        assertEquals(0f,returning.cardAlpha,.00001f)
        assertTrue(returning.rowAlpha>0f && returning.rowOffsetY>0f)

        val overshoot=PreviewCardMotion.swipeFrame(.88f,full,68f,10f)
        assertTrue(overshoot.rowOffsetY<0f)
        assertEquals(1f,overshoot.rowAlpha,.00001f)
        val settled=PreviewCardMotion.swipeFrame(1f,full,68f,10f)
        assertEquals(0f,settled.rowOffsetY,.00001f)
    }

    @Test fun fasterSwipesTravelFartherAndStayBounded() {
        val slow=PreviewCardMotion.overrunDp(240f)
        val fast=PreviewCardMotion.overrunDp(1800f)
        assertTrue(fast-slow>13f)
        assertEquals(fast,PreviewCardMotion.overrunDp(-1800f),.00001f)
        assertTrue(PreviewCardMotion.overrunDp(6000f)<36f)
        val peak=PreviewCardMotion.LANDING+(1f-PreviewCardMotion.LANDING)*PreviewCardMotion.OVERRUN_PEAK
        assertEquals(fast,PreviewCardMotion.closingOverrun(peak,2f,fast),.00001f)
    }

    @Test fun rowReturnsToItsNormalScaleAtTheEndOfTheOverrun() {
        assertEquals(0f,PreviewCardMotion.rowSettling(.60f),.00001f)
        val landing = PreviewCardMotion.rowSettling(PreviewCardMotion.LANDING)
        assertTrue(landing > 0f && landing < .5f)
        assertTrue(PreviewCardMotion.rowSettling(.95f) > .98f)
        assertEquals(1f,PreviewCardMotion.rowSettling(1f),.00001f)
    }

    @Test fun shrinksGentlyAndSettlesAfterRelease() {
        assertEquals(0f,PreviewCardMotion.pullProgress(0f,480f),.00001f)
        assertTrue(PreviewCardMotion.pullProgress(10f,480f)<.035f)
        assertEquals(1f,PreviewCardMotion.pullProgress(300f,480f),.00001f)
        assertEquals(72f,PreviewCardMotion.releasedOffset(0f,72f,55f),.00001f)
        assertEquals(0f,PreviewCardMotion.releasedOffset(1f,72f,55f),.00001f)
    }

    @Test fun lowerRowsOvershootDownward() {
        assertEquals(1f,PreviewCardMotion.rowDirection(630f,900f),0f)
        assertEquals(-1f,PreviewCardMotion.rowDirection(270f,900f),0f)
    }
}
