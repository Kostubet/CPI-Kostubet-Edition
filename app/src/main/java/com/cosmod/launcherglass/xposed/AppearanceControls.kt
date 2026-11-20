package com.cosmod.launcherglass.xposed

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.view.doOnLayout
import com.cosmod.launcherglass.util.PreviewIcons

internal class AppearanceSegmentContract(loader: ClassLoader) {
    private val type = loader.loadClass("com.coui.appcompat.segmentbutton.COUISegmentButtonLayout")
    private val constructor = type.getConstructor(Context::class.java, AttributeSet::class.java)
    private val labels = type.getMethod("setSegmentButtons", Array<String>::class.java)
    private val select = type.getMethod("selectSegmentAt", Int::class.javaPrimitiveType)
    private val getSelected = type.getMethod("getSelectedSegmentPosition")
    fun create(context: Context, options: List<String>, onSelect: (Int) -> Unit): AppearanceSegment {
        val view = constructor.newInstance(context, null) as View
        labels.invoke(view, options.toTypedArray() as Any)
        // The launcher inlines its listener with a concrete cast. Observe the native
        // selection instead, preserving the segment's own drag and spring behavior.
        var previous = -1
        val observer = android.view.ViewTreeObserver.OnPreDrawListener {
            if (view.isShown) {
                val selected = (getSelected.invoke(view) as Int)
                if (selected != previous && selected in options.indices) {
                    previous = selected
                    onSelect(selected)
                }
            }
            true
        }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.viewTreeObserver.addOnPreDrawListener(observer)
            }
            override fun onViewDetachedFromWindow(v: View) {
                v.viewTreeObserver.removeOnPreDrawListener(observer)
            }
        })
        return AppearanceSegment(view) { index ->
            view.doOnLayout { if (getSelected.invoke(view) != index) select.invoke(view, index) }
        }
    }
}

internal class AppearanceSegment(val view: View, val select: (Int) -> Unit)

internal class AppearanceSample(
    context: Context,
    private val weather: PreviewIcons.Icon?,
    private val stock: Boolean,
    private val accent: Int,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bounds = RectF()
    private val iconBounds = RectF()
    private val mask = android.graphics.Path()
    private var plate = Color.TRANSPARENT
    private var glyph = Color.WHITE
    private var selected = false
    private var edge: LinearGradient? = null
    private var logoFilter = PorterDuffColorFilter(glyph, PorterDuff.Mode.SRC_IN)

    fun setPlate(plate: Int, glyph: Int, selected: Boolean) {
        if (this.plate == plate && this.glyph == glyph && this.selected == selected) return
        if (this.glyph != glyph) logoFilter = PorterDuffColorFilter(glyph, PorterDuff.Mode.SRC_IN)
        this.plate = plate
        this.glyph = glyph
        this.selected = selected
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val inset = density
        val radius = density * 13
        bounds.set(inset, inset, w - inset, h - inset)
        iconBounds.set(bounds)
        iconBounds.inset(density * 4, density * 4)
        mask.rewind()
        mask.addRoundRect(bounds, radius, radius, android.graphics.Path.Direction.CW)
        if (h > 0) edge = LinearGradient(0f, 0f, w * .65f, h.toFloat(),
            intArrayOf(0x40ffffff, 0x00ffffff, 0x2cffffff), null, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val radius = density * 13
        paint.style = Paint.Style.FILL
        if (!stock && plate != Color.TRANSPARENT) {
            paint.color = plate
            canvas.drawRoundRect(bounds, radius, radius, paint)
            paint.shader = edge
            paint.alpha = 255
            canvas.drawRoundRect(bounds, radius, radius, paint)
            paint.shader = null
        }
        val image = if (stock) weather?.source else weather?.layers?.glyphBitmap ?: weather?.source
        if (image != null) {
            paint.alpha = 255
            if (!stock) paint.colorFilter = logoFilter
            val layer = canvas.save()
            canvas.clipPath(mask)
            canvas.drawBitmap(image, null, if (stock) bounds else iconBounds, paint)
            canvas.restoreToCount(layer)
            paint.colorFilter = null
        }
        if (selected) {
            paint.color = accent
            paint.strokeWidth = density * 1.25f
            paint.style = Paint.Style.STROKE
            canvas.drawRoundRect(bounds, radius, radius, paint)
        }
    }
}

internal class AppearanceSpectrum(context: Context, private val range: Int) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cursorHsv = floatArrayOf(0f, 1f, 1f)
    private var colors = intArrayOf(Color.BLACK, Color.WHITE)
    private var gradient: LinearGradient? = null
    private var handle: Int? = null
    private var start = 0f
    private var end = 0f
    private var inputStart = Float.NaN
    private var inputLength = 0f
    var position = 0
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    fun setColors(value: IntArray) {
        if (colors.contentEquals(value)) return
        colors = value
        updateGradient()
    }

    fun setHandle(color: Int) {
        if (handle == color) return
        handle = color
        invalidate()
    }

    fun setInputBounds(first: Float, length: Float) {
        if (!first.isFinite() || length <= 0f) return
        inputStart = first
        inputLength = length
        updateGradient()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = updateGradient()

    private fun updateGradient() {
        val inset = 12f * density
        if (width <= inset * 2) return
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val nativeEnd = if (rtl) inputStart - inputLength else inputStart + inputLength
        val useNative = inputLength >= width * .55f && inputStart.isFinite()
        start = if (useNative) minOf(nativeEnd, inputStart).coerceIn(inset, width - inset) else inset
        end = if (useNative) maxOf(nativeEnd, inputStart).coerceIn(start + 1f, width.toFloat()) else width - inset
        end = end.coerceAtMost(width - inset)
        val samples = if (rtl) colors.reversedArray() else colors
        gradient = LinearGradient(start, 0f, end, 0f, samples, null, Shader.TileMode.CLAMP)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (gradient == null) return
        val y = height / 2f
        val radius = 8f * density
        paint.shader = gradient
        paint.color = Color.WHITE
        canvas.drawRoundRect(start - radius, y - radius, end + radius, y + radius, radius, radius, paint)
        paint.shader = null
        val fraction = position.toFloat() / range
        val x = start + (end - start) * if (layoutDirection == LAYOUT_DIRECTION_RTL) 1f - fraction else fraction
        paint.color = 0x59000000
        canvas.drawCircle(x, y + density, 11f * density, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(x, y, 10.5f * density, paint)
        cursorHsv[0] = position.toFloat() % 360f
        paint.color = handle ?: Color.HSVToColor(cursorHsv)
        canvas.drawCircle(x, y, radius, paint)
    }
}
