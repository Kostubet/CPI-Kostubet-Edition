package com.cosmod.launcherglass.xposed

import android.graphics.*
import android.util.LruCache
import com.cosmod.launcherglass.util.OpticsMath

/** Smooth photo-only alpha ramp, with a fully faded lower plateau. */
internal object PhotoFade {
    private data class Key(val top:Int,val bottom:Int,val height:Int,val strength:Int,val smoothness:Int)
    private val paints=LruCache<Key,Paint>(12)
    fun clear(){paints.evictAll()}
    fun apply(canvas:Canvas,bounds:Rect,height:Int,strength:Int,smoothness:Int){
        if(height<=0 || strength<=0 || bounds.isEmpty)return
        val key=Key(bounds.top,bounds.bottom,height,strength,smoothness)
        val paint=paints[key] ?: run {
            val positions=OpticsMath.photoStops(height,smoothness)
            val colors=IntArray(positions.size){Color.argb((OpticsMath.photoAlpha(positions[it],height,strength,smoothness)*255).toInt(),255,255,255)}
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
                shader=LinearGradient(0f,bounds.top.toFloat(),0f,bounds.bottom.toFloat(),colors,positions,Shader.TileMode.CLAMP)
                xfermode=PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            }.also {paints.put(key,it)}
        }
        canvas.drawRect(bounds,paint)
    }
}
