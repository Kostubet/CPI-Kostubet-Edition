package com.cosmod.launcherglass.xposed

import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.annotation.RequiresApi
import com.cosmod.launcherglass.ui.FeatureSections
import com.cosmod.launcherglass.ui.GlassEngineSettings
import com.cosmod.launcherglass.ui.OpticalSettings
import com.cosmod.launcherglass.util.GlassLogger
import com.cosmod.launcherglass.util.WallpaperHelper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap

/** Category glass samples a shared texture and composites through the stock card silhouette. */
@RequiresApi(33)
internal object DrawerGlass {
    private const val TAG = "DrawerGlass"
    private val main = Handler(Looper.getMainLooper())
    private val cards = WeakHashMap<View, Card>()
    private var contract: CategoryContract? = null
    private var reportedCategory = false
    @Volatile private var categoryStyle: GlassStyle? = null
    val categoriesEnabled get() = categoryStyle != null
    private val refreshAfterScroll = Runnable { invalidateCards() }

    private class Card {
        val bounds = RectF()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val backdropPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val location = IntArray(2)
        val crop = RectF()
        val sampleMatrix = Matrix()
        var mask: Shader? = null
        var lens: LiquidGlassShader? = null
        var image: Bitmap? = null
        var imageShader: Shader? = null
        var composite: Shader? = null
        var corner = -1f
        var stockSuppressed = false
        var pending = false
    }

    private class CategoryContract(
        val updateStock: Method,
        val stockEnabled: Method,
        val circle: Field,
        val tablet: Field,
        val iconWidth: Field,
        val startPadding: Field,
        val columns: Method,
        val padding: Method,
        val radius: Method,
        val smoothDraw: Method?,
    )

    fun update(settings: Bundle, enabled: Boolean) {
        val next = if (enabled && settings.getBoolean(FeatureSections.key("DRAWER_FOLDERS"))) {
            fun number(key: String) = settings.getInt(key, OpticalSettings.integers.getValue(key))
                .coerceIn(0, OpticalSettings.maximum(key))
            val scope = GlassEngineSettings.DRAWER_FOLDERS
            GlassStyle(
                liquid = number("drawer_folders_glass_style") == 1,
                width = number("drawer_folders_glass_width"),
                distortion = number("drawer_folders_glass_distortion"),
                radius = number("drawer_folders_glass_blur"),
                opacity = number("drawer_folders_glass_opacity"),
                filter = null,
                extendedLens = true,
                surfaceScope = GlassSurface.DRAWER_FOLDERS,
                dispersion = number("drawer_folders_glass_dispersion"),
                newEngine = settings.getBoolean(scope) && !settings.getBoolean(GlassEngineSettings.softKey(scope)),
                engineOptics = GlassEngineSettings.optics(settings, scope),
            )
        } else null
        if (categoryStyle == next) return
        categoryStyle = next
        main.post {
            if (categoryStyle == null) WallpaperHelper.clearDrawerBackdrop()
            val access = contract ?: return@post
            for ((view, card) in cards) {
                if (categoryStyle == null) restoreStock(view, card, access)
                if (view.isAttachedToWindow) view.invalidate()
            }
            if (categoryStyle == null) cards.clear()
        }
    }

    fun invalidateCards() {
        if (!categoriesEnabled) return
        for (view in cards.keys) {
            if (view.isAttachedToWindow && view.isShown) view.invalidate()
        }
    }

