package com.cosmod.launcherglass.xposed

import com.cosmod.launcherglass.util.FramePacer
import com.cosmod.launcherglass.util.PerformancePolicy

/** Mini previews may lower the global material FPS limit to 30; other icons retain the global limit. */
internal object MiniIconRefresh {
    fun fps(globalFps:Int,mini:Boolean,enabled:Boolean)=
        if(mini && enabled)globalFps.coerceIn(1,30) else globalFps.coerceIn(1,120)
    fun isMini(preview:Boolean,width:Float,height:Float,nativeWidth:Int,nativeHeight:Int):Boolean =
        preview && nativeWidth>0 && nativeHeight>0 && width>0f && height>0f &&
            width<=nativeWidth*.5f && height<=nativeHeight*.5f
}

/** Keep the requested average cadence when display and material FPS differ. */
internal class SampleFrameClock {
    private var nextNs=0L
    private var rate=0
    private var refresh=0f
    private var period=0L
    fun remainingNs(nowNs:Long,fps:Int):Long =
        if(rate!=fps || refresh!=PerformancePolicy.capturePacingHz || nextNs==0L)0L
        else (nextNs-nowNs-500_000L).coerceAtLeast(0L)
    fun record(nowNs:Long,fps:Int){
        val display=PerformancePolicy.capturePacingHz
        val changed=rate!=fps || refresh!=display
        if(changed)period=FramePacer.intervalNs(fps,display)
        nextNs=if(changed || nextNs==0L)nowNs+period
            else nextNs+maxOf(1L,(nowNs-nextNs)/period+1L)*period
        rate=fps;refresh=display
    }
}
