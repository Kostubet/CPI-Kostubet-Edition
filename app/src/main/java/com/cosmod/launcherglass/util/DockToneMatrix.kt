package com.cosmod.launcherglass.util

internal object DockToneMatrix {
    /** RGB only: wallpaper saturation/exposure cannot change child icons or alpha. */
    fun values(saturation:Int,darkness:Int):FloatArray {
        val s=saturation.coerceIn(0,200)*.01f;val scale=1f-darkness.coerceIn(0,100)*.01f
        val r=.213f*(1f-s);val g=.715f*(1f-s);val b=.072f*(1f-s)
        return floatArrayOf((r+s)*scale,g*scale,b*scale,0f,0f,
            r*scale,(g+s)*scale,b*scale,0f,0f,r*scale,g*scale,(b+s)*scale,0f,0f,
            0f,0f,0f,1f,0f)
    }
}
