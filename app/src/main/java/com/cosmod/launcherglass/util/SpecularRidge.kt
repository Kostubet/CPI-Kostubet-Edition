package com.cosmod.launcherglass.util
import kotlin.math.exp
internal object SpecularRidge {
    fun intensity(depth:Float,core:Float,softness:Int):Float {
        if(depth<=0f)return 1f
        val scale=core.coerceAtLeast(.5f)
        val p=depth/scale
        val shoulder=.015f+.07f*softness.coerceIn(0,100)/100f
        return ((1f-shoulder)*exp(-2.8f*p*p)+shoulder*exp(-.5f*p*p)).coerceIn(0f,1f)
    }
}
