package com.cosmod.launcherglass.util

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.Shader
import kotlin.math.roundToInt

/** Composites the blurred backdrop over the original at a fixed radius. */
internal object GlassBlur {
    fun effect(radius: Float, opacity: Int, input: RenderEffect? = null): RenderEffect? {
        if (radius <= 0f || opacity <= 0) return input
        val blurred = if (input == null) RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
            else RenderEffect.createBlurEffect(radius, radius, input, Shader.TileMode.CLAMP)
        if (opacity >= 100) return blurred

        val alpha = ColorMatrix().apply { setScale(1f, 1f, 1f, opacity / 100f) }
        val tinted = RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(alpha), blurred)
        val original = input ?: RenderEffect.createOffsetEffect(0f, 0f)
        return RenderEffect.createBlendModeEffect(original, tinted, BlendMode.SRC_OVER)
    }

    /** [source] is readable. The caller owns the returned bitmap. */
    fun bitmap(source: Bitmap, radius: Float, opacity: Int): Bitmap {
        if (radius < 1f || opacity <= 0) return checkNotNull(source.copy(Bitmap.Config.ARGB_8888, false))
        val blurred = FastBlur.blur(source, radius.roundToInt())
        if (opacity >= 100) return blurred
        val result = checkNotNull(source.copy(Bitmap.Config.ARGB_8888, true))
        try {
            val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = (opacity * 2.55f).roundToInt() }
            Canvas(result).drawBitmap(blurred, 0f, 0f, paint)
        } finally {
            blurred.recycle()
        }
        return result
    }
}
