package com.cosmod.launcherglass.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import com.cosmod.launcherglass.util.TiltCurve
import kotlin.math.roundToInt

internal class TiltCurveEditor(
    context: Context,
    private val accent: Int,
    private val foreground: Int,
    initial: TiltCurve,
    private val onChange: (TiltCurve, Boolean) -> Unit
) : View(context) {
    var curve = initial
        set(value) {
            if (field == value) return
            field = value
            rebuild()
        }
    val isEditing get() = pointer >= 0

    private val density = resources.displayMetrics.density
    private val plot = RectF()
    private val line = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var pointer = -1
    private var first = true
    private var beforeDrag = initial

    init {
        isFocusable = true
        isClickable = true
    }

    private fun x(time: Int) = plot.left + plot.width() * time / 100f
    private fun y(tilt: Int) = plot.bottom - plot.height() * tilt / 100f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val margin = 17f * density
        plot.set(margin, margin, w - margin, h - margin)
        rebuild()
    }

    private fun rebuild() {
        line.rewind()
        if (!plot.isEmpty) {
            line.moveTo(plot.left, plot.bottom)
            for (i in 1..100) {
                val phase = i / 100f
                line.lineTo(plot.left + plot.width() * phase, plot.bottom - plot.height() * curve.amount(phase))
            }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (plot.isEmpty) return
        paint.color = ColorUtils.setAlphaComponent(foreground, 24)
        paint.strokeWidth = density
        for (i in 0..4) {
            val x = x(i * 25)
            val y = y(i * 25)
            canvas.drawLine(x, plot.top, x, plot.bottom, paint)
            canvas.drawLine(plot.left, y, plot.right, y, paint)
        }
        paint.color = accent
        paint.strokeWidth = 2.4f * density
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.style = Paint.Style.STROKE
        canvas.drawPath(line, paint)
        paint.style = Paint.Style.FILL
        drawHandle(canvas, x(curve.firstTime), y(curve.firstTilt), first)
        drawHandle(canvas, x(curve.secondTime), y(curve.secondTilt), !first)
    }

    private fun drawHandle(canvas: Canvas, x: Float, y: Float, selected: Boolean) {
        paint.color = ColorUtils.setAlphaComponent(accent, if (selected) 54 else 26)
        canvas.drawCircle(x, y, 13f * density, paint)
        paint.color = accent
        canvas.drawCircle(x, y, 7.5f * density, paint)
        paint.color = android.graphics.Color.WHITE
        canvas.drawCircle(x, y, 3.2f * density, paint)
    }

    private fun move(px: Float, py: Float) {
        val time = ((px - plot.left) / plot.width() * 100f).roundToInt()
        val tilt = ((plot.bottom - py) / plot.height() * 100f).roundToInt()
        curve = curve.move(first, time, tilt)
        onChange(curve, false)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || plot.isEmpty) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val dx1 = event.x - x(curve.firstTime)
                val dy1 = event.y - y(curve.firstTilt)
                val dx2 = event.x - x(curve.secondTime)
                val dy2 = event.y - y(curve.secondTilt)
                first = dx1 * dx1 + dy1 * dy1 <= dx2 * dx2 + dy2 * dy2
                beforeDrag = curve
                pointer = event.getPointerId(0)
                parent.requestDisallowInterceptTouchEvent(true)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointer)
                if (index >= 0) move(event.getX(index), event.getY(index))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                if (event.getPointerId(index) == pointer) {
                    move(event.getX(index), event.getY(index))
                    pointer = -1
                    parent.requestDisallowInterceptTouchEvent(false)
                    if (curve != beforeDrag) onChange(curve, true)
                    performClick()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (isEditing) {
                    pointer = -1
                    curve = beforeDrag
                    onChange(curve, false)
                    parent.requestDisallowInterceptTouchEvent(false)
                }
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!isEnabled) return super.onKeyDown(keyCode, event)
        val time = if (first) curve.firstTime else curve.secondTime
        val tilt = if (first) curve.firstTilt else curve.secondTilt
        val next = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> curve.move(first, time - 1, tilt)
            KeyEvent.KEYCODE_DPAD_RIGHT -> curve.move(first, time + 1, tilt)
            KeyEvent.KEYCODE_DPAD_UP -> curve.move(first, time, tilt + 1)
            KeyEvent.KEYCODE_DPAD_DOWN -> curve.move(first, time, tilt - 1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE -> {
                first = !first
                invalidate()
                return true
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        if (next != curve) {
            curve = next
            onChange(next, true)
        }
        return true
    }
}
