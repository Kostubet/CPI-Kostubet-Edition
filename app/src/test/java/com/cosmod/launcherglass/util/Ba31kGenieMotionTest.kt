package com.cosmod.launcherglass.util

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Ba31kGenieMotionTest {
    private fun Ba31kGenieMotion.frame(fraction: Double, x: Double, y: Double, time: Long,
        opening: Boolean = true) = sample(fraction, x, .15, y, 1.0, .24, .88, 1.0, opening, time)

    @Test fun springRetainsVelocityWhenItsTargetChanges() {
        val spring = Ba31kGenieMotion.Spring()
        spring.advance(1.0, .36, .78, .045)
        val before = spring.position
        val velocity = spring.velocity
        spring.advance(0.0, .36, .78, .0001)
        assertEquals(velocity, (spring.position - before) / .0001, .06)
        repeat(50) { spring.advance(0.0, .36, .78, .016) }
        assertEquals(0.0, spring.position, .0001)
    }

    @Test fun springPositionIsIndependentOfRefreshRate() {
        for (damping in listOf(.84, 1.0, 1.1)) {
            fun position(rate: Int): Double {
                val spring = Ba31kGenieMotion.Spring()
                repeat(rate * 3 / 10) { spring.advance(1.0, .36, damping, 1.0 / rate) }
                return spring.position
            }
            assertEquals(position(60), position(90), 1e-12)
            assertEquals(position(60), position(120), 1e-12)
        }
    }

    @Test fun upperRowTapersWhileSmallAndStraightensAsTheCropFills() {
        val opening = Ba31kGenieMotion()
        assertNull(opening.frame(0.0, .80, -.80, 1L))
        val early = checkNotNull(opening.frame(.33, .80, -.80, 17_000_001L))
        val late = checkNotNull(opening.frame(.75, .80, -.80, 49_000_001L))
        val earlyRatio = early.topWidth / early.bottomWidth
        val lateRatio = late.topWidth / late.bottomWidth
        assertTrue("Unexpected opening taper: $earlyRatio",earlyRatio in .74f.. .91f)
        assertTrue(lateRatio > earlyRatio + .035f)
        val matrix = FloatArray(16)
        early.writeMatrix(matrix, 80f, 145f, 480f, 960f)
        assertTrue(matrix.all(Float::isFinite))
        assertNull(opening.frame(1.0, .80, -.80, 85_000_001L))
    }

    @Test fun oppositeRowsTaperInOppositeDirectionsAndFollowTheIconSide() {
        for (opening in listOf(false, true)) for (x in listOf(.07, .78)) {
            fun edge(y: Double): AppWindowDeformation {
                val motion = Ba31kGenieMotion()
                motion.frame(if (opening) 0.0 else 1.0, x, y, 10L, opening)
                return checkNotNull(motion.frame(.4, x, y, 26_000_010L, opening))
            }
            val upper = edge(-.76)
            val lower = edge(.76)
            assertTrue(upper.topWidth < upper.bottomWidth)
            assertTrue(lower.topWidth > lower.bottomWidth)
            val left = upper.topCenter - upper.topWidth * .5f
            val right = upper.topCenter + upper.topWidth * .5f
            val lowerLeft = upper.bottomCenter - upper.bottomWidth * .5f
            val lowerRight = upper.bottomCenter + upper.bottomWidth * .5f
            assertTrue(if (x < .5) abs(left - lowerLeft) < abs(right - lowerRight)
                else abs(right - lowerRight) < abs(left - lowerLeft))
        }
    }

    @Test fun geometryDoesNotJumpWhenTheGestureReverses() {
        val motion = Ba31kGenieMotion()
        val before = checkNotNull(motion.frame(.36, .73, -.75, 10L))
        val after = checkNotNull(motion.frame(.36, .73, -.75, 200_010L, opening = false))
        assertEquals(before.topWidth, after.topWidth, .01f)
        assertEquals(before.bottomWidth, after.bottomWidth, .01f)
    }

    @Test fun tiltAndDeformationWorkIndependentlyAndReturnToNativeEndpoints() {
        fun pose(strength:Double,tilt:Double,x:Double):AppWindowDeformation? {
            val motion=Ba31kGenieMotion()
            fun frame(fraction:Double,time:Long)=motion.sample(fraction,x,.15,-.7,
                strength,.24,.88,1.0,true,time,tilt=tilt)
            assertNull(frame(0.0,1))
            val result=frame(.4,17_000_001)
            assertNull(frame(1.0,240_000_001))
            return result
        }
        assertNull(pose(0.0,0.0,.68))
        val shape=checkNotNull(pose(1.0,0.0,.68))
        val right=checkNotNull(pose(0.0,1.0,.68))
        val left=checkNotNull(pose(0.0,1.0,.17))
        assertTrue(shape.topWidth < shape.bottomWidth)
        assertTrue(right.topCenter > right.bottomCenter)
        assertTrue(right.topWidth < right.bottomWidth)
        assertTrue(left.topCenter < left.bottomCenter)
        assertTrue(checkNotNull(pose(0.0,2.5,.68)).topCenter > right.topCenter)
    }

    @Test fun classicSettingsDoNotReplaceAnExplicitEngineChoice() {
        val keys=Ba31kAnimationSettings
        val old=mapOf("app_tilt_enabled" to true,"app_genie_open_speed" to 135,"app_tilt_close" to false)
        assertEquals(mapOf(keys.ENABLED to true,keys.OPEN_SPEED to 135,keys.CLOSE to false),keys.migrateClassic(old))
        assertEquals(emptyMap<String,Any>(),keys.migrateClassic(old+(keys.ENABLED to true)))
        assertEquals(emptyMap<String,Any>(),keys.migrateClassic(old+(keys.ENABLED to false)))
        assertEquals(mapOf(keys.ENABLED to true,keys.OPEN_SPEED to 135),
            keys.migrateClassic(old+(keys.CLOSE to true)))
    }

    @Test fun blurAndDropletFadeAtBothEnds() {
        for (opening in listOf(false, true)) {
            assertEquals(0.0, Ba31kGenieMotion.backdrop(0.0, opening), 0.0)
            assertEquals(1.0, Ba31kGenieMotion.backdrop(.5, opening), 0.0)
            assertEquals(0.0, Ba31kGenieMotion.backdrop(1.0, opening), 0.0)
            assertEquals(0f, Ba31kGenieMotion.droplet(0.0, opening, 1.0), 0f)
            assertEquals(0f, Ba31kGenieMotion.droplet(1.0, opening, 1.0), 0f)
        }
    }
}
