package com.cosmod.launcherglass.util

import kotlin.math.roundToInt

/** Only the translucent overlay's color; wallpaper pixels and glyphs are separate. */
internal object PlateTone {
    fun iconSaturation(color:Int,percent:Int,neutralAccent:Int?):Int {
        val red=color ushr 16 and 255
        val green=color ushr 8 and 255
        val blue=color and 255
        val source=if(percent>0 && neutralAccent!=null && maxOf(red,green,blue)-minOf(red,green,blue)<=3)
            (color and -0x1000000) or (neutralAccent and 0xffffff) else color
        return saturation(source,percent)
    }

    fun saturation(color:Int,percent:Int):Int {
        val amount=percent.coerceIn(0,200)
        if(amount==100)return color
        val r=(color ushr 16 and 255).toFloat()
        val g=(color ushr 8 and 255).toFloat()
        val b=(color and 255).toFloat()
        val luminance=.213f*r+.715f*g+.072f*b
        fun channel(value:Float)=(luminance+(value-luminance)*amount/100f).roundToInt().coerceIn(0,255)
        return (color and -0x1000000) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
    }
}
