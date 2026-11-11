package com.cosmod.launcherglass.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.widget.LinearLayout
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Paints one material behind the independently touchable, spring-driven controls. */
internal class GlassActionRow(
    context: Context,
    private val tint: Int,
    private val dark: Boolean
) : LinearLayout(context) {
    var drawBackdrop: ((Canvas) -> Unit)? = null
    private val first = RectF()
    private val second = RectF()
    private val material = GlassPairMaterial(context, tint, dark)

    init {
        orientation = HORIZONTAL
        clipChildren = false
        clipToPadding = false
    }

    override fun dispatchDraw(canvas: Canvas) {
        glassBounds(getChildAt(0), first)
        glassBounds(getChildAt(1), second)
        material.draw(canvas, first, second, height, drawBackdrop)
        super.dispatchDraw(canvas)
    }
}

internal fun glassBounds(control: View?, out: RectF, offsetX: Float = 0f, offsetY: Float = 0f) {
    if (control == null || control.visibility != View.VISIBLE) {
        out.setEmpty()
        return
    }
    val x = offsetX + control.x + (1f - control.scaleX) * control.pivotX
    val y = offsetY + control.y + (1f - control.scaleY) * control.pivotY
    out.set(x, y, x + control.width * control.scaleX, y + control.height * control.scaleY)
}

internal class GlassPairMaterial(context: Context, private val tint: Int, private val dark: Boolean) {
    private val density = context.resources.displayMetrics.density
    private val outlines = Path()
    private val bridge = Path()
    private val cap = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * .85f
    }
    private var lastHeight = 0
    private val sideFade=sideSheenFade(0f,1f)
    private val edgeMatrix=android.graphics.Matrix()
    private val edgeBounds=RectF()

    private fun connect(left: RectF, right: RectF) {
        val r1 = minOf(left.width(), left.height()) * .5f
        val r2 = minOf(right.width(), right.height()) * .5f
        val ax = left.right - r1
        val ay = left.centerY()
        val bx = right.left + r2
        val by = right.centerY()
        val dx = bx - ax
        val dy = by - ay
        val distance = hypot(dx, dy)
        val reach = 5.5f * density
        if (dx <= 0f || distance <= abs(r1 - r2) || distance >= r1 + r2 + reach) return

        // Match both cap tangents. The neck widens continuously as the two caps approach.
        val angle = .94f * sqrt(((r1 + r2 + reach - distance) / reach).coerceIn(0f, 1f))
        val u = cos(angle)
        val v = sin(angle)
        val x = dx / distance
        val y = dy / distance
        val ax1 = ax + r1 * (x * u + y * v)
        val ay1 = ay + r1 * (y * u - x * v)
        val bx1 = bx - r2 * (x * u - y * v)
        val by1 = by - r2 * (y * u + x * v)
        val ax2 = ax + r1 * (x * u - y * v)
        val ay2 = ay + r1 * (y * u + x * v)
        val bx2 = bx - r2 * (x * u + y * v)
        val by2 = by - r2 * (y * u - x * v)
        val handle = minOf(minOf(r1, r2) * v * .72f, hypot(bx1 - ax1, by1 - ay1) * .40f)
        val nearX = (x * v - y * u) * handle
        val nearY = (y * v + x * u) * handle
        val farX = (x * v + y * u) * handle
        val farY = (y * v - x * u) * handle
        bridge.rewind()
        bridge.moveTo(ax1, ay1)
        bridge.cubicTo(ax1 + nearX, ay1 + nearY, bx1 - farX, by1 - farY, bx1, by1)
        bridge.lineTo(bx2, by2)
        bridge.cubicTo(bx2 - nearX, by2 - nearY, ax2 + farX, ay2 + farY, ax2, ay2)
        bridge.close()
        outlines.op(bridge, Path.Op.UNION)
    }

    fun draw(canvas: Canvas, first: RectF, second: RectF, height: Int, backdrop: ((Canvas) -> Unit)?) {
        if (height > 0 && lastHeight != height) {
            lastHeight = height
            val vertical = LinearGradient(0f, 0f, 0f, height.toFloat(),
                intArrayOf(if (dark) 0x55ffffff else 0x82ffffff.toInt(),
                    if (dark) 0x19ffffff else 0x150e2540,if (dark) 0x63ffffff else 0x55ffffff),
                floatArrayOf(0f,.55f,1f),Shader.TileMode.CLAMP)
            edge.shader=android.graphics.ComposeShader(vertical,sideFade,android.graphics.PorterDuff.Mode.DST_IN)
        }
        outlines.rewind()
        for (i in 0..1) {
            val rect = if (i == 0) first else second
            if (rect.isEmpty) continue
            val radius = minOf(rect.width(), rect.height()) * .5f
            if(i==1 && RectF.intersects(first,second)){
                cap.rewind()
                cap.addRoundRect(rect,radius,radius,Path.Direction.CW)
                outlines.op(cap,Path.Op.UNION)
            }else outlines.addRoundRect(rect,radius,radius,Path.Direction.CW)
        }
        if (!first.isEmpty && !second.isEmpty) {
            if (first.centerX() <= second.centerX()) connect(first, second) else connect(second, first)
        }
        if (!outlines.isEmpty) {
            val sample = backdrop
            if (sample != null && canvas.isHardwareAccelerated) {
                val save = canvas.save()
                canvas.clipPath(outlines)
                sample(canvas)
                canvas.restoreToCount(save)
                fill.color = tint
            } else fill.color = tint or Color.BLACK
            canvas.drawPath(outlines, fill)
            @Suppress("DEPRECATION")
            outlines.computeBounds(edgeBounds,true)
            edgeMatrix.setScale(edgeBounds.width().coerceAtLeast(1f),1f)
            edgeMatrix.postTranslate(edgeBounds.left,0f)
            sideFade.setLocalMatrix(edgeMatrix)
            canvas.drawPath(outlines, edge)
        }
    }
}
