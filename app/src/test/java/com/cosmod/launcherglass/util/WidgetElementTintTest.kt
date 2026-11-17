package com.cosmod.launcherglass.util

import org.junit.Assert.*
import org.junit.Test

class WidgetElementTintTest {
    private fun apply(color:Int,transparency:Int,rgb:FloatArray):FloatArray {
        val m=WidgetElementTint.matrix(color,transparency)
        return FloatArray(4){r->(0..3).sumOf {c->(m[r*5+c]*rgb[c]).toDouble()}.toFloat()+m[r*5+4]}
    }
    @Test fun fullTransparencyPreservesNativeTextAndAlpha(){
        val native=floatArrayOf(25f,145f,220f,130f)
        assertArrayEquals(native,apply(0xffcc4411.toInt(),100,native),.001f)
    }
    @Test fun opaqueTintReplacesRgbWithoutChangingGlyphCoverage(){
        assertArrayEquals(floatArrayOf(204f,68f,17f,130f),
            apply(0xffcc4411.toInt(),0,floatArrayOf(25f,145f,220f,130f)),.001f)
    }
    @Test fun midpointMixesOriginalAndTintInsteadOfFadingText(){
        assertArrayEquals(floatArrayOf(127.5f,127.5f,127.5f,220f),
            apply(0xffffffff.toInt(),50,floatArrayOf(0f,0f,0f,220f)),.001f)
    }
    @Test fun increasingSelectedBrightnessNeverDarkensAnyChannel(){
        for(transparency in listOf(0,40,100)){
            var previous=FloatArray(4)
            for(v in 0..255){
                val color=(0xff shl 24) or (v shl 16) or (v shl 8) or v
                val next=apply(color,transparency,floatArrayOf(80f,100f,120f,200f))
                for(c in 0..2)assertTrue(next[c]>=previous[c])
                assertEquals(200f,next[3],.001f)
                previous=next
            }
        }
    }
}
