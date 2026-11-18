package com.cosmod.launcherglass.haptic

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.roundToInt

internal class HapticTimeline(
    context: Context,
    private val ink: Int,
    private val accent: Int,
    private val change: (time: Int, value: Float, finished: Boolean) -> Unit,
    private val seek: (Int) -> Unit,
) : View(context) {
    var pattern: HapticPattern? = null
        set(value) { field = value; curvesChanged() }
    var source: HapticAnalysis.Track? = null
        set(value) { field = value; curvesChanged() }
    var channel = HapticChannel.AMPLITUDE
        set(value) { field = value; curvesChanged() }
    var limits = 0f..1f
        set(value) { field = value; curvesChanged() }
    var selected: Int? = null
        set(value) { field = value; invalidate() }
    var cursor = 0
        set(value) { field = value; invalidate() }
    var drawCurve = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val wave = Path()
    private var curvesDirty = true
    private val tickIntervals = intArrayOf(50, 100, 250, 500, 1000, 2500, 5000, 10_000, 20_000, 30_000)
    private val density = resources.displayMetrics.density
    private var leftMs = 0f
    private var zoom = 1f
    private var lastX = 0f
    private var downX = 0f
    private var downY = 0f
    private var panning = false
    private var pinched = false
    private val length get() = (pattern?.length ?: 2000).toFloat()
    private val visible get() = length / zoom
    private val graphHeight get() = (height - 25 * density).coerceAtLeast(1f)
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            pinched = true
            return true
        }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val focus = detector.focusX / width.coerceAtLeast(1)
            val anchor = leftMs + visible * focus
            zoom = (zoom * detector.scaleFactor).coerceIn(1f, 64f)
            leftMs = clampLeft(anchor - visible * focus)
            curvesChanged()
            return true
        }
    })

    private fun curvesChanged() { curvesDirty = true; invalidate() }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        curvesChanged()
    }

    override fun onDraw(canvas: Canvas) {
        val project = pattern ?: return
        val visibleStart = clampLeft(leftMs)
        val visibleEnd = visibleStart + visible
        fun x(time: Float) = (time - visibleStart) * width / visible
        fun y(value: Float) = 8 * density +
            (1 - ((value - limits.start) / (limits.endInclusive - limits.start)).coerceIn(0f, 1f)) *
            (graphHeight - 16 * density).coerceAtLeast(1f)

        val interval = tickIntervals.firstOrNull { visible / it <= 6 } ?: 30_000
        paint.color = ink
        paint.strokeWidth = density
        paint.textSize = 10 * density
        paint.style = Paint.Style.FILL
        for (time in (visibleStart.toInt() / interval * interval)..visibleEnd.toInt() step interval) {
            if (time < visibleStart) continue
            val tick = x(time.toFloat())
            paint.alpha = 38
            canvas.drawLine(tick, 0f, tick, graphHeight, paint)
            paint.alpha = 155
            canvas.drawText(if (interval < 1000) "${time / 1000f}s" else "${time / 1000}s",
                tick + 3 * density, height - 6 * density, paint)
        }

        if (curvesDirty) {
            wave.rewind()
            source?.let { audio ->
                val center = graphHeight * .5f
                val first = (visibleStart.toInt() / HapticAnalysis.HOP_MS).coerceAtLeast(0)
                val last = minOf(audio.frames.lastIndex, visibleEnd.toInt() / HapticAnalysis.HOP_MS)
                for (index in first..last step ((last - first) / width.coerceAtLeast(1)).coerceAtLeast(1)) {
                    val rms = audio.frames[index].rms
                    val peak = (rms * 4f).coerceAtMost(1f) * center * .88f
                    val tick = x(index * HapticAnalysis.HOP_MS.toFloat())
                    wave.moveTo(tick, center - peak)
                    wave.lineTo(tick, center + peak)
                }
            }
            path.rewind()
            val sections = (width / 2).coerceIn(1, 350)
            for (i in 0..sections) {
                val at = (visibleStart + visible * i / sections).roundToInt().coerceIn(0, project.length)
                val line = y(value(project.at(at)))
                val px = i * width.toFloat() / sections
                if (i == 0) path.moveTo(px, line) else path.lineTo(px, line)
            }
            curvesDirty = false
        }
        paint.style = Paint.Style.STROKE
        paint.color = ink
        paint.alpha = 58
        paint.strokeWidth = density
        canvas.drawPath(wave, paint)
        paint.color = accent
        paint.strokeWidth = 2.2f * density
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        var lastMarker = -100f
        for (point in project.points) {
            if (point.ms < visibleStart) continue
            if (point.ms > visibleEnd) break
            val marker = x(point.ms.toFloat())
            if (marker - lastMarker < 9f * density && point.ms != selected) continue
            lastMarker = marker
            canvas.drawCircle(marker, y(value(point)), (if (point.ms == selected) 5f else 2.4f) * density, paint)
        }
        paint.color = ink
        paint.alpha = 210
        val playhead = x(cursor.toFloat())
        canvas.drawLine(playhead, 0f, playhead, graphHeight, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (pattern == null) return false
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pinched = false
                panning = false
                downX = event.x
                downY = event.y
                lastX = event.x
                if (drawCurve) parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> parent.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_MOVE -> {
                if (pinched) return true
                if (drawCurve && downY < graphHeight) apply(event, false)
                else if (abs(event.x - downX) > 8 * density) {
                    parent.requestDisallowInterceptTouchEvent(true)
                    if (downY >= graphHeight) seek(time(event.x))
                    else {
                        panning = true
                        leftMs = clampLeft(leftMs - (event.x - lastX) * visible / width.coerceAtLeast(1))
                        curvesChanged()
                    }
                }
                lastX = event.x
            }
            MotionEvent.ACTION_UP -> {
                if (!pinched && !panning) {
                    if (drawCurve && downY < graphHeight) apply(event, true)
                    else seek(time(event.x))
                    performClick()
                }
                parent.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL -> {
                if (drawCurve && !pinched) apply(event, true)
                parent.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
    private fun clampLeft(time: Float) = time.coerceIn(0f, (length - visible).coerceAtLeast(0f))
    private fun time(x: Float) = (leftMs + visible * x.coerceIn(0f, width.toFloat()) / width.coerceAtLeast(1))
        .roundToInt().coerceIn(0, length.toInt())
    private fun apply(event: MotionEvent, finished: Boolean) {
        val normalized = (1 - (event.y - 8 * density) / (graphHeight - 16 * density).coerceAtLeast(1f)).coerceIn(0f, 1f)
        val time = time(event.x)
        val snapped = ((time / 16f).roundToInt() * 16).coerceIn(0, length.toInt())
        change(snapped, limits.start + normalized * (limits.endInclusive - limits.start), finished)
    }

    private fun value(point: HapticPoint): Float = when (channel) {
        HapticChannel.AMPLITUDE -> point.amplitude
        HapticChannel.FREQUENCY -> point.hz
        HapticChannel.RICHTAP -> point.pitch
        HapticChannel.SHARPNESS -> point.sharpness
    }
}
