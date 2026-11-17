package com.cosmod.launcherglass.util
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
/** Colors the sampled wallpaper; the alpha row stays identity at every setting. */
internal fun widgetTintFilter(hue:Int,saturation:Int,lightness:Int,transparency:Int,brightness:Int=100):ColorMatrixColorFilter? {
    if(transparency>=100)return null
    val color=Color.HSVToColor(floatArrayOf(hue.coerceIn(0,360).toFloat(),saturation.coerceIn(0,100)/100f,1f))
    val peak=maxOf(Color.red(color),Color.green(color),Color.blue(color),1).toFloat()
    return ColorMatrixColorFilter(BackdropChromaMatrix.tint(Color.red(color)/peak,Color.green(color)/peak,
        Color.blue(color)/peak,brightness,lightness,transparency))
}
