package com.cosmod.launcherglass.xposed

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.max

/** Samples only wallpaper pixels, using the same crop for display and for the eyedropper. */
internal class DesktopWallpaperColorPicker(
    context: Context,
    private val wallpaper: Bitmap,
    description: String,
    private val onColor: (Int) -> Unit,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val radius = 39f * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val crop = RectF()
    private val lens = Path()
    private var x = 0f
    private var y = 0f

    init {
        contentDescription = description
        isClickable = true
        isFocusable = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val scale = max(w.toFloat() / wallpaper.width, h.toFloat() / wallpaper.height)
        val dx = (wallpaper.width * scale - w) * .5f
        val dy = (wallpaper.height * scale - h) * .5f
        crop.set(-dx, -dy, w + dx, h + dy)
        x = w * .5f
        y = h * .5f
    }

    private fun sampledColor(): Int = wallpaper.getPixel(
        (((x - crop.left) / crop.width()) * wallpaper.width).toInt().coerceIn(0, wallpaper.width - 1),
        (((y - crop.top) / crop.height()) * wallpaper.height).toInt().coerceIn(0, wallpaper.height - 1),
    )

    override fun onDraw(canvas: Canvas) {
        if (crop.isEmpty || wallpaper.isRecycled) return
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.isFilterBitmap = true
        canvas.drawBitmap(wallpaper, null, crop, paint)

        val lensX = x.coerceIn(radius, max(radius, width - radius))
        val offset = radius + 24 * density
        val lensY = (if (y >= radius + offset) y - offset else y + offset)
            .coerceIn(radius, max(radius, height - radius))
        lens.rewind()
        lens.addCircle(lensX, lensY, radius, Path.Direction.CW)
        val saved = canvas.save()
        canvas.clipPath(lens)
        canvas.translate(lensX, lensY)
        canvas.scale(2.75f, 2.75f)
        canvas.translate(-x, -y)
        canvas.drawBitmap(wallpaper, null, crop, paint)
        canvas.restoreToCount(saved)
        paint.color = sampledColor()
        canvas.drawCircle(lensX, lensY, 7f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        paint.color = Color.WHITE
        canvas.drawCircle(lensX, lensY, radius, paint)
        canvas.drawCircle(lensX, lensY, 7f * density, paint)
        paint.strokeWidth = density
        canvas.drawLine(x - 7f * density, y, x + 7f * density, y, paint)
        canvas.drawLine(x, y - 7f * density, x, y + 7f * density, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                x = event.x.coerceIn(0f, width.toFloat())
                y = event.y.coerceIn(0f, height.toFloat())
                invalidate()
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
            MotionEvent.ACTION_CANCEL -> return true
            else -> return super.onTouchEvent(event)
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        if (!crop.isEmpty && !wallpaper.isRecycled) onColor(sampledColor())
        return true
    }
}
