package com.cosmod.launcherglass.util

import kotlin.math.exp
import kotlin.math.sqrt

/** Shared, platform-independent optical curves. */
object OpticsMath {
    fun photoStops(height:Int,smoothness:Int=90):FloatArray {
        val range=height.coerceIn(0,100)/100f
        if(range==0f)return floatArrayOf(0f,1f)
        val start=1f-range
        val points=ArrayList<Float>()
        if(start>0f)points.add(0f)
        val feather=.1f+.9f*smoothness.coerceIn(0,100)/100f
        for(i in 0..24)points.add(start+range*feather*i/24f)
        if(points.last()<1f)points.add(1f)
        return points.toFloatArray()
    }
    fun photoAlpha(y:Float,height:Int,strength:Int,smoothness:Int=90):Float {
        if(height<=0 || strength<=0)return 1f
        val range=height.coerceIn(0,100)/100f
        val feather=.1f+.9f*smoothness.coerceIn(0,100)/100f
        val t=((y.coerceIn(0f,1f)-(1f-range))/(range*feather)).coerceIn(0f,1f)
        return 1f-strength.coerceIn(0,100)/100f*t*t*(3f-2f*t)
    }
    fun softRadius(radius:Float,scale:Float,softness:Int):Float {
        val extra=softness.coerceIn(0,100)*.12f*scale.coerceAtLeast(0f)
        return sqrt(radius*radius+extra*extra)
    }
    internal fun edgeLightStops(length:Int):FloatArray {
        val base=.075f+length.coerceIn(0,100)*.00165f
        val extension=((length-100)/100f).coerceIn(0f,1f)
        val reach=base+.065f*extension
        val core=reach*(.18f+.10f*extension)
        val shoulder=reach*(.56f+.12f*extension)
        val oppositeReach=base*.72f+.017f*extension
        val oppositeCore=base*.28f+.012f*extension
        return floatArrayOf(0f,core,shoulder,reach,.5f-oppositeReach,.5f-oppositeCore,.5f,
            .5f+oppositeCore,.5f+oppositeReach,1f-reach,1f-shoulder,1f-core,1f)
    }

    fun followAngle(current:Float,target:Float,seconds:Float,smoothing:Int):Float {
        val delta=((target-current+540f)%360f+360f)%360f-180f
        val tau=.025f+smoothing.coerceIn(0,100)*.0035f
        return current+delta*(1f-exp(-seconds.coerceIn(0f,.25f)/tau))
    }
}
