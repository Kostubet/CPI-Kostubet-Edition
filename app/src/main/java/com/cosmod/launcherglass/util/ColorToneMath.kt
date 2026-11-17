package com.cosmod.launcherglass.util
/** Signed lightness mixes toward black/white without changing alpha or hue at neutral. */
internal object ColorToneMath {
    fun channel(value:Float,lightness:Int):Float {
        val amount=lightness.coerceIn(-100,100)/100f
        return if(amount>=0f)value+(1f-value)*amount else value*(1f+amount)
    }
    fun argb(color:Int,lightness:Int):Int {
        fun part(shift:Int)=(channel(((color ushr shift) and 255)/255f,lightness)*255f+.5f).toInt().coerceIn(0,255)
        return (color and -0x1000000) or (part(16) shl 16) or (part(8) shl 8) or part(0)
    }
}
