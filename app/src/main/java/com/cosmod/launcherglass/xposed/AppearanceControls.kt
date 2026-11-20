package com.cosmod.launcherglass.xposed

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.ImageView
import androidx.core.view.doOnLayout
import com.cosmod.launcherglass.ui.DesktopAppearance.Mode
import com.cosmod.launcherglass.ui.onextbox.addOplusSmoothRoundRect
import com.cosmod.launcherglass.util.PreviewIcons

internal class AppearanceSegmentContract(loader: ClassLoader) {
    private val type = loader.loadClass("com.coui.appcompat.segmentbutton.COUISegmentButtonLayout")
    private val constructor = type.getConstructor(Context::class.java, AttributeSet::class.java)
    private val labels = type.getMethod("setSegmentButtons", Array<String>::class.java)
    private val select = type.getMethod("selectSegmentAt", Int::class.javaPrimitiveType)
    private val getSelected = type.getMethod("getSelectedSegmentPosition")
    private val material = type.getMethod("setMaterialEffectEnabled", Boolean::class.javaPrimitiveType)
    private val drawMaterial = type.getMethod("setIsDrawMaterialEffect", Boolean::class.javaPrimitiveType)
    private val innerStroke = type.getMethod("setInnerShadowStrokeEnabled", Boolean::class.javaPrimitiveType)
    fun create(context: Context, options: List<String>, onSelect: (Int) -> Unit): AppearanceSegment {
        val view = constructor.newInstance(context, null) as View
        labels.invoke(view, options.toTypedArray() as Any)
        // Within the already blurred sheet, COUI draws its own edges and
        // spotlight without an additional compositor blur surface.
        material.invoke(view, true)
        drawMaterial.invoke(view, true)
        innerStroke.invoke(view, true)
        // The launcher inlines its listener with a concrete cast. Observe the native
        // selection instead, preserving the segment's own drag and spring behavior.
        var previous = -1
        val observer = android.view.ViewTreeObserver.OnPreDrawListener {
            if (view.isShown && view.isEnabled) {
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
            previous = index
            view.doOnLayout { if (getSelected.invoke(view) != index) select.invoke(view, index) }
        }
    }
}

internal class AppearanceSegment(val view: View, val select: (Int) -> Unit)

internal class AppearanceButtonContract(loader: ClassLoader) {
    private val type = loader.loadClass("com.coui.appcompat.searchview.SearchBarIconButton")
    private val constructor = type.getConstructor(Context::class.java)
    private val blur = type.getMethod("setBlurEffectEnable", Boolean::class.javaPrimitiveType)
    private val stroke = type.getMethod("setStrokeEffectEnable", Boolean::class.javaPrimitiveType)
    private val spotlight = type.getMethod("setSpotLightEnabled", Boolean::class.javaPrimitiveType)
    private val motion = type.getMethod("setTouchAnimationEnable", Boolean::class.javaPrimitiveType)

    fun create(context: Context): ImageView = (constructor.newInstance(context) as ImageView).apply {
        blur.invoke(this, true)
        stroke.invoke(this, true)
        spotlight.invoke(this, true)
        motion.invoke(this, true)
    }
}

internal class AppearanceSample(context: Context, private val mode: Mode) : View(context) {
    private var weather: AppearanceWeather? = null
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bounds = RectF()
    private val iconBounds = RectF()
    private val mask = android.graphics.Path()
    private var plate = Color.TRANSPARENT
    private var glyph = Color.WHITE
    private var selected = false
    private var edge: LinearGradient? = null
    private var rim: LinearGradient? = null
    private var logoFilter = shadedGlyph(glyph)

    fun setWeather(icon: AppearanceWeather) {
        weather = icon
        invalidate()
    }

    fun setPlate(plate: Int, glyph: Int, selected: Boolean) {
        if (this.plate == plate && this.glyph == glyph && this.selected == selected) return
        if (this.glyph != glyph) logoFilter = shadedGlyph(glyph)
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
        iconBounds.inset(density * 1.5f, density * 1.5f)
        mask.rewind()
        if (!addOplusSmoothRoundRect(mask, bounds, radius)) {
            mask.addRoundRect(bounds, radius, radius, android.graphics.Path.Direction.CW)
        }
        if (h > 0) {
            edge = LinearGradient(0f, 0f, w * .65f, h.toFloat(),
                intArrayOf(0x2affffff, 0x00ffffff, 0x0dffffff), null, Shader.TileMode.CLAMP)
            rim = LinearGradient(0f, 0f, w * .5f, h.toFloat(),
                intArrayOf(0x9affffff.toInt(), 0x16ffffff, 0x69ffffff), null, Shader.TileMode.CLAMP)
        }
    }

    private fun shadedGlyph(color: Int): ColorMatrixColorFilter {
        val gray = floatArrayOf(.2126f, .7152f, .0722f)
        val matrix = FloatArray(20)
        for ((row, channel) in intArrayOf(Color.red(color), Color.green(color), Color.blue(color)).withIndex()) {
            for (column in gray.indices) matrix[row * 5 + column] = gray[column] * (channel / 255f) * .42f
            matrix[row * 5 + 4] = channel * .58f
        }
        matrix[18] = 1f
        return ColorMatrixColorFilter(matrix)
    }

