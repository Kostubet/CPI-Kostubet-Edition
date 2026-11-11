package com.cosmod.launcherglass.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class TiltCurveTest {
    @Test fun speedMovesKeyframesWithoutChangingTheirStrength() {
        val curve = TiltCurve(35, 84, 65, 42)
        fun timeAt(speed: Float) = 1f - (1f - .35f).pow(1f / speed)
        assertTrue(timeAt(1.6f) < timeAt(1f))
        assertTrue(timeAt(.5f) > timeAt(1f))
        for (speed in listOf(.5f, 1f, 1.6f)) {
            assertEquals(.84f, curve.amount(timeAt(speed), speed), .0001f)
        }
    }

    @Test fun eachDirectionReturnsToNeutralAtBothEnds() {
        for (direction in TiltDirection.entries) {
            for (speed in listOf(.5f, 1f, 1.6f)) {
                assertEquals(0f, direction.amount(0f, speed, direction.defaults), .0001f)
                assertEquals(0f, direction.amount(1f, speed, direction.defaults), .0001f)
            }
        }
    }

    @Test fun importedAndDraggedPointsStayOrdered() {
        val curve = TiltDirection.OPEN.read { key, _ -> if (key.endsWith("time1")) 100 else 0 }
        assertEquals(85, curve.firstTime)
        assertEquals(95, curve.secondTime)
        val edited = curve.move(first = true, time = 20, tilt = 200).move(first = false, time = 2, tilt = -1)
        assertEquals(TiltCurve(20, 100, 30, 0), edited)
    }
}
