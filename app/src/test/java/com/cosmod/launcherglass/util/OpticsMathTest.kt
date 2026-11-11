package com.cosmod.launcherglass.util

import org.junit.Assert.*
import org.junit.Test

class OpticsMathTest {
    @Test fun fadeIsBoundedAndMonotonic(){
        for(height in 1..100 step 5)for(strength in 0..100 step 5){
            var previous=1f
            for(i in 0..200){
                val alpha=OpticsMath.photoAlpha(i/200f,height,strength)
                assertTrue(alpha in 0f..1f)
                assertTrue(alpha<=previous+.00001f)
                previous=alpha
            }
        }
    }
    @Test fun disabledFadePreservesPhoto(){
        for(i in 0..100){assertEquals(1f,OpticsMath.photoAlpha(i/100f,0,100),0f);assertEquals(1f,OpticsMath.photoAlpha(i/100f,100,0),0f)}
    }
    @Test fun bottomHasARealTransparentArea(){
        assertEquals(1f,OpticsMath.photoAlpha(.3f,60,100),0f)
        for(y in listOf(.96f,.98f,1f))assertEquals(0f,OpticsMath.photoAlpha(y,60,100),.00001f)
        assertEquals(.5f,OpticsMath.photoAlpha(1f,60,50),.00001f)
        assertTrue(OpticsMath.photoAlpha(.5f,90,100)<OpticsMath.photoAlpha(.5f,30,100))
    }
    @Test fun softnessDoesNotAddAnUnrequestedTintOrReduceBlur(){
        for(base in listOf(0f,1f,12f,50f)){
            assertEquals(base,OpticsMath.softRadius(base,.75f,0),.00001f)
            var previous=base
            for(softness in 0..100){val radius=OpticsMath.softRadius(base,.75f,softness);assertTrue(radius>=previous);previous=radius}
        }
    }
    @Test fun tiltUsesTheShortestPathAndDoesNotOvershoot(){
        val right=OpticsMath.followAngle(179f,-179f,.016f,75)
        assertTrue(right>179f && right<181f)
        val left=OpticsMath.followAngle(-179f,179f,.016f,75)
        assertTrue(left< -179f && left> -181f)
    }
    @Test fun tiltSmoothingIsIndependentOfSensorFrequency(){
        fun follow(fps:Int):Float {var value=315f;repeat(fps){value=OpticsMath.followAngle(value,45f,1f/fps,75)};return value}
        assertEquals(follow(30),follow(60),.003f)
        assertEquals(follow(60),follow(120),.003f)
    }
    private fun sampleRamp(height:Int,y:Float):Float {
        val stops=OpticsMath.photoStops(height)
        val index=(0 until stops.lastIndex).firstOrNull {y<=stops[it+1]} ?: stops.lastIndex-1
        val t=((y-stops[index])/(stops[index+1]-stops[index])).coerceIn(0f,1f)
        val a=OpticsMath.photoAlpha(stops[index],height,100)
        return a+(OpticsMath.photoAlpha(stops[index+1],height,100)-a)*t
    }
    @Test fun actualGradientStopsMoveWithHeight(){
        assertEquals(1f,sampleRamp(25,.5f),.0001f)
        assertTrue(sampleRamp(90,.5f)<.6f)
        assertTrue(sampleRamp(90,.9f)<sampleRamp(10,.9f)-.5f)
    }
    @Test fun veryShortGradientDoesNotExpandToAMinimumStep(){
        assertEquals(1f,sampleRamp(1,.98f),.0001f)
        assertTrue(sampleRamp(1,.995f) in .01f.. .8f)
        for(h in 1..100){val stops=OpticsMath.photoStops(h);assertEquals(0f,stops.first(),0f);assertEquals(1f,stops.last(),0f);assertTrue(stops.asList().zipWithNext().all {it.first<it.second})}
    }

    @Test fun smoothnessLengthensTheFeatherWithoutChangingStrengthOrHeight(){
        assertTrue(OpticsMath.photoAlpha(.6f,70,100,100)>OpticsMath.photoAlpha(.6f,70,100,10))
        for(s in 0..100){
            assertEquals(1f,OpticsMath.photoAlpha(.1f,70,100,s),0f)
            assertEquals(.3f,OpticsMath.photoAlpha(1f,70,70,s),.0001f)
            val stops=OpticsMath.photoStops(70,s).asList();assertTrue(stops.zipWithNext().all {it.first<it.second})
        }
    }

    @Test fun extendedEdgeLightStopsAreOrderedAndLengthenTheHighlight() {
        for(length in 0..200){
            val stops=OpticsMath.edgeLightStops(length)
            assertTrue(stops.first()==0f && stops.last()==1f)
            assertTrue((1 until stops.size).all {stops[it]>stops[it-1]})
        }
        val previousLimit=OpticsMath.edgeLightStops(100)
        val extended=OpticsMath.edgeLightStops(200)
        assertTrue(extended[1]>previousLimit[1] && extended[2]>previousLimit[2])
    }
}
