package com.cosmod.launcherglass.util

import kotlin.math.ceil
import kotlin.math.roundToLong

/** Uses display timestamps, avoiding millisecond rounding that drops every other frame. */
class FramePacer {
    private var next=0L
    private var period=0L
    private var lastFps=-1
    private var lastRefreshHz=0f
    fun reset(){next=0L}
    fun ready(time:Long,fps:Int,refreshHz:Float=0f):Boolean {
        if(fps!=lastFps || refreshHz!=lastRefreshHz){
            period=intervalNs(fps,refreshHz)
            lastFps=fps;lastRefreshHz=refreshHz
            next=0L
        }
        if(time+500_000L<next)return false
        next=if(next==0L || time-next>period)time+period else next+period
        return true
    }

    companion object {
        fun intervalNs(fps:Int,refreshHz:Float):Long {
            val cap=fps.coerceIn(1,240)
            if(refreshHz !in 150f..240f)return 1_000_000_000L/cap
            val steps=ceil(refreshHz.toDouble()/cap-.001).coerceAtLeast(1.0)
            return (1_000_000_000.0*steps/refreshHz).roundToLong()
        }
    }
}