    fun install(loader: ClassLoader) {
        if (Build.VERSION.SDK_INT < 33) return
        runCatching {
            val category = loader.loadClass("com.android.launcher3.allapps.OplusCategoryIconContainer")
            val dimensions = loader.loadClass("com.android.launcher3.allapps.CategoryIconDimenManager")
            val material = loader.loadClass("com.android.launcher3.util.LauncherMaterialEffectUtils")
            fun field(name: String) = category.getDeclaredField(name).apply { isAccessible = true }
            val access = CategoryContract(
                category.getDeclaredMethod("updateBlurBackground", Boolean::class.javaPrimitiveType),
                material.getDeclaredMethod("isBlurEnabled"),
                field("mNeedCircleBg"),
                field("mIsSupportNewTabletLayout"),
                field("mIconWidth"),
                field("mStartPadding"),
                category.getDeclaredMethod("getColumn").apply { isAccessible = true },
                dimensions.getDeclaredMethod("getCategoryFolderPaddingStart", Resources::class.java),
                dimensions.getDeclaredMethod("getCategoryBgRadius", Resources::class.java),
                runCatching {
                    loader.loadClass("com.android.launcher3.util.SmoothRoundedManager").getDeclaredMethod(
                        "createSmoothCanvas", Canvas::class.java, RectF::class.java,
                        Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Paint::class.java,
                        Float::class.javaPrimitiveType,
                    )
                }.getOrNull(),
            )
            val draw = category.getDeclaredMethod("onDraw", Canvas::class.java)
            XposedBridge.hookMethod(access.updateStock, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (categoryStyle != null && param.args[0] == true && cards[param.thisObject]?.stockSuppressed == true) {
                        param.result = null
                    }
                }
            })
            XposedBridge.hookMethod(draw, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val style = categoryStyle ?: return
                    val canvas = param.args[0] as Canvas
                    if (!canvas.isHardwareAccelerated) return
                    val view = param.thisObject as View
                    if (runCatching { drawCategory(canvas, view, access, style) }.onFailure {
                        cards[view]?.let { restoreStock(view, it, access) }
                        GlassLogger.limited(TAG, "category-draw") { "Stock category restored: ${it.message}" }
                    }.getOrDefault(false)) param.result = null
                }
            })
            // Scroll movement reuses children; refresh their crops once the
            // list settles instead of rebuilding shaders on every scroll frame.
            val list = runCatching {
                loader.loadClass("androidx.recyclerview.widget.RecyclerView")
                    .getDeclaredMethod("dispatchOnScrolled", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            }.getOrNull()
            if (list != null) XposedBridge.hookMethod(list, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!categoriesEnabled || !IconGlassHook.isDrawerIcon(param.thisObject as View)) return
                    main.removeCallbacks(refreshAfterScroll)
                    main.postDelayed(refreshAfterScroll, 90L)
                }
            })
            contract = access
            GlassLogger.i(TAG, "Category background contract loaded")
        }.onFailure {
            GlassLogger.limited(TAG, "category-contract") { "Stock category kept: ${it.message}" }
        }
    }

    private fun drawCategory(canvas: Canvas, view: View, access: CategoryContract, style: GlassStyle): Boolean {
        if (view.width <= 0 || view.height <= 0 || !view.isAttachedToWindow) return false
        val card = cards.getOrPut(view, ::Card)
        val circle = access.circle.getBoolean(view)
        val tablet = !circle && access.tablet.getBoolean(view)
        val inset = if (tablet) {
            val columns = (access.columns.invoke(view) as Number).toInt()
            val pad = (view.width - access.iconWidth.getInt(view) * columns - view.paddingStart * 2) / 2
            access.startPadding.setInt(view, pad)
            pad.toFloat()
        } else {
            (access.padding.invoke(null, view.resources) as Number).toInt() / 2f
        }
        val width = (view.width - 2 * inset).toInt()
        val height = view.height
        if (width <= 0 || canvas.quickReject(inset, 0f, inset + width, height.toFloat())) return false
        val metrics = view.resources.displayMetrics
        val blur = style.engineOptics.blur
        val image = WallpaperHelper.getDrawerBackdrop(view.context, style.radius * metrics.density, blur.opacity)
        if (image == null) {
            restoreStock(view, card, access)
            return false
        }
        val edge = minOf(width, height)
        val corner = if (circle) edge / 2f else
            (access.radius.invoke(null, view.resources) as Number).toFloat().coerceIn(0f, edge / 2f)
        val bounds = card.bounds
        if (card.mask == null || bounds.width() != width.toFloat() || bounds.height() != height.toFloat() ||
            card.corner != corner) {
            bounds.set(0f, 0f, width.toFloat(), height.toFloat())
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
            card.paint.color = Color.WHITE
            val maskCanvas = Canvas(bitmap)
            if (circle || access.smoothDraw == null) maskCanvas.drawRoundRect(bounds, corner, corner, card.paint)
            else access.smoothDraw.invoke(null, maskCanvas, bounds, corner, corner, card.paint, 3f)
            card.mask = GlassGeometry.wallpaperShader(bitmap)
            card.composite = null
            card.corner = corner
        }
        val mask = card.mask ?: return false
        view.getLocationOnScreen(card.location)
        val left = card.location[0] + inset
        val top = card.location[1].toFloat()
        card.crop.set(left, top, left + width, top + height)
        WallpaperFootprint.source(image, metrics.widthPixels, metrics.heightPixels, card.crop, card.crop)
        val lens = if (style.liquid && PrefsHelper.gpuEffects) {
            (card.lens ?: LiquidGlassShader.create().also { card.lens = it })
                ?.update(image, mask, card.crop, width, height, style.backdropFilter, corner / edge, style)
        } else null
        val paint = card.backdropPaint
        if (lens == null) {
            if (card.image !== image) {
                card.image = image
                card.imageShader = GlassGeometry.wallpaperShader(image)
                card.composite = null
            }
            val source = checkNotNull(card.imageShader)
            card.sampleMatrix.setRectToRect(card.crop, bounds, Matrix.ScaleToFit.FILL)
            source.setLocalMatrix(card.sampleMatrix)
            paint.shader = card.composite ?: ComposeShader(source, mask, PorterDuff.Mode.DST_IN).also { card.composite = it }
            paint.colorFilter = style.backdropFilter
        } else {
            paint.shader = lens
            paint.colorFilter = null
        }
        // The stock controller owns a drawable set outside onDraw. Clear it on
        // the UI queue once CPI has a usable layer, then re-record the plate.
        if (!card.stockSuppressed && !card.pending) {
            card.pending = true
            view.post {
                card.pending = false
                if (categoryStyle != null && view.isAttachedToWindow) {
                    runCatching { XposedBridge.invokeOriginalMethod(access.updateStock, view, arrayOf(false)) }
                        .onSuccess { card.stockSuppressed = true; view.invalidate() }
                }
            }
        }
        if (!card.stockSuppressed) return false
        if (!reportedCategory) {
            reportedCategory = true
            GlassLogger.i(TAG, "Masked category card bound to shared backdrop")
        }
        val saved = canvas.save()
        try {
            canvas.translate(inset, 0f)
            // Keep the real drawer backdrop visible through the optical sample.
            paint.alpha = 185
            canvas.drawRect(bounds, paint)
            if (style.opacity > 0) {
                val night = view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                    Configuration.UI_MODE_NIGHT_YES
                card.paint.shader = mask
                card.paint.colorFilter = if (night) DARK_TINT else LIGHT_TINT
                card.paint.alpha = 255 * style.opacity / 100
                canvas.drawRect(bounds, card.paint)
                card.paint.shader = null
                card.paint.colorFilter = null
            }
        } finally {
            canvas.restoreToCount(saved)
        }
        return true
    }

    private fun restoreStock(view: View, card: Card, access: CategoryContract) {
        if (!card.stockSuppressed) return
        card.stockSuppressed = false
        if (!view.isAttachedToWindow) return
        runCatching {
            XposedBridge.invokeOriginalMethod(access.updateStock, view, arrayOf(access.stockEnabled.invoke(null) == true))
        }.onFailure {
            GlassLogger.limited(TAG, "category-restore") { "Category blur restore: ${it.message}" }
        }
    }

    private val DARK_TINT = PorterDuffColorFilter(0xff141414.toInt(), PorterDuff.Mode.SRC_IN)
    private val LIGHT_TINT = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
}
