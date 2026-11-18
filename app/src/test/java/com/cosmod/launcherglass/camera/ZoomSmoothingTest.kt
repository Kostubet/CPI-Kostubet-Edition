package com.cosmod.launcherglass.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ln

class ZoomSmoothingTest {
    @Test fun reachesTheTargetWithoutOvershootingAtCommonVideoRates() {
        val tuning = ZoomTuning()
        for (fps in listOf(24, 30, 60, 120)) {
            val zoom = ZoomSmoothing(1.0)
            zoom.aim(3.0, 0.0, false)
            var previous = 1.0
            for (frame in 1..(fps * 8)) {
                val ratio = zoom.step(frame.toDouble() / fps, tuning)
                assertTrue("fps=$fps ratio=$ratio previous=$previous", ratio >= previous && ratio <= 3.000001)
                previous = ratio
            }
            assertEquals(3.0, zoom.ratio, .00001)
            assertTrue(!zoom.active)
        }
    }

    @Test fun reversalKeepsTheCurrentPositionAndSettlesBelowOne() {
        val tuning = ZoomTuning.forMode(ZoomMode.ADAPTIVE, ZoomTuning())
        val zoom = ZoomSmoothing(1.0)
        zoom.aim(6.0, 0.0, true)
        for (i in 1..36) zoom.step(i / 60.0, tuning)
        val before = zoom.ratio
        zoom.aim(.65, .6, true)
        assertEquals(before, zoom.ratio, 0.0)
        val next = zoom.step(37 / 60.0, tuning)
        assertTrue(abs(ln(next / before)) < .08)
        for (i in 38..600) assertTrue(zoom.step(i / 60.0, tuning) >= .65 - .000001)
        assertEquals(.65, zoom.ratio, .00001)
    }

    @Test fun responsiveMovesFasterThanCinematicAcrossFrameRates() {
        fun after(fps: Int, mode: ZoomMode): Double {
            val tuning = ZoomTuning.forMode(mode, ZoomTuning())
            val zoom = ZoomSmoothing(1.0)
            zoom.aim(3.0, 0.0, false)
            for (i in 1..fps) zoom.step(i.toDouble() / fps, tuning)
            return zoom.ratio
        }
        assertTrue(after(60, ZoomMode.RESPONSIVE) > after(60, ZoomMode.CINEMATIC))
        assertEquals(after(30, ZoomMode.IPHONE), after(60, ZoomMode.IPHONE), .025)
    }
}
