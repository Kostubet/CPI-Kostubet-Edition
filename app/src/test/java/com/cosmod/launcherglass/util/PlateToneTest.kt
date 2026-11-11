package com.cosmod.launcherglass.util

import org.junit.Assert.assertEquals
import org.junit.Test

class PlateToneTest {
    private val gray=0xff333333.toInt()
    private val accent=0xff503020.toInt()

    @Test fun neutralNativePlateUsesConfiguredAccent(){
        assertEquals(accent,PlateTone.iconSaturation(gray,100,accent))
    }

    @Test fun zeroSaturationAndExplicitClearStayNeutral(){
        val muted=PlateTone.iconSaturation(gray,0,accent)
        assertEquals(muted ushr 16 and 255,muted and 255)
        assertEquals(gray,PlateTone.iconSaturation(gray,200,null))
    }

    @Test fun nativeColorIsPreserved(){
        val native=0xff2060a0.toInt()
        assertEquals(native,PlateTone.iconSaturation(native,100,accent))
    }
}
