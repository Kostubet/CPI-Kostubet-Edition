package com.cosmod.launcherglass.xposed

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeFluxIconStyleTest {
    private val geometry = 0x001e177017700101L
    private val darkDisplay = (1L shl 10) or (1L shl 61)
    private val gradient = geometry or 0x30L
    private val color = (1L shl 60) or (1L shl 11)

    @Test
    fun classicAndBrightGradientBecomeDarkGradient() {
        assertEquals(gradient or darkDisplay, NativeFluxIconStyle.requiredConfig(geometry or 0x20L))
        assertEquals(gradient or darkDisplay or color, NativeFluxIconStyle.requiredConfig(gradient or color))
    }

    @Test
    fun darkAndAutomaticGradientKeepTheirConfiguration() {
        val dark = gradient or darkDisplay or color
        val automatic = gradient or (1L shl 61) or color
        assertEquals(dark, NativeFluxIconStyle.requiredConfig(dark))
        assertEquals(automatic, NativeFluxIconStyle.requiredConfig(automatic))
    }

    @Test
    fun leavesOtherNativeStylesAndUnsetConfigurationsAlone() {
        assertEquals(geometry or 0x40L, NativeFluxIconStyle.requiredConfig(geometry or 0x40L))
        assertEquals(-1L, NativeFluxIconStyle.requiredConfig(-1L))
    }
}
