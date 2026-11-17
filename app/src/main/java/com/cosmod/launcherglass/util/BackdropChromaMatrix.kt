package com.cosmod.launcherglass.util
/** The launcher and preview use exactly the same chroma replacement matrix. */
internal object BackdropChromaMatrix {
    fun create(red:Float,green:Float,blue:Float,saturation:Int,vibrancy:Int,brightness:Int=100):FloatArray {
        val s=saturation.coerceIn(0,100)/100f
        val keep=vibrancy.coerceIn(0,100)/100f
        val value=brightness.coerceIn(0,100)/100f
        val weights=floatArrayOf(.213f,.715f,.072f)
        val gains=floatArrayOf(1f-s+s*red,1f-s+s*green,1f-s+s*blue)
        return FloatArray(20).apply {
            for(row in 0..2)for(column in 0..2)this[row*5+column]=(gains[row]*weights[column]*(1f-keep)+(if(row==column)keep else 0f))*value
            this[18]=1f
        }
    }
    fun tint(red:Float,green:Float,blue:Float,brightness:Int,lightness:Int,transparency:Int):FloatArray {
        val keep=transparency.coerceIn(0,100)/100f
        val light=lightness.coerceIn(-100,100)
        val gain=(1f-kotlin.math.abs(light)/100f)*brightness.coerceIn(0,100)/100f
        val lift=255f*light.coerceAtLeast(0)/100f
        val channels=floatArrayOf(red,green,blue);val weights=floatArrayOf(.213f,.715f,.072f)
        return FloatArray(20).apply {
            for(row in 0..2){
                for(column in 0..2)this[row*5+column]=channels[row]*gain*weights[column]*(1f-keep)+(if(row==column)keep else 0f)
                this[row*5+4]=lift*(1f-keep)
            }
            this[18]=1f
        }
    }
}