    override fun onDraw(canvas: Canvas) {
        val stock = mode == Mode.STOCK
        paint.style = Paint.Style.FILL
        if (!stock && plate != Color.TRANSPARENT) {
            paint.color = plate
            canvas.drawPath(mask, paint)
            paint.shader = edge
            paint.alpha = 255
            canvas.drawPath(mask, paint)
            paint.shader = null
        }
        val image = if (stock) weather?.source else weather?.glyph
        if (image != null) {
            paint.alpha = 255
            if (!stock && mode != Mode.DARK) {
                paint.colorFilter = logoFilter
            }
            val layer = canvas.save()
            canvas.clipPath(mask)
            canvas.drawBitmap(image, null, if (stock) bounds else iconBounds, paint)
            canvas.restoreToCount(layer)
            paint.colorFilter = null
        }
        if (selected || !stock) {
            paint.color = Color.WHITE
            paint.shader = if (selected) null else rim
            paint.strokeWidth = density * if (selected) 1.1f else .6f
            paint.style = Paint.Style.STROKE
            canvas.drawPath(mask, paint)
            paint.shader = null
        }
    }
}

/** Load Weather's package resource directly so the currently applied icon pack cannot tint the sample. */
internal class AppearanceWeather(val source: Bitmap, val glyph: Bitmap) {
    companion object {
        fun load(context: Context): AppearanceWeather? {
            val manager = context.packageManager
            for (name in listOf("net.oneplus.weather", "com.coloros.weather2", "com.oplus.weather")) {
                val icon = runCatching {
                    val app = manager.getApplicationInfo(name, 0)
                    val resources = manager.getResourcesForApplication(app)
                    val classicId = resources.getIdentifier("ic_launcher_weather", "drawable", name)
                    if (classicId != 0) {
                        val source = Bitmap.createBitmap(144, 144, Bitmap.Config.ARGB_8888)
                        resources.getDrawable(classicId, null).apply {
                            setBounds(0, 0, source.width, source.height)
                            draw(Canvas(source))
                        }
                        // The stock art is flattened over a blue tile. Remove its blue
                        // backing while retaining the yellow sun and softly shaded cloud.
                        val pixels = IntArray(source.width * source.height)
                        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
                        for (i in pixels.indices) {
                            val p = pixels[i]
                            val blueKey = (Color.blue(p) - Color.red(p) - 32).coerceIn(0, 100)
                            pixels[i] = (p and 0x00ffffff) or ((Color.alpha(p) * (100 - blueKey) / 100) shl 24)
                        }
                        AppearanceWeather(source, Bitmap.createBitmap(pixels, source.width, source.height,
                            Bitmap.Config.ARGB_8888))
                    } else {
                        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(name)
                        val activity = manager.resolveActivity(launch, 0)?.activityInfo
                        val resourceId = activity?.iconResource?.takeIf { it != 0 } ?: app.icon
                        PreviewIcons.fromDrawable(resources.getDrawable(resourceId, null))?.let {
                            AppearanceWeather(it.source, it.layers.glyphBitmap)
                        }
                    }
                }.getOrNull()
                if (icon != null) return icon
            }
            return null
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
        private set
    var displayedPosition = 0f
        private set
    var onPositionDrawn: (() -> Unit)? = null
    private var motion: ValueAnimator? = null

    fun moveTo(value: Int, animate: Boolean = false) {
        if (position == value && (animate || displayedPosition == value.toFloat())) return
        position = value
        motion?.cancel()
        fun show(progress: Float) {
            displayedPosition = progress
            invalidate()
            onPositionDrawn?.invoke()
        }
        if (!animate || !isAttachedToWindow || !ValueAnimator.areAnimatorsEnabled()) {
            show(value.toFloat())
            return
        }
        motion = ValueAnimator.ofFloat(displayedPosition, value.toFloat()).apply {
            duration = 190L
            interpolator = PathInterpolator(.2f, .65f, .3f, 1f)
            addUpdateListener { show(it.animatedValue as Float) }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        motion?.cancel()
        displayedPosition = position.toFloat()
        super.onDetachedFromWindow()
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
        val left = minOf(nativeEnd, inputStart).coerceAtLeast(inset)
        val right = maxOf(nativeEnd, inputStart).coerceAtMost(width - inset)
        val useNative = right - left > width * .55f
        start = if (useNative) left else inset
        end = if (useNative) right else width - inset
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
        val fraction = displayedPosition / range
        val x = start + (end - start) * if (layoutDirection == LAYOUT_DIRECTION_RTL) 1f - fraction else fraction
        paint.color = 0x59000000
        canvas.drawCircle(x, y + density, 11f * density, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(x, y, 10.5f * density, paint)
        cursorHsv[0] = displayedPosition % 360f
        paint.color = handle ?: Color.HSVToColor(cursorHsv)
        canvas.drawCircle(x, y, radius, paint)
    }
}
