package com.cosmod.launcherglass.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View

internal class ThemePreview(
    context: Context,
    dark: Boolean,
    private val selected: Boolean,
    private val textColor: Int,
    private val muted: Int,
    private val accent: Int,
    private val label: String
) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val screenTop = if (dark) 0xff277cbd.toInt() else 0xff83bcec.toInt()
    private val screenBottom = if (dark) 0xff219ca9.toInt() else 0xff94d9d7.toInt()
    private val tile = if (dark) 0xff15557c.toInt() else 0xffa7e3f7.toInt()
    private var wallpaper: Shader? = null

    init {
        contentDescription = label
        isSelected = selected
        isFocusable = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        wallpaper = LinearGradient(0f, 5f * density, 0f, 146f * density,
            screenTop, screenBottom, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val d = density
        val x = width / 2f
        val half = minOf(36f * d, width * .36f)
        val left = x - half
        val right = x + half
        paint.color = muted
        canvas.drawRoundRect(left, 5f*d, right, 144f*d, 13f*d, 13f*d, paint)
        paint.shader = wallpaper
        canvas.drawRoundRect(left+1.5f*d, 6.5f*d, right-1.5f*d, 142.5f*d, 12f*d, 12f*d, paint)
        paint.shader = null
        paint.color = Color.WHITE
        paint.textSize = 15f*d
        canvas.drawText("10:30", x, 41f*d, paint)
        paint.color = tile
        canvas.drawRoundRect(left+6f*d, 56f*d, right-6f*d, 70f*d, 4f*d, 4f*d, paint)
        canvas.drawRoundRect(left+6f*d, 75f*d, right-6f*d, 89f*d, 4f*d, 4f*d, paint)
        paint.color = 0xaaffffff.toInt()
        canvas.drawRoundRect(left+10f*d, 60f*d, left+16f*d, 66f*d, d, d, paint)
        canvas.drawRect(left+19f*d, 61f*d, x+3f*d, 62.5f*d, paint)
        canvas.drawRect(left+19f*d, 64.5f*d, right-10f*d, 66f*d, paint)
        canvas.drawRect(x-12f*d, 138f*d, x+12f*d, 139f*d, paint)
        paint.color = if (selected) accent else textColor
        paint.textSize = minOf(13f*d, (width-8f*d) / label.length * 1.65f).coerceAtLeast(1f)
        canvas.drawText(label, x, 169f*d, paint)
        if (selected) {
            paint.color = accent
            canvas.drawCircle(x, 188f*d, 9f*d, paint)
            paint.color = Color.WHITE
            canvas.drawCircle(x, 188f*d, 5.5f*d, paint)
        } else {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.4f*d
            paint.color = muted
            canvas.drawCircle(x, 188f*d, 8f*d, paint)
            paint.style = Paint.Style.FILL
        }
    }
}
