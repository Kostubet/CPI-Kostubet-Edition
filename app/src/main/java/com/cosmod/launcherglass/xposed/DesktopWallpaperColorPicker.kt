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
    private val radius = 33f * density
    private val lift = 62f * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val crop = RectF()
    private val lens = Path()
    private var x = 0f
    private var y = 0f
    private var zoom = 1.65f

    init {
        contentDescription = description
        isClickable = true
        isFocusable = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val scale = max(w.toFloat() / wallpaper.width, h.toFloat() / wallpaper.height)
        zoom = (1.35f / scale).coerceIn(1.15f, 1.85f)
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

        val margin = radius + 5f * density
        val lensX = x.coerceIn(margin, max(margin, width - margin))
        val liftedY = if (y >= margin + lift) y - lift else y + lift
        val lensY = liftedY.coerceIn(margin, max(margin, height - margin))
        lens.rewind()
        lens.addCircle(lensX, lensY, radius, Path.Direction.CW)
        val saved = canvas.save()
        canvas.clipPath(lens)
        canvas.translate(lensX, lensY)
        canvas.scale(zoom, zoom)
        canvas.translate(-x, -y)
        canvas.drawBitmap(wallpaper, null, crop, paint)
        canvas.restoreToCount(saved)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 7f * density
        paint.color = 0xdb191919.toInt()
        canvas.drawCircle(lensX, lensY, radius, paint)
        paint.strokeWidth = 5f * density
        paint.color = sampledColor()
        canvas.drawCircle(lensX, lensY, radius, paint)
        paint.strokeWidth = density
        paint.color = Color.WHITE
        canvas.drawCircle(lensX, lensY, radius + 3f * density, paint)
        drawTarget(canvas, lensX, lensY, 5f * density)
        drawTarget(canvas, x, y, 8f * density)
    }

    private fun drawTarget(canvas: Canvas, cx: Float, cy: Float, length: Float) {
        fun lines() {
            val gap = 2f * density
            canvas.drawLine(cx - length, cy, cx - gap, cy, paint)
            canvas.drawLine(cx + gap, cy, cx + length, cy, paint)
            canvas.drawLine(cx, cy - length, cx, cy - gap, paint)
            canvas.drawLine(cx, cy + gap, cx, cy + length, paint)
        }
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Color.BLACK
        paint.strokeWidth = 3f * density
        lines()
        paint.color = Color.WHITE
        paint.strokeWidth = 1.25f * density
        lines()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return true
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
