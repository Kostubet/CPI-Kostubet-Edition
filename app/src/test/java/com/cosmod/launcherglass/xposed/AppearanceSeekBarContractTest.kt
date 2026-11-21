package com.cosmod.launcherglass.xposed

import com.coui.appcompat.seekbar.COUISeekBar
import org.junit.Assert.assertEquals
import org.junit.Test

class AppearanceSeekBarContractTest {
    @Test
    fun usesAdapterListenerWhenCoreSetterIsAlsoPublic() {
        val seekBar = COUISeekBar::class.java
        assertEquals(2, seekBar.methods.count { it.name == "setOnSeekBarChangeListener" })

        val contract = DesktopAppearancePanel.Contract(seekBar.classLoader!!)

        assertEquals(seekBar, contract.listener.declaringClass)
        assertEquals(COUISeekBar.Listener::class.java, contract.listenerType)
        assertEquals("onProgress", contract.progressCallback.name)
    }
}
