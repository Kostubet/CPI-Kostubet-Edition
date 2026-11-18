package com.cosmod.launcherglass.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import com.cosmod.launcherglass.camera.ZoomSmoothing
import com.cosmod.launcherglass.camera.ZoomTuning
import kotlin.math.ln

internal class CameraZoomCurveView(context: Context, private val ink: Int, private val accent: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var curve = FloatArray(0)

    fun preview(target: Double, tuning: ZoomTuning) {
        val engine = ZoomSmoothing(1.0)
        engine.aim(target, 0.0, gesture = true)
        val range = maxOf(1.0, target)
        val lower = minOf(1.0, target)
        curve = FloatArray(241) {
            val sample = engine.step(it / 60.0, tuning).coerceIn(lower, range)
            ((ln(sample) - ln(lower)) / (ln(range) - ln(lower)).coerceAtLeast(.001)).toFloat()
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (curve.isEmpty()) return
        val density = resources.displayMetrics.density
        val labelSpace = 18f * density
        val plotHeight = (height - labelSpace - paddingTop - paddingBottom).coerceAtLeast(1f)
        val plotWidth = (width - paddingLeft - paddingRight).coerceAtLeast(1)
        paint.color = ink
        paint.alpha = 44
        paint.strokeWidth = density
        for (i in 0..4) {
            val y = paddingTop + i * plotHeight / 4
            canvas.drawLine(paddingLeft.toFloat(), y, paddingLeft + plotWidth.toFloat(), y, paint)
        }
        path.rewind()
        curve.forEachIndexed { index, value ->
            val x = paddingLeft + index.toFloat() / curve.lastIndex * plotWidth
            val y = paddingTop + (1 - value) * plotHeight
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f * density
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = accent
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        paint.color = ink
        paint.alpha = 155
        paint.textSize = 11f * density
        for (second in 0..4) {
            val label = "${second}s"
            val x = paddingLeft + second * (plotWidth - paint.measureText(label)) / 4
            canvas.drawText(label, x, paddingTop + plotHeight + 14f * density, paint)
        }
    }
}
