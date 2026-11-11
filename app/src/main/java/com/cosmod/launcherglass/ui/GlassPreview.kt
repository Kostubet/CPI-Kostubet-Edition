package com.cosmod.launcherglass.ui

import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi
import androidx.core.graphics.ColorUtils
import com.cosmod.launcherglass.util.BackdropVibrancy
import com.cosmod.launcherglass.util.FastBlur
import com.cosmod.launcherglass.util.VolumeRim
import com.cosmod.launcherglass.xposed.GlassStyle
import com.cosmod.launcherglass.xposed.LiquidGlassShader

/** A small sample scene rendered with the same lens shader as the launcher. */
internal class GlassPreview(context: Context, private val dark: Boolean) : View(context) {
    data class Material(val enabled: Boolean, val liquid: Boolean, val blur: Int, val opacity: Int,
        val width: Int, val distortion: Int, val dispersion: Int, val corner: Int,
        val saturation: Int = 100, val vibrancy: Int = 0, val brightness: Int = 50,
        val tiles: Int = 1, val vertical: Boolean = false, val rimStrength: Int = 100)

    private var material: Material? = null
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val target = RectF()
    private val volumeRim = VolumeRim()
    private val crop = RectF()
    private val source = RectF()
    private val bitmapMatrix = Matrix()
    private var icons: List<Drawable> = emptyList()
    private var wallpaper: Bitmap? = null
    private val clip = android.graphics.Path()
    private val wallpaperColor = runCatching {
        WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)?.primaryColor?.toArgb()
    }.getOrNull() ?: 0xff3e4687.toInt()
    private var backdrop: Bitmap? = null
    private var sample: Bitmap? = null
    private var sampleKey = -1
    private val optics by lazy { if (Build.VERSION.SDK_INT >= 33) Optics() else null }

    fun show(next: Material) {
        if (next == material) return
        material = next
        invalidate()
    }

    fun setScene(wallpaper: Bitmap?, icons: List<Drawable>) {
        if (this.wallpaper !== wallpaper) {
            this.wallpaper = wallpaper
            rebuildBackdrop()
        }
        this.icons = icons
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        clip.reset()
        clip.addRoundRect(0f, 0f, w.toFloat(), h.toFloat(), 18 * density, 18 * density,
            android.graphics.Path.Direction.CW)
        rebuildBackdrop()
    }

    private fun rebuildBackdrop() {
        if (width <= 0 || height <= 0) return
        val sw = width.coerceAtMost(540)
        val sh = (height * sw / width).coerceAtLeast(1)
        backdrop = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888).apply {
            val canvas = Canvas(this)
            val image = wallpaper
            if (image != null && !image.isRecycled) {
                val scale = maxOf(sw.toFloat() / image.width, sh.toFloat() / image.height)
                val insetX = (sw - image.width * scale) * .5f
                val insetY = (sh - image.height * scale) * .5f
                paint.color = Color.WHITE
                canvas.drawBitmap(image, null, RectF(insetX, insetY, sw - insetX, sh - insetY), paint)
            } else {
                canvas.drawColor(ColorUtils.blendARGB(wallpaperColor, 0xff101828.toInt(), .4f))
            }
        }
        sample = null
        sampleKey = -1
    }

    companion object {
        fun systemIcons(context: Context): List<Drawable> {
            val packages = context.packageManager
            val launchers = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            return packages.queryIntentActivities(launchers, 0).asSequence()
                .filter { it.activityInfo.applicationInfo.flags and (ApplicationInfo.FLAG_SYSTEM or
                    ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0 }
                .distinctBy { it.activityInfo.packageName }
                .sortedBy {
                    val name = it.activityInfo.packageName
                    when {
                        name == "com.android.settings" -> 0
                        "camera" in name -> 1
                        "contacts" in name || "dialer" in name -> 2
                        else -> 3
                    }
                }
                .mapNotNull { runCatching { it.loadIcon(packages).mutate() }.getOrNull() }
                .take(3)
                .toList()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val base = backdrop ?: return
        val current = material ?: return
        target.set(0f, 0f, width.toFloat(), height.toFloat())
        val saved = canvas.save()
        canvas.clipPath(clip)
        paint.color = Color.WHITE
        canvas.drawBitmap(base, null, target, paint)
        val radius = (current.blur / if (current.liquid) 5 else 3).coerceIn(0, 18)
        val key = radius * 201 + current.vibrancy
        if (sampleKey != key) {
            val vivid = BackdropVibrancy.applyCpu(base, current.vibrancy)
            sample = if (radius == 0) vivid else FastBlur.blur(vivid, radius)
            sampleKey = key
        }
        val input = sample ?: base
        val color = ColorMatrix().apply { setSaturation(current.saturation * .01f) }
        val filter = ColorMatrixColorFilter(color)
        val itemW = when {
            current.vertical -> 38 * density
            current.tiles > 1 -> minOf(52 * density, width * .21f)
            else -> width * .72f
        }
        val itemH = if (current.vertical) height * .80f else minOf(height * .60f, itemW)
        val gap = 12 * density
        val total = itemW * current.tiles + gap * (current.tiles - 1)
        val top = (height - itemH) * .5f
        for (index in 0 until current.tiles) {
            val left = (width - total) * .5f + index * (itemW + gap)
            crop.set(left, top, left + itemW, top + itemH)
            source.set(crop.left * input.width / width, crop.top * input.height / height,
                crop.right * input.width / width, crop.bottom * input.height / height)
            drawPlate(canvas, current, input, filter)
            if (current.tiles > 1 && icons.isNotEmpty()) icons[index % icons.size].let { icon ->
                val size = (itemW * .45f).toInt()
                val x = (crop.centerX() - size / 2).toInt()
                val y = (crop.centerY() - size / 2).toInt()
                icon.setBounds(x, y, x + size, y + size)
                icon.draw(canvas)
            }
        }
        canvas.restoreToCount(saved)
    }

    private fun drawPlate(canvas: Canvas, current: Material, input: Bitmap, filter: ColorMatrixColorFilter) {
        val rounding = minOf(crop.width(), crop.height()) * current.corner * .01f
        val tint = if (dark) Color.BLACK else Color.WHITE
        paint.color = Color.WHITE
        if (current.enabled) {
            val lens = if (current.liquid && Build.VERSION.SDK_INT >= 33 && canvas.isHardwareAccelerated) optics else null
            val shader = lens?.shader(input, source, crop.width().toInt(), crop.height().toInt(), rounding, filter, current)
            if (shader != null) {
                val save = canvas.save()
                canvas.translate(crop.left, crop.top)
                paint.shader = shader
                canvas.drawRect(0f, 0f, crop.width(), crop.height(), paint)
                canvas.restoreToCount(save)
            } else {
                paint.shader = BitmapShader(input, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                    bitmapMatrix.setRectToRect(source, crop, Matrix.ScaleToFit.FILL)
                    setLocalMatrix(bitmapMatrix)
                }
                paint.colorFilter = filter
                canvas.drawRoundRect(crop, rounding, rounding, paint)
            }
            paint.shader = null
            paint.colorFilter = null
            paint.color = ColorUtils.setAlphaComponent(tint, current.opacity * 255 / 100)
            canvas.drawRoundRect(crop, rounding, rounding, paint)
            val light = current.brightness - 50
            if (light != 0) {
                paint.color = ColorUtils.setAlphaComponent(if (light > 0) Color.WHITE else Color.BLACK, kotlin.math.abs(light))
                canvas.drawRoundRect(crop, rounding, rounding, paint)
            }
        } else {
            paint.color = 0x9631333d.toInt()
            canvas.drawRoundRect(crop, rounding, rounding, paint)
        }
        if (current.vertical && current.enabled) {
            volumeRim.draw(canvas, crop, rounding, (density * .55f).coerceAtLeast(1f), current.rimStrength)
            return
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density * .65f
        paint.color = 0x55ffffff
        canvas.drawRoundRect(crop, rounding, rounding, paint)
        paint.style = Paint.Style.FILL
    }

    @RequiresApi(33)
    private class Optics {
        private val lens = LiquidGlassShader.create()
        private val mask = RuntimeShader("""
            uniform float2 size;
            uniform float radius;
            half4 main(float2 p) {
                float2 q = abs(p - size * .5) - (size * .5 - radius);
                float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
                return half4(1.0 - smoothstep(-.8, .4, d));
            }
        """.trimIndent())

        fun shader(bitmap: Bitmap, source: RectF, width: Int, height: Int, radius: Float,
            filter: ColorMatrixColorFilter, material: Material): Shader? {
            if (width <= 0 || height <= 0) return null
            mask.setFloatUniform("size", width.toFloat(), height.toFloat())
            mask.setFloatUniform("radius", radius)
            val style = GlassStyle(true, material.width, material.distortion, material.blur, material.opacity,
                filter, extendedLens = true, dispersion = material.dispersion)
            return lens?.update(bitmap, mask, source, width, height, filter, radius / minOf(width, height), style)
        }
    }
}
