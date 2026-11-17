package com.cosmod.launcherglass.util
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
internal object IconBackdropFilter {
    fun create(vibrancy:Int,palette:IconPalette):ColorMatrixColorFilter? {
        if(!palette.custom || palette.clear || vibrancy>=100 && palette.tintBrightness==100)return null
        val hue=Color.HSVToColor(floatArrayOf(palette.tintHue.toFloat(),1f,1f))
        return ColorMatrixColorFilter(BackdropChromaMatrix.create(Color.red(hue)/255f,Color.green(hue)/255f,
            Color.blue(hue)/255f,palette.tintSaturation,vibrancy,palette.tintBrightness))
    }
}
