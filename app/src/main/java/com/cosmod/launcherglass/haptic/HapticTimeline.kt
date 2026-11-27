package com.cosmod.launcherglass.haptic

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RenderNode
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

internal enum class HapticDrawMode { NAVIGATE, POINT, BRUSH }

internal class HapticTimeline(
    context: Context,
    private val ink: Int,
    private val accent: Int,
    private val change: (stroke: List<Pair<Int, Float>>, finished: Boolean) -> Unit,
    private val seek: (Int) -> Unit,
    private val cancelChange: () -> Unit,
    private val dragPoint: (source: Int, time: Int, value: Float, finished: Boolean) -> Unit,
) : View(context) {
    data class Viewport(val zoom: Float, val left: Float)

    var editorLanes = false
        set(value) {
            if (field == value) return
            field = value
            curvesChanged()
        }
    var audioLabel = ""
        set(value) {
            if (field == value) return
            field = value
            chartChanged()
        }
    var hapticLabel = ""
        set(value) {
            if (field == value) return
            field = value
            chartChanged()
        }
    var pattern: HapticPattern? = null
        set(value) {
            field = value
            leftMs = clampLeft(leftMs)
            curvesChanged()
        }
    var source: HapticAnalysis.Track? = null
        set(value) { field = value; curvesChanged() }
    var channel = HapticChannel.AMPLITUDE
        set(value) { field = value; curvesChanged() }
    var limits = 0f..1f
        set(value) { field = value; curvesChanged() }
    var selected: Int? = null
        set(value) {
            if (field == value) return
            field = value
            chartChanged()
        }
    var cursor = 0
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }
    var drawMode = HapticDrawMode.NAVIGATE
    private val stroke = java.util.TreeMap<Int, Float>()
    private var editTime: Int? = null
    private var originalPointValue = 0f
    private var pointDrag: Pair<Int, Float>? = null
    private var previousSample: Pair<Int, Float>? = null
    private var drawing = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val wave = Path()
    private val chart = RenderNode("CPI.hapticChart")
    private var curvesDirty = true
    private var chartDirty = true
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
    private val audioLane get() = if (editorLanes && source != null) minOf(64 * density, graphHeight * .35f) else 0f
    private val graphTop get() = if (editorLanes) audioLane + 19 * density else 0f
    private val plotHeight get() = (graphHeight - graphTop - 16 * density).coerceAtLeast(1f)
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            finishEditing()
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

    private fun chartChanged() {
        chartDirty = true
        invalidate()
    }

    private fun curvesChanged() {
        curvesDirty = true
        chartChanged()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        chart.setPosition(0, 0, w, h)
        curvesChanged()
    }

    override fun onDetachedFromWindow() {
        chart.discardDisplayList()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val project = pattern ?: return
        val start = clampLeft(leftMs)
        if (canvas.isHardwareAccelerated && width > 0 && height > 0) {
            if (chartDirty || !chart.hasDisplayList()) {
                val recording = chart.beginRecording()
                try {
                    drawChart(recording, project, start)
                    chartDirty = false
                } finally {
                    chart.endRecording()
                }
            }
            canvas.drawRenderNode(chart)
        } else {
            drawChart(canvas, project, start)
        }
        paint.style = Paint.Style.FILL
        paint.strokeWidth = density
        paint.color = ink
        paint.alpha = 210
        val playhead = (cursor - start) * width / visible
        canvas.drawLine(playhead, 0f, playhead, graphHeight, paint)
    }

    private fun drawChart(canvas: Canvas, project: HapticPattern, visibleStart: Float) {
        val visibleEnd = visibleStart + visible
        fun x(time: Float) = (time - visibleStart) * width / visible
        fun y(value: Float) = graphTop + 8 * density +
            (1 - ((value - limits.start) / (limits.endInclusive - limits.start)).coerceIn(0f, 1f)) *
            plotHeight

        if (editorLanes) {
            paint.style = Paint.Style.FILL
            paint.color = ink
            paint.alpha = 17
            val radius = 10 * density
            if (audioLane > 0) canvas.drawRoundRect(0f, 0f, width.toFloat(), audioLane - 3 * density, radius, radius, paint)
            paint.color = accent
            paint.alpha = 18
            canvas.drawRoundRect(0f, audioLane, width.toFloat(), graphHeight, radius, radius, paint)
            paint.color = ink
            paint.alpha = 176
            paint.textSize = 11 * density
            if (audioLane > 0) canvas.drawText(audioLabel, 8 * density, 15 * density, paint)
            canvas.drawText(hapticLabel, 8 * density, audioLane + 15 * density, paint)
        }

        val interval = tickIntervals.firstOrNull { visible / it <= 6 } ?: 30_000
        paint.color = ink
        paint.strokeWidth = density
        paint.textSize = 10 * density
        paint.style = Paint.Style.FILL
        for (time in (visibleStart.toInt() / interval * interval)..visibleEnd.toInt() step interval) {
            if (time < visibleStart) continue
            val tick = x(time.toFloat())
            paint.alpha = 38
            canvas.drawLine(tick, graphTop, tick, graphHeight, paint)
            paint.alpha = 155
            canvas.drawText(if (interval < 1000) "${time / 1000f}s" else "${time / 1000}s",
                tick + 3 * density, height - 6 * density, paint)
        }

        if (curvesDirty) {
            wave.rewind()
            source?.let { audio ->
                val center = if (editorLanes) audioLane * .59f else graphHeight * .5f
                val first = (visibleStart.toInt() / HapticAnalysis.HOP_MS).coerceAtLeast(0)
                val last = minOf(audio.frames.lastIndex, visibleEnd.toInt() / HapticAnalysis.HOP_MS)
                for (index in first..last step ((last - first) / width.coerceAtLeast(1)).coerceAtLeast(1)) {
                    val rms = audio.frames[index].rms
                    val peak = (rms * 4f).coerceAtMost(1f) * (if (editorLanes) audioLane * .31f else center * .88f)
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
            val time = point.ms + project.offset
            if (time < visibleStart) continue
            if (time > visibleEnd) break
            val marker = x(time.toFloat())
            if (marker - lastMarker < 9f * density && point.ms != selected) continue
            lastMarker = marker
            canvas.drawCircle(marker, y(value(project.at(time))), (if (point.ms == selected) 5f else 2.4f) * density, paint)
        }
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
                if (drawMode == HapticDrawMode.BRUSH && downY in graphTop..<graphHeight) {
                    parent.requestDisallowInterceptTouchEvent(true)
                    sampleStroke(event.x, event.y, publish = false)
                } else if (drawMode == HapticDrawMode.POINT && downY in graphTop..<graphHeight) {
                    editTime = hitPoint(event.x, event.y)
                    editTime?.let { source ->
                        originalPointValue = value(requireNotNull(pattern).sourcePoint(source))
                        parent.requestDisallowInterceptTouchEvent(true)
                    }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                finishEditing()
                pinched = true
                parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (pinched) return true
                val grabbed = editTime
                if (grabbed != null && (pointDrag != null || kotlin.math.hypot(event.x - downX, event.y - downY) > 5 * density)) {
                    val (at, level) = movedPoint(grabbed, event.x, event.y)
                    pointDrag = at to level
                    dragPoint(grabbed, at, level, false)
                } else if (stroke.isNotEmpty()) {
                    for (i in 0 until event.historySize) sampleStroke(event.getHistoricalX(i), event.getHistoricalY(i), publish = false)
                    sampleStroke(event.x, event.y)
                }
                else if (grabbed == null && abs(event.x - downX) > 8 * density) {
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
                    val grabbed = editTime
                    if (grabbed != null && pointDrag != null) {
                        pointDrag = movedPoint(grabbed, event.x, event.y)
                        finishEditing()
                    } else if (stroke.isNotEmpty()) {
                        sampleStroke(event.x, event.y, publish = false)
                        drawing = true
                        finishEditing()
                    } else seek(grabbed?.plus(pattern?.offset ?: 0)?.coerceIn(0, length.toInt()) ?: time(event.x))
                    performClick()
                }
                clearStroke()
                parent.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL -> {
                if (drawing || pointDrag != null) cancelChange()
                clearStroke()
                parent.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
    private fun clampLeft(time: Float) = time.coerceIn(0f, (length - visible).coerceAtLeast(0f))
    private fun time(x: Float) = (leftMs + visible * x.coerceIn(0f, width.toFloat()) / width.coerceAtLeast(1))
        .roundToInt().coerceIn(0, length.toInt())
    fun zoomBy(factor: Float) {
        val anchor = cursor.coerceIn(leftMs.toInt(), (leftMs + visible).toInt())
        val fraction = (anchor - leftMs) / visible
        zoom = (zoom * factor).coerceIn(1f, 64f)
        leftMs = clampLeft(anchor - fraction * visible)
        curvesChanged()
    }

    fun viewport() = Viewport(zoom, leftMs)

    fun restoreViewport(state: Viewport?) {
        zoom = (state?.zoom ?: (length / 8_000f)).coerceIn(1f, 64f)
        leftMs = clampLeft(state?.left ?: 0f)
        curvesChanged()
    }

    fun follow(position: Int) {
        if (position < leftMs || position > leftMs + visible) {
            leftMs = clampLeft(position - visible * .12f)
            curvesChanged()
        }
        cursor = position
    }

    private fun hitPoint(x: Float, y: Float): Int? {
        val project = pattern ?: return null
        val touchedTime = leftMs + visible * x / width.coerceAtLeast(1)
        val maxDistance = 19 * density
        var nearest: Int? = null
        var best = maxDistance
        for (point in project.points) {
            val time = point.ms + project.offset
            val dx = (time - touchedTime) * width / visible
            if (dx < -maxDistance) continue
            if (dx > maxDistance) break
            val amplitude = if (channel == HapticChannel.AMPLITUDE) project.at(time).amplitude else value(point)
            val normalized = ((amplitude - limits.start) / (limits.endInclusive - limits.start)).coerceIn(0f, 1f)
            val pointY = graphTop + 8 * density + (1 - normalized) * plotHeight
            val distance = kotlin.math.hypot(dx, pointY - y)
            if (distance < best) {
                best = distance
                nearest = point.ms
            }
        }
        return nearest
    }

    private fun valueAt(y: Float): Float {
        val normalized = (1 - (y - graphTop - 8 * density) / plotHeight).coerceIn(0f, 1f)
        return limits.start + normalized * (limits.endInclusive - limits.start)
    }

    private fun movedPoint(source: Int, x: Float, y: Float): Pair<Int, Float> {
        val delta = ((x - downX) * visible / width.coerceAtLeast(1)).roundToInt()
        val at = (source + (pattern?.offset ?: 0) + delta).coerceIn(0, length.toInt())
        val valueDelta = (downY - y) / plotHeight *
            (limits.endInclusive - limits.start)
        return at to (originalPointValue + valueDelta).coerceIn(limits.start, limits.endInclusive)
    }

    private fun sampleStroke(x: Float, y: Float, publish: Boolean = true) {
        val value = valueAt(y)
        val at = time(x)
        previousSample?.let { (before, lastValue) ->
            val step = maxOf(8f, visible / 260f)
            val segments = ceil(abs(at - before) / step).toInt()
            if (segments > 1) {
                stroke.subMap(minOf(before, at), false, maxOf(before, at), false).clear()
                for (i in 1 until segments) {
                    val progress = i.toFloat() / segments
                    stroke[(before + (at - before) * progress).roundToInt()] = lastValue + (value - lastValue) * progress
                }
            }
        }
        stroke[at] = value
        previousSample = at to value
        if (publish) {
            drawing = true
            publishStroke(false)
        }
    }

    private fun publishStroke(finished: Boolean) {
        val at = previousSample?.first ?: return
        val samples = stroke.entries.map { it.key to it.value }
        change(samples.filter { it.first != at } + (at to stroke.getValue(at)), finished)
    }

    private fun finishEditing() {
        val source = editTime
        if (source != null) pointDrag?.let { (time, level) -> dragPoint(source, time, level, true) }
        if (drawing) publishStroke(true)
        clearStroke()
    }

    private fun clearStroke() {
        drawing = false
        stroke.clear()
        previousSample = null
        editTime = null
        pointDrag = null
    }

    private fun value(point: HapticPoint): Float = when (channel) {
        HapticChannel.AMPLITUDE -> point.amplitude
        HapticChannel.FREQUENCY -> point.hz
        HapticChannel.RICHTAP -> point.pitch
        HapticChannel.SHARPNESS -> point.sharpness
    }
}
