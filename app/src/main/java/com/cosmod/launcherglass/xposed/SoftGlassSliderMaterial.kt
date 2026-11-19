package com.cosmod.launcherglass.xposed

import android.content.Context
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.drawable.Drawable
import android.view.View
import androidx.annotation.RequiresApi
import com.cosmod.launcherglass.ui.SoftGlassSettings.Parameter
import com.cosmod.launcherglass.ui.SoftGlassSettings.Profile
import com.cosmod.launcherglass.util.GlassLogger

/** Shares one live backdrop for tracks that the OEM draws through its older blur wrapper. */
@RequiresApi(33)
internal class SoftGlassSliderMaterial(context: Context) {
    private val screen = RectF()
    private val local = RectF()
    private val matrix = Matrix()
    private val location = IntArray(2)
    private val origin = FloatArray(2)
    private val shape = FloatArray(8)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val visible: (View) -> Boolean = { it.isShown && it.windowVisibility == View.VISIBLE }
    private val backdrop = ShadeBackdrop(context, View::postInvalidateOnAnimation) { error ->
        unavailable = true
        clear()
        GlassLogger.e("SoftGlass", "Slider capture unavailable; native tracks retained", error)
    }
    private var unavailable = false
    private var shader: RuntimeShader? = null
    private val drawn = mutableSetOf<Float>()

    fun clear() { backdrop.clear() }

    fun draw(canvas: Canvas, native: Drawable, view: View, profile: Profile,
        role: Float, radius: Float, dark: Boolean, lightX: Float, lightY: Float) {
        if (unavailable || !canvas.isHardwareAccelerated || !view.isAttachedToWindow || !visible(view) ||
            native.bounds.isEmpty || native.alpha <= 0) return
        local.set(native.bounds)
        matrix.reset()
        view.transformMatrixToGlobal(matrix)
        screen.set(local)
        matrix.mapRect(screen)
        origin.fill(0f)
        matrix.mapPoints(origin)
        view.getLocationOnScreen(location)
        screen.offset(location[0] - origin[0], location[1] - origin[1])
        if (screen.isEmpty) return
        val metrics = view.resources.displayMetrics
        val frame = backdrop.frameFor(view, screen, (112 * metrics.density).toInt(), visible) ?: return
        if (frame.image.isRecycled || screen.top < frame.area.top || screen.bottom > frame.area.bottom) return
        val w = local.width()
        val h = local.height()
        val corner = (if (radius > 0f) radius else minOf(w,h) * .5f).coerceAtMost(minOf(w,h) * .5f)
        matrix.setScale(frame.area.width() * w / (frame.image.width * screen.width()),
            frame.area.height() * h / (frame.image.height * screen.height()))
        matrix.postTranslate((frame.area.left - screen.left) * w / screen.width(),
            (frame.area.top - screen.top) * h / screen.height())
        val input: BitmapShader = GlassGeometry.wallpaperShader(frame.image)
        input.setLocalMatrix(matrix)
        val program = shader ?: RuntimeShader(SoftGlassShader.activitySource()).also { shader = it }
        SoftGlassShader.applyProfile(program, profile, role, dark, metrics.density,
            metrics.widthPixels.toFloat(), metrics.heightPixels.toFloat(), lightX, lightY, fullMix = true)
        program.setInputShader(SoftGlassShader.INPUT, input)
        shape[2] = w * .5f
        shape[3] = h * .5f
        shape[4] = shape[2]
        shape[5] = shape[3]
        shape[6] = corner
        program.setFloatUniform("u_size", w, h)
        program.setFloatUniform("u_commonArray", shape)
        val save = canvas.save()
        try {
            canvas.translate(local.left, local.top)
            paint.shader = program
            paint.alpha = (native.alpha * profile[Parameter.STRENGTH]).toInt()
            canvas.drawRoundRect(0f, 0f, w, h, corner, corner, paint)
        } finally {
            paint.shader = null
            canvas.restoreToCount(save)
        }
        if (drawn.add(role)) GlassLogger.i("SoftGlass", "Legacy slider material applied: role=$role")
    }
}
