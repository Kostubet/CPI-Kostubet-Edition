// Uses the optical material adapted in SoftGlassShader.kt.
package com.cosmod.launcherglass.xposed

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi
import com.cosmod.launcherglass.ui.SoftGlassSettings.Parameter
import com.cosmod.launcherglass.ui.SoftGlassSettings.Profile
import com.cosmod.launcherglass.util.GlassLogger
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** Draws only the background of expanded lock-screen activities with legacy compositor blur. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal object SoftGlassActivityMaterial {
    private class Plate {
        val optics = SoftGlassShader.Plate()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = optics.shader }
        val viewToScreen = Matrix()
        val screenToView = Matrix()
        val imageToView = Matrix()
        val location = IntArray(2)
        val origin = FloatArray(2)
    }

    private val plates = WeakHashMap<View, Plate>()
    private var unavailable = false
    private var logged = false

    fun clear() = plates.clear()

    fun draw(canvas: Canvas, view: View, profile: Profile) {
        if (unavailable || !canvas.isHardwareAccelerated || !view.isShown || view.isLayoutRequested) return
        val native = NativeAlertMaterial.backgroundBlur(view) ?: return
        val bitmap = LockscreenGlass.activityWallpaper()?.takeUnless(Bitmap::isRecycled) ?: return
        val shape = AlertContour.resolve(view, native)
        val bounds = shape.bounds
        if (bounds.width() < 4f || bounds.height() < 4f || shape.path.isEmpty) return
        val opacity = NativeAlertMaterial.visibleAlpha(view).coerceIn(0f, 1f)
        if (opacity <= 0f) return
        val plate = plates[view] ?: runCatching { Plate() }.getOrElse {
            unavailable = true
            GlassLogger.limited("SoftGlass", "legacyActivityShader") { "Native activity background retained: ${it.message}" }
            return
        }.also { plates[view] = it }
        if (!SoftGlassNative.activityUniforms(plate.optics, view)) return

        val metrics = view.resources.displayMetrics
        val scale = maxOf(metrics.widthPixels.toFloat() / bitmap.width, metrics.heightPixels.toFloat() / bitmap.height)
        plate.viewToScreen.reset()
        view.transformMatrixToGlobal(plate.viewToScreen)
        plate.origin.fill(0f)
        plate.viewToScreen.mapPoints(plate.origin)
        view.getLocationOnScreen(plate.location)
        // transformMatrixToGlobal is relative to the root's window origin.
        plate.viewToScreen.postTranslate(
            plate.location[0] - plate.origin[0].roundToInt().toFloat(),
            plate.location[1] - plate.origin[1].roundToInt().toFloat()
        )
        if (!plate.viewToScreen.invert(plate.screenToView)) return
        plate.imageToView.setScale(scale, scale)
        plate.imageToView.postTranslate(
            (metrics.widthPixels - bitmap.width * scale) * .5f,
            (metrics.heightPixels - bitmap.height * scale) * .5f
        )
        plate.imageToView.postConcat(plate.screenToView)
        plate.imageToView.postTranslate(-bounds.left, -bounds.top)
        plate.optics.bindImage(bitmap, plate.imageToView)
        plate.optics.setShape(bounds.width(), bounds.height(),
            shape.radius.coerceIn(0f, minOf(bounds.width(), bounds.height()) * .5f))
        plate.paint.alpha = (opacity * profile[Parameter.STRENGTH] * 255f).roundToInt()
        val saved = canvas.save()
        try {
            canvas.clipPath(shape.path)
            canvas.translate(bounds.left, bounds.top)
            canvas.drawRect(0f, 0f, bounds.width(), bounds.height(), plate.paint)
        } finally {
            canvas.restoreToCount(saved)
        }
        if (!logged) {
            logged = true
            GlassLogger.i("SoftGlass", "Expanded lock-screen activity uses wallpaper-backed material")
        }
    }
}
