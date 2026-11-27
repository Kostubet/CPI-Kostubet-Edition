package com.cosmod.launcherglass.xposed

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.annotation.RequiresApi
import com.cosmod.launcherglass.ui.FeatureSections
import com.cosmod.launcherglass.ui.GlassEngineSettings
import com.cosmod.launcherglass.ui.OpticalSettings
import com.cosmod.launcherglass.util.GlassBlur
import com.cosmod.launcherglass.util.GlassLogger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap

/**
 * Compositor backdrops for the drawer. The backdrop at each draw point contains
 * the launcher scrim and any category plate already drawn below the icon.
 */
@RequiresApi(33)
internal object DrawerGlass {
    private const val TAG = "DrawerGlass"
    private val setter by lazy { resolveSetter() }
    private val main = Handler(Looper.getMainLooper())
    val backdropAvailable get() = setter != null
    private val cards = WeakHashMap<View, Card>()
    private val icons = WeakHashMap<View, WeakHashMap<Drawable, Plate>>()
    private var contract: CategoryContract? = null
    private var reportedCategory = false
    private var reportedIcon = false
    @Volatile private var categoryStyle: GlassStyle? = null

    @SuppressLint("SoonBlockedPrivateApi")
    private fun resolveSetter() = runCatching {
        RenderNode::class.java.getDeclaredMethod("setBackdropRenderEffect", RenderEffect::class.java)
            .apply { isAccessible = true }
    }.getOrNull()

    private class Plate {
        val node = RenderNode("CPI.drawerBackdrop")
        var style: GlassStyle? = null
        var mask: Shader? = null
        var rounding = 0f
        var blurRadius = 0f
        var failed = false

        fun prepare(width: Int, height: Int, mask: Shader, rounding: Float, radius: Float, style: GlassStyle): Boolean {
            if (failed) return false
            val apply = setter ?: return false
            if (this.style == style && this.mask === mask && this.rounding == rounding && blurRadius == radius &&
                node.width == width && node.height == height && node.hasDisplayList()) return true

            return runCatching {
                val shader = LiquidGlassShader.lifted()?.updateLifted(
                    mask, width, height, 0f, width, height, style.backdropFilter, rounding, style,
                ) ?: return false
                val lens = RenderEffect.createRuntimeShaderEffect(shader, "backdrop")
                val blur = GlassBlur.effect(radius, style.engineOptics.blur.opacity)
                val effect = if (blur == null) lens else RenderEffect.createChainEffect(lens, blur)
                node.setPosition(0, 0, width, height)
                if (!node.hasDisplayList()) {
                    val recording = node.beginRecording(width, height)
                    try {
                        recording.drawColor(Color.TRANSPARENT)
                    } finally {
                        node.endRecording()
                    }
                }
                if (apply.invoke(node, effect) == false) {
                    failed = true
                    return false
                }
                this.style = style
                this.mask = mask
                this.rounding = rounding
                blurRadius = radius
                true
            }.onFailure { error ->
                failed = true
                GlassLogger.limited(TAG, "native-backdrop") {
                    "Native drawer backdrop unavailable: ${error.javaClass.simpleName}: ${error.message}"
                }
            }.getOrDefault(false)
        }
    }

    private class Card {
        val plate = Plate()
        val bounds = RectF()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var mask: Shader? = null
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
                surfaceScope = GlassSurface.DRAWER_FOLDERS,
                dispersion = number("drawer_folders_glass_dispersion"),
                newEngine = settings.getBoolean(scope) && !settings.getBoolean(GlassEngineSettings.softKey(scope)),
                engineOptics = GlassEngineSettings.optics(settings, scope),
            )
        } else null
        if (categoryStyle == next) return
        categoryStyle = next
        main.post {
            val access = contract ?: return@post
            for ((view, card) in cards) {
                if (categoryStyle == null) restoreStock(view, card, access)
                if (view.isAttachedToWindow) view.invalidate()
            }
        }
    }

    fun install(loader: ClassLoader) {
        if (Build.VERSION.SDK_INT < 33 || setter == null) return
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
            card.corner = corner
        }
        val mask = card.mask ?: return false
        val density = view.resources.displayMetrics.density
        if (!card.plate.prepare(width, height, mask, corner / edge, style.radius * density, style)) {
            restoreStock(view, card, access)
            return false
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
            GlassLogger.i(TAG, "Compositor category card backdrop bound")
        }
        val saved = canvas.save()
        try {
            canvas.translate(inset, 0f)
            canvas.drawRenderNode(card.plate.node)
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

    fun iconReady(view: View): Boolean =
        icons[view]?.values?.any { !it.failed && it.node.hasDisplayList() } == true

    fun drawIcon(
        canvas: Canvas, binding: IconMaterialRenderer.Binding, width: Int, height: Int,
        mask: Shader, rounding: Float, style: GlassStyle,
    ): Boolean {
        if (setter == null || !binding.view.isAttachedToWindow || Looper.myLooper() != Looper.getMainLooper()) return false
        val plate = icons.getOrPut(binding.view) { WeakHashMap() }.getOrPut(binding.drawable, ::Plate)
        if (!plate.prepare(width, height, mask, rounding, style.radius.toFloat(), style)) return false
        canvas.drawRenderNode(plate.node)
        if (!reportedIcon) {
            reportedIcon = true
            GlassLogger.i(TAG, "Compositor drawer icon backdrop bound")
        }
        return true
    }

    private val DARK_TINT = android.graphics.PorterDuffColorFilter(0xff141414.toInt(), android.graphics.PorterDuff.Mode.SRC_IN)
    private val LIGHT_TINT = android.graphics.PorterDuffColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN)
}
