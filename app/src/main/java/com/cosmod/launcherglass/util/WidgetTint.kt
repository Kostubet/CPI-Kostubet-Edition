package com.cosmod.launcherglass.util

import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import kotlin.math.abs

/** Transparency mixes the selected tint with the original widget colors. */
internal fun widgetTintFilter(hue:Int,saturation:Int,lightness:Int,transparency:Int):ColorMatrixColorFilter? {
    if(transparency>=100)return null
    val color=Color.HSVToColor(floatArrayOf(hue.toFloat(),saturation/100f,1f))
    val gain=1f-abs(lightness)/100f
    val lift=255f*lightness.coerceAtLeast(0)/100f
    val peak=maxOf(Color.red(color),Color.green(color),Color.blue(color),1).toFloat()
    val matrix=ColorMatrix().apply {setSaturation(0f)}
    matrix.postConcat(ColorMatrix(floatArrayOf(
        Color.red(color)/peak*gain,0f,0f,0f,lift,
        0f,Color.green(color)/peak*gain,0f,0f,lift,
        0f,0f,Color.blue(color)/peak*gain,0f,lift,
        0f,0f,0f,1f,0f)))
    if(transparency>0){
        val original=ColorMatrix().array
        val amount=transparency/100f
        val values=matrix.array
        for(index in values.indices)values[index]=values[index]*(1f-amount)+original[index]*amount
        matrix.set(values)
    }
    return ColorMatrixColorFilter(matrix)
}
