package com.cosmod.launcherglass.util

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader

internal class VolumeRim {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var height = 0f
    private var strength = -1

    fun draw(canvas: Canvas, bounds: RectF, radius: Float, stroke: Float, percent: Int) {
        if (percent <= 0 || bounds.isEmpty) return
        if (height != bounds.height() || strength != percent) {
            height = bounds.height()
            strength = percent
            val colors = intArrayOf(100, 29, 8, 64).map {
                Color.argb((it * percent / 100).coerceAtMost(255), 255, 255, 255)
            }.toIntArray()
            paint.shader = LinearGradient(0f, 0f, 0f, height, colors,
                floatArrayOf(0f, .20f, .73f, 1f), Shader.TileMode.CLAMP)
        }
        paint.strokeWidth = stroke
        val inset = stroke * .5f
        val corner = (radius - inset).coerceAtLeast(0f)
        val saved = canvas.save()
        canvas.translate(bounds.left, bounds.top)
        canvas.drawRoundRect(inset, inset, bounds.width() - inset, height - inset, corner, corner, paint)
        canvas.restoreToCount(saved)
    }
}
