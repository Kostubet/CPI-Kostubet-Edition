package com.cosmod.launcherglass.xposed

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import androidx.annotation.RequiresApi
import com.cosmod.launcherglass.ui.SoftGlassSettings.Parameter
import com.cosmod.launcherglass.ui.SoftGlassSettings.Profile
import com.cosmod.launcherglass.util.GlassLogger
import java.util.WeakHashMap

/** Shares one live backdrop for tracks that the OEM draws through its older blur wrapper. */
@RequiresApi(33)
internal class SoftGlassSliderMaterial(context: Context) {
    private val screen = RectF()
    private val local = RectF()
    private val matrix = Matrix()
    private val location = IntArray(2)
    private val origin = FloatArray(2)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val visible: (View) -> Boolean = { it.isShown && it.windowVisibility == View.VISIBLE }
    private val plates = WeakHashMap<Drawable, SoftGlassShader.Plate>()
    private val backdrop = ShadeBackdrop(context, View::postInvalidateOnAnimation, {
        plates.values.forEach(SoftGlassShader.Plate::clearImage)
    }) { error ->
        unavailable = true
        clear()
        GlassLogger.e("SoftGlass", "Slider capture unavailable; native tracks retained", error)
    }
    private var unavailable = false
    private val drawn = mutableSetOf<Float>()

    fun clear() { backdrop.clear() }

    fun diagnostics() = backdrop.diagnostics()

    fun draw(canvas: Canvas, native: Drawable, view: View, profile: Profile,
        kind: SoftGlassPanelTargets.Kind, radius: Float, dark: Boolean, lightX: Float, lightY: Float) {
        val role = kind.role
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
        val frame = backdrop.frameFor(view, screen, (112 * metrics.density).toInt(), visible,
            kind == SoftGlassPanelTargets.Kind.SEEK_TRACK) ?: return
        if (frame.image.isRecycled || screen.top < frame.area.top || screen.bottom > frame.area.bottom) return
        val w = local.width()
        val h = local.height()
        val corner = (if (radius > 0f) radius else minOf(w,h) * .5f).coerceAtMost(minOf(w,h) * .5f)
        matrix.setScale(frame.area.width() * w / (frame.image.width * screen.width()),
            frame.area.height() * h / (frame.image.height * screen.height()))
        matrix.postTranslate((frame.area.left - screen.left) * w / screen.width(),
            (frame.area.top - screen.top) * h / screen.height())
        val optics = plates.getOrPut(native) { SoftGlassShader.Plate() }
        optics.apply(profile, role, dark, metrics, lightX, lightY)
        optics.bindImage(frame.image, matrix)
        optics.setShape(w, h, corner)
        val save = canvas.save()
        try {
            canvas.translate(local.left, local.top)
            paint.shader = optics.shader
            paint.alpha = (native.alpha * profile[Parameter.STRENGTH]).toInt()
            canvas.drawRoundRect(0f, 0f, w, h, corner, corner, paint)
        } finally {
            paint.shader = null
            canvas.restoreToCount(save)
        }
        if (drawn.add(role)) GlassLogger.i("SoftGlass", "Legacy slider material applied: role=$role")
    }
}
