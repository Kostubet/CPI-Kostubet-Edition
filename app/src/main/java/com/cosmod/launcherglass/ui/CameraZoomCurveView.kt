package com.cosmod.launcherglass.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import com.cosmod.launcherglass.camera.ZoomTrajectory
import com.cosmod.launcherglass.camera.ZoomTuning

internal class CameraZoomCurveView(context: Context, private val ink: Int, private val accent: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var curve: ZoomTrajectory? = null
    private var pendingTarget = Double.NaN
    private var pendingTuning: ZoomTuning? = null
    private val refresh = Runnable {
        pendingTuning?.let { curve = ZoomTrajectory(pendingTarget, it) }
        invalidate()
    }

    fun preview(target: Double, tuning: ZoomTuning) {
        if (target == pendingTarget && tuning == pendingTuning) return
        pendingTarget = target
        pendingTuning = tuning
        removeCallbacks(refresh)
        postOnAnimation(refresh)
    }

    override fun onDraw(canvas: Canvas) {
        val trajectory = curve ?: return
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
        val plotSamples = plotWidth.coerceIn(2, 600)
        for (index in 0..plotSamples) {
            val fraction = index.toFloat() / plotSamples
            val x = paddingLeft + fraction * plotWidth
            val y = paddingTop + (1 - trajectory.sampleAt(fraction)) * plotHeight
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
        for (tick in 0..4) {
            val label = "${tick * trajectory.durationSeconds / 4}s"
            val x = paddingLeft + tick * (plotWidth - paint.measureText(label)) / 4
            canvas.drawText(label, x, paddingTop + plotHeight + 14f * density, paint)
        }
    }
}
