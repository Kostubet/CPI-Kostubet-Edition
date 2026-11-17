package com.cosmod.launcherglass.util

/** Mixes only foreground RGB with the selected color. Glyph coverage stays unchanged. */
internal object WidgetElementTint {
    fun matrix(color:Int,transparency:Int):FloatArray {
        val keep=transparency.coerceIn(0,100)/100f
        val tint=1f-keep
        return floatArrayOf(
            keep,0f,0f,0f,((color ushr 16) and 255)*tint,
            0f,keep,0f,0f,((color ushr 8) and 255)*tint,
            0f,0f,keep,0f,(color and 255)*tint,
            0f,0f,0f,1f,0f)
    }
}
