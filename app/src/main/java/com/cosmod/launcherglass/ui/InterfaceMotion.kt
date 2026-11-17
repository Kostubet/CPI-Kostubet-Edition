package com.cosmod.launcherglass.ui

internal object InterfaceMotion {
    fun glide(time:Float,startSlope:Float=0f):Float {
        val t=time.coerceIn(0f,1f)
        val phase=minOf(t,1f-t)
        val eased=phase*phase*phase*(10f+phase*(-15f+6f*phase))
        val position=if(t<=.5f)eased else 1f-eased
        return position+startSlope*velocityBasis(t)
    }

    fun velocityBasis(phase:Float):Float {
        val remaining=1f-phase
        return phase*remaining*remaining*remaining*(1f+3f*phase)
    }
}
