package com.cosmod.launcherglass.xposed

import android.graphics.Bitmap
import android.graphics.RenderEffect

/** Color quantization of the refracted backdrop, before drawing the foreground. */
internal object MaterialFinish {
    fun key()=if(PrefsHelper.colorDepthStage==ColorDepthEffect.AFTER_REFRACTION)PrefsHelper.colorDepthBits else 24
    fun needed()=key()<24
    fun effect(input:RenderEffect?)=ColorDepthEffect.at(input,ColorDepthEffect.AFTER_REFRACTION)
    fun applyCpu(source:Bitmap)=ColorDepthEffect.atCpu(source,ColorDepthEffect.AFTER_REFRACTION)
}
