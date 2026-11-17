package com.cosmod.launcherglass.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppGenieTrajectoryTest {
    @Test
    fun gestureReleaseStartsWithoutMovingTheNativeWindow() {
        val path = AppGenieTrajectory()
        assertEquals(0f, path.sample(.65f, false)!!.blend, 0f)
        assertEquals(1f, path.sample(.45f, false)!!.blend, 0f)
        val end = path.sample(0f, false)!!
        assertEquals(0f, end.blend, 0f)
        assertEquals(0f, end.x, 0f)
        assertEquals(0f, end.y, 0f)
    }

    @Test
    fun pathStaysContinuousAndMonotonicThroughTheFinalCurve() {
        val path = AppGenieTrajectory()
        var lastX = 0f
        var lastY = 0f
        for (frame in 0..1000) {
            val position = path.sample(frame / 1000f, true)!!
            assertTrue(position.x in lastX..1f)
            assertTrue(position.y in lastY..1f)
            assertTrue(position.x - lastX < .008f)
            assertTrue(position.y - lastY < .008f)
            lastX = position.x
            lastY = position.y
        }
        assertEquals(1f, lastX, 0f)
        assertEquals(1f, lastY, 0f)
    }
}
