package com.cosmod.launcherglass.ui.onextbox

import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import org.junit.Assert.*
import org.junit.Test

/** Drives the actual AndroidX integrator without a display clock or mocked spring physics. */
class BottomBarSpringRegressionTest {
    private fun running(start:Float,queued:Float):SpringAnimation {
        val animation=SpringAnimation(FloatValueHolder(start)).apply {
            spring=SpringForce(queued).setStiffness(200f).setDampingRatio(.35f)
            setStartValue(start)
        }
        DynamicAnimation::class.java.getDeclaredField("mRunning").apply {isAccessible=true}.setBoolean(animation,true)
        animation.animateToFinalPosition(queued)
        return animation
    }
    private fun settle(animation:SpringAnimation):Float {
        val step=SpringAnimation::class.java.getDeclaredMethod("updateValueAndVelocity",Long::class.javaPrimitiveType).apply {isAccessible=true}
        repeat(400){step.invoke(animation,16L)}
        return DynamicAnimation::class.java.getDeclaredField("mValue").apply {isAccessible=true}.getFloat(animation)
    }
    @Test fun queuedDragDoesNotReappearAfterRelease(){
        val animation=running(28f,84f)
        retargetBottomBarSpring(animation,28f,0f,-35f)
        assertEquals(0f,settle(animation),.001f)
        assertEquals(0f,animation.spring.finalPosition,0f)
    }
    @Test fun queuedDeformationReturnsToUnitScale(){
        val animation=running(1.12f,1.35f)
        retargetBottomBarSpring(animation,1.12f,1f,0f)
        assertEquals(1f,settle(animation),.0001f)
    }
    @Test fun quickRetouchAndReleaseStillEndsAtRest(){
        val animation=running(-24f,-90f)
        retargetBottomBarSpring(animation,-24f,65f,130f)
        retargetBottomBarSpring(animation,-24f,0f,12f)
        assertEquals(0f,settle(animation),.001f)
    }
}
