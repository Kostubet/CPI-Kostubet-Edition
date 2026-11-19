package com.cosmod.launcherglass.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ln

class ZoomSmoothingTest {
    @Test fun previewShowsTheEntireTransitionWhenTheConfiguredZoomIsSlow() {
        val curve = ZoomTrajectory(6.0, ZoomTuning(speedIn = .15, maxSpeed = .15, curve = ZoomCurve.EASE_OUT))
        assertTrue(curve.durationSeconds > 4)
        assertEquals(0f, curve.sampleAt(0f), .001f)
        assertEquals(1f, curve.sampleAt(1f), .001f)
        assertTrue(curve.sampleAt(.2f) < .75f)
        assertEquals(.5f, ZoomTrajectory(1.0, ZoomTuning()).sampleAt(.5f), .001f)
    }

    @Test fun manualSpeedLimitHoldsDuringAccelerationAndBraking() {
        val tuning = ZoomTuning(speedIn = 3.5, speedOut = 3.5, maxSpeed = .15, smoothing = 16.0,
            acceleration = 16.0, braking = 18.0, jerk = 2.0, inertia = .02, response = .005)
        val zoom = ZoomSmoothing(1.0)
        zoom.aim(3.0, 0.0, false)
        var previous = 1.0
        for (frame in 1..(60 * 30)) {
            val next = zoom.step(frame / 60.0, tuning)
            assertTrue("limit exceeded at frame $frame", ln(next / previous) <= .15 / 60.0 + .000001)
            previous = next
        }
        assertEquals(3.0, zoom.ratio, .000001)
    }

    @Test fun abruptCustomSettingsStillReachBothEndpointsWithoutASnap() {
        val tunings = listOf(
            ZoomTuning(speedIn = 3.5, speedOut = 3.5, maxSpeed = 4.0, acceleration = 16.0,
                braking = .5, jerk = 2.0, smoothing = 16.0, inertia = .02, response = .005),
            ZoomTuning(speedIn = 3.5, speedOut = 3.5, maxSpeed = 4.0, acceleration = 16.0,
                braking = 18.0, jerk = 2.0, smoothing = 1.0, inertia = .02, response = .005),
        )
        for (tuning in tunings) for ((start, target) in listOf(1.0 to 6.0, 6.0 to 1.0)) for (fps in listOf(24, 60, 120)) {
            val zoom = ZoomSmoothing(start)
            val direction = if (target > start) 1 else -1
            zoom.aim(target, 0.0, false)
            var previous = start
            for (frame in 1..(fps * 30)) {
                val ratio = zoom.step(frame.toDouble() / fps, tuning)
                val step = ln(ratio / previous) * direction
                assertTrue("zoom reversed at frame $frame", step >= -1e-9)
                if (!zoom.active) {
                    assertTrue("zoom snapped to its target at frame $frame", step < .002)
                    break
                }
                previous = ratio
            }
            assertEquals(target, zoom.ratio, .000001)
        }
    }

    @Test fun retargetingToCurrentZoomClearsOldMomentumAndRejectsInvalidTime() {
        val tuning = ZoomTuning()
        val zoom = ZoomSmoothing(1.0)
        zoom.aim(5.0, Double.NaN, false)
        assertTrue(!zoom.active)
        zoom.aim(5.0, 0.0, true)
        for (i in 1..30) zoom.step(i / 60.0, tuning)
        val held = zoom.ratio
        zoom.aim(held, .5, true)
        assertTrue(!zoom.active)
        zoom.aim(.65, .6, true)
        assertTrue(zoom.step(.65, tuning) <= held)
    }

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

    @Test fun defaultZoomRespondsQuicklyWithoutCatchingUpInOneFrameAfterAStall() {
        val tuning = ZoomTuning()
        for (fps in listOf(24, 30, 60)) {
            val zoom = ZoomSmoothing(1.0)
            zoom.aim(2.0, 0.0, true)
            for (frame in 1..fps / 2) zoom.step(frame.toDouble() / fps, tuning)
            val beforeStall = zoom.ratio
            assertTrue("slow response at ${fps} fps", beforeStall > 1.55)
            val resumed = zoom.step(.72, tuning)
            assertTrue("jump after stall at ${fps} fps", ln(resumed / beforeStall) <= tuning.maxSpeed * .05)
            for (frame in fps..fps * 2) zoom.step(frame.toDouble() / fps, tuning)
            assertEquals(2.0, zoom.ratio, .000001)
        }
    }
}
