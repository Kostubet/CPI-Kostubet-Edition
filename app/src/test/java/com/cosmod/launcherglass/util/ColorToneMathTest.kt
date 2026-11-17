package com.cosmod.launcherglass.util
import org.junit.Assert.*
import org.junit.Test
class ColorToneMathTest {
    @Test fun zeroKeepsEveryChannelAndAlpha(){
        for(color in listOf(0x804b39ac.toInt(),0xff000000.toInt(),0x00ffffff))assertEquals(color,ColorToneMath.argb(color,0))
    }
    @Test fun extremesReachBlackAndWhiteWithoutChangingAlpha(){
        val input=0x804b39ac.toInt()
        assertEquals(0x80000000.toInt(),ColorToneMath.argb(input,-100))
        assertEquals(0x80ffffff.toInt(),ColorToneMath.argb(input,100))
    }
    @Test fun transitionIsMonotonicAndBounded(){
        for(channel in listOf(0f,.1f,.7f,1f)){
            var last=0f
            for(amount in -100..100){val next=ColorToneMath.channel(channel,amount);assertTrue(next in last..1f);last=next}
        }
    }
}
