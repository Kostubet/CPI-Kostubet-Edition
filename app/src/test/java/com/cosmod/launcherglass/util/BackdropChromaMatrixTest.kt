package com.cosmod.launcherglass.util
import org.junit.Test
import org.junit.Assert.*
class BackdropChromaMatrixTest {
    @Test fun vibrancy100RestoresTheOriginalImage(){
        val matrix=BackdropChromaMatrix.create(1f,.3f,0f,100,100)
        assertArrayEquals(floatArrayOf(1f,0f,0f,0f,0f,0f,1f,0f,0f,0f,0f,0f,1f,0f,0f,0f,0f,0f,1f,0f),matrix,0f)
    }
    @Test fun recoloringDoesNotChangeAlphaOrIntroduceAnOpaqueVeil(){
        for(v in 0..100 step 10){val matrix=BackdropChromaMatrix.create(.1f,.2f,1f,80,v)
            assertEquals(1f,matrix[18],0f)
            for(i in listOf(3,4,8,9,13,14,15,16,17,19))assertEquals(0f,matrix[i],0f)
        }
    }
    @Test fun interpolationDoesNotFullyDesaturateAtIntermediateVibrancy(){
        val grey=BackdropChromaMatrix.create(1f,1f,1f,0,0)
        val mixed=BackdropChromaMatrix.create(1f,1f,1f,0,30)
        assertTrue(mixed[0]>grey[0]);assertTrue(mixed[1]<grey[1])
    }
    @Test fun widgetTintAlwaysPreservesSourceTransparency(){
        for(light in -100..100 step 20)for(transparency in 0..100 step 10){
            val m=BackdropChromaMatrix.tint(1f,.4f,.3f,75,light,transparency)
            assertEquals(1f,m[18],0f)
            for(i in listOf(15,16,17,19))assertEquals(0f,m[i],0f)
            assertEquals(.25f,.25f*m[18]+m[19],0f)
        }
    }
    @Test fun transparentTintRestoresTheOriginalRgb(){
        val m=BackdropChromaMatrix.tint(1f,0f,0f,0,100,100)
        assertEquals(1f,m[0],0f);assertEquals(1f,m[6],0f);assertEquals(1f,m[12],0f)
        assertEquals(0f,m[4],0f)
    }
    @Test fun brightnessStillWorksWhenVibrancyKeepsOriginalColors(){
        val dark=BackdropChromaMatrix.create(1f,.3f,0f,100,100,20)
        val light=BackdropChromaMatrix.create(1f,.3f,0f,100,100,90)
        assertEquals(.2f,dark[0],.0001f);assertEquals(.9f,light[0],.0001f)
        assertEquals(.9f,light[6],.0001f);assertEquals(.9f,light[12],.0001f)
    }
    @Test fun brightnessNeverChangesCoverageOrAddsColorToTransparentPixels(){
        for(value in 0..100 step 10){
            val matrix=BackdropChromaMatrix.create(1f,.3f,.1f,80,40,value)
            assertEquals(1f,matrix[18],0f)
            for(i in listOf(4,9,14,19))assertEquals(0f,matrix[i],0f)
        }
    }
    @Test fun increasingBrightnessNeverDarkensTheBackdrop(){
        var previous=FloatArray(20)
        for(value in 0..100){
            val matrix=BackdropChromaMatrix.create(1f,.5f,.2f,70,35,value)
            for(i in listOf(0,1,2,5,6,7,10,11,12))assertTrue(matrix[i]>=previous[i])
            previous=matrix
        }
    }
}
