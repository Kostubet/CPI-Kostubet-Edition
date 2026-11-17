package com.cosmod.launcherglass.util
import org.junit.Assert.*
import org.junit.Test
class SpecularRidgeTest {
    @Test fun staysMonotonicWithoutTheOldSecondRing(){
        for(softness in 0..100 step 10){
            var last=1f
            for(step in 0..1000){val next=SpecularRidge.intensity(step*.01f,1.5f,softness);assertTrue(next in 0f..last);last=next}
        }
    }
    @Test fun halfWidthKeepsASharpRidgeAtMaximumSoftness(){
        assertTrue(SpecularRidge.intensity(.2f,1f,100)>.8f)
        assertTrue(SpecularRidge.intensity(1.5f,1f,100)<.05f)
    }
    @Test fun userWidthActuallyControlsTheRidge(){
        assertTrue(SpecularRidge.intensity(1f,2f,50)>SpecularRidge.intensity(1f,.5f,50))
    }
}
