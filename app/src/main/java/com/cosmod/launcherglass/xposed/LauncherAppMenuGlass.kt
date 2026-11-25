package com.cosmod.launcherglass.xposed

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.widget.PopupWindow
import android.view.ViewGroup
import com.cosmod.launcherglass.ui.FeatureSections
import com.cosmod.launcherglass.ui.GlassEngineSettings
import com.cosmod.launcherglass.ui.OpticalSettings
import com.cosmod.launcherglass.util.GlassLogger
import com.cosmod.launcherglass.util.WallpaperHelper
import de.robv.android.xposed.XC_MethodHook
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Materials for the stock COUI app shortcut menu and its submenu. */
internal object LauncherAppMenuGlass {
    private const val TAG = "LauncherAppMenuGlass"
    private const val CLEAR_BACKGROUND_SCOPE = "cpiClearAppMenuBackground"
    private const val BOUND_FRAME = "cpiAppMenuFrame"

    private data class Material(val style: GlassStyle, val backgroundBlur: Boolean)
    private data class Frame(val bitmap: Bitmap, val area: Rect)

    @Volatile private var material: Material? = null
    private val main = Handler(Looper.getMainLooper())
    private val captures = Executors.newSingleThreadExecutor { Thread(it, "CPI app menu backdrop") }
    private val menus = WeakHashMap<View, Menu>()
    private val bindings = WeakHashMap<View, Menu>()
    private val clearBackground = ThreadLocal<Boolean>()

    fun update(settings: Bundle, moduleEnabled: Boolean) {
        material = if (moduleEnabled && settings.getBoolean(FeatureSections.key("APP_MENU"))) {
            fun number(key: String) = settings.getInt(key, OpticalSettings.integers.getValue(key))
                .coerceIn(0, OpticalSettings.maximum(key))
            val scope = GlassEngineSettings.APP_MENU
            Material(
                GlassStyle(
                    liquid = number("app_menu_glass_style") == 1,
                    width = number("app_menu_glass_width"),
                    distortion = number("app_menu_glass_distortion"),
                    radius = number("app_menu_glass_blur"),
                    opacity = number("app_menu_glass_opacity"),
                    filter = null,
                    extendedLens = true,
                    dispersion = number("app_menu_glass_dispersion"),
                    newEngine = settings.getBoolean(scope) && !settings.getBoolean(GlassEngineSettings.softKey(scope)),
                    engineOptics = GlassEngineSettings.optics(settings, scope),
                ),
                backgroundBlur = settings.getBoolean("app_menu_background_blur", true),
            )
        } else null
    }

    fun install(loader: ClassLoader) {
        if (Build.VERSION.SDK_INT < 33) return
        runCatching {
            val container = loader.loadClass("com.android.launcher3.popup.view.COUIPopupListWindowContainer")
            val base = checkNotNull(container.superclass)
            val show = container.getDeclaredMethod("reorderAndShow")
            val getWindow = base.getDeclaredMethod("getMPopupListWindow").apply { isAccessible = true }
            val getLauncher = container.getDeclaredMethod("getMLauncher")
            check(PopupWindow::class.java.isAssignableFrom(getWindow.returnType))
            check(Activity::class.java.isAssignableFrom(getLauncher.returnType))
            val frameType = loader.loadClass("com.coui.appcompat.poplist.RoundFrameLayout")
            val radius = frameType.getDeclaredMethod("getSmoothRadiusAndWeight")
            val blurBuilder = frameType.getDeclaredField("w").type
            val builderTarget = blurBuilder.getDeclaredField("b").apply { isAccessible = true }
            check(builderTarget.type == View::class.java)
            val applyBlur = blurBuilder.getDeclaredMethod("a")
            val refreshBlur = blurBuilder.getDeclaredMethod("e", String::class.java)
            check(applyBlur.returnType == Void.TYPE && refreshBlur.returnType == Void.TYPE)
            val dragLayer = loader.loadClass("com.android.launcher3.OplusDragLayer")
            val blurFactory = loader.loadClass("com.android.launcher3.popup.PopupBlurView\$Companion")
                .getDeclaredMethod("getPopBlurView", Context::class.java, dragLayer)

            ModuleHooks.hookMethod(show, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val settings = material ?: return
                    param.setObjectExtra(CLEAR_BACKGROUND_SCOPE, clearBackground.get())
                    clearBackground.set(!settings.backgroundBlur)
                    val popup = getWindow.invoke(param.thisObject) as? PopupWindow ?: return
                    val root = popup.contentView ?: return
                    val launcher = getLauncher.invoke(param.thisObject) as? Activity ?: return
                    menus[root]?.release(root)
                    val frames = popupFrames(root, frameType)
                    if (frames.isEmpty()) {
                        GlassLogger.limited(TAG, "menu-frames-missing") { "COUI popup has no material frames" }
                        return
                    }
                    val menu = Menu(frames) { view ->
                        (radius.invoke(view) as? FloatArray)?.firstOrNull()
                            ?.takeIf { it.isFinite() && it > 0f } ?: 24f * view.resources.displayMetrics.density
                    }
                    menus[root] = menu
                    frames.forEach { bindings[it] = menu }
                    root.addOnAttachStateChangeListener(menu)
                    menu.capture(launcher, root)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val previous = param.getObjectExtra(CLEAR_BACKGROUND_SCOPE) as? Boolean
                    if (previous == null) clearBackground.remove() else clearBackground.set(previous)
                }
            })

            ModuleHooks.hookMethod(blurFactory, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    // Keep the OEM animator, selected icon and close listeners.
                    if (clearBackground.get() == true) (param.result as? View)?.visibility = View.INVISIBLE
                }
            })

            val retainMaterial = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (bindings.isEmpty()) return
                    val view = builderTarget.get(param.thisObject) as? View ?: return
                    val menu = bindings[view] ?: return
                    if (menu.frame == null || material == null) return
                    param.setObjectExtra(BOUND_FRAME, view)
                    menu.restoreBackground(view)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.getObjectExtra(BOUND_FRAME) as? View ?: return
                    if (!param.hasThrowable()) bindings[view]?.applyBackground(view)
                }
            }
            // The native builder can replace the drawable during menu morphs or theme refreshes.
            // Give it its own drawable during both calls so it never caches the CPI wrapper.
            ModuleHooks.hookMethod(applyBlur, retainMaterial)
            ModuleHooks.hookMethod(refreshBlur, retainMaterial)
        }.onFailure {
            GlassLogger.limited(TAG, "menu-compatibility") {
                "Using stock app menu material: ${it.javaClass.simpleName}"
            }
        }
    }

    private fun popupFrames(root: View, frameType: Class<*>): List<View> = buildList {
        fun collect(view: View) {
            if (frameType.isInstance(view)) {
                add(view)
            } else if (view is ViewGroup) {
                for (index in 0 until view.childCount) collect(view.getChildAt(index))
            }
        }
        collect(root)
    }

    private class Menu(frames: List<View>, private val radius: (View) -> Float) : View.OnAttachStateChangeListener {
        private val frames = frames.map { WeakReference(it) }
        private val backgrounds = WeakHashMap<View, GlassBackground>()
        var frame: Frame? = null
            private set
        @Volatile private var released = false
        var loggedDraw = false

        fun applyBackground(view: View) {
            if (released || frame == null || material == null || !view.isAttachedToWindow) return
            val current = view.background
            val previous = backgrounds[view]
            if (current === previous && previous != null) return
            val drawable = if (previous != null && previous.original === current) previous
                else GlassBackground(view, this, current, radius(view))
            backgrounds[view] = drawable
            view.background = drawable
        }

        fun restoreBackground(view: View) {
            val drawable = backgrounds[view] ?: return
            if (view.background === drawable) view.background = drawable.original
        }

        fun capture(launcher: Activity, root: View) {
            val decor = launcher.window.decorView
            val width = decor.width
            val height = decor.height
            if (width <= 0 || height <= 0 || !decor.isAttachedToWindow) return
            val location = IntArray(2).also(decor::getLocationOnScreen)
            val area = Rect(location[0], location[1], location[0] + width, location[1] + height)
            val scale = minOf(1f, 800f / width, 1560f / height)
            val captureWidth = (width * scale).roundToInt().coerceAtLeast(1)
            val captureHeight = (height * scale).roundToInt().coerceAtLeast(1)
            val context = launcher.applicationContext
            val rootReference = WeakReference(root)
            val workspace = Bitmap.createBitmap(captureWidth, captureHeight, Bitmap.Config.ARGB_8888)
            fun submit(workspaceFrame: Bitmap?) {
                captures.execute {
                    if (released) {
                        workspaceFrame?.recycle()
                        return@execute
                    }
                    val bitmap = runCatching {
                        composeBackdrop(context, area, captureWidth, captureHeight, workspaceFrame)
                    }.onFailure { error ->
                        GlassLogger.limited(TAG, "menu-backdrop-composite") {
                            "Could not combine wallpaper and launcher window: ${error.javaClass.simpleName}"
                        }
                    }.getOrNull() ?: workspaceFrame
                    if (bitmap !== workspaceFrame) workspaceFrame?.recycle()
                    if (bitmap == null) {
                        GlassLogger.limited(TAG, "menu-backdrop-missing") { "No backdrop for the app menu" }
                        return@execute
                    }
                    bitmap.prepareToDraw()
                    main.post {
                        val target = rootReference.get()
                        if (target == null || menus[target] !== this || material == null) {
                            bitmap.recycle()
                        } else {
                            frame = Frame(bitmap, area)
                            frames.forEach { reference -> reference.get()?.let(::applyBackground) }
                            target.invalidate()
                        }
                    }
                }
            }
            // The menu has its own window. Only copy the launcher's window, before
            // ColorOS adds the full-screen blur view to it.
            @Suppress("DEPRECATION")
            try {
                PixelCopy.request(launcher.window, workspace, { result ->
                    if (result == PixelCopy.SUCCESS) submit(workspace)
                    else {
                        workspace.recycle()
                        submit(null)
                    }
                }, main)
            } catch (_: IllegalArgumentException) {
                workspace.recycle()
                submit(null)
            }
        }

        override fun onViewAttachedToWindow(view: View) {
            // The root attaches before its children and their native blur builders.
            view.post { frames.forEach { reference -> reference.get()?.let(::applyBackground) } }
        }

        override fun onViewDetachedFromWindow(view: View) = release(view)

        fun release(root: View) {
            released = true
            if (menus[root] === this) menus.remove(root)
            root.removeOnAttachStateChangeListener(this)
            frames.forEach { reference ->
                val view = reference.get() ?: return@forEach
                restoreBackground(view)
                if (bindings[view] === this) bindings.remove(view)
                AlertGlassLens.forget(view)
            }
            backgrounds.clear()
            frame = null
        }
    }

    private fun composeBackdrop(context: Context, area: Rect, width: Int, height: Int, workspace: Bitmap?): Bitmap? {
        val snapshot = WallpaperHelper.readSystemSnapshot(context, maxSide = 1560)
            ?: return workspace
        var wallpaper = snapshot
        try {
            if (snapshot.config == Bitmap.Config.HARDWARE) {
                wallpaper = snapshot.copy(Bitmap.Config.ARGB_8888, false) ?: return workspace
            }
            val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            val dest = RectF(0f, 0f, width.toFloat(), height.toFloat())
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            val source = WallpaperFootprint.source(wallpaper, area.right, area.bottom, RectF(area))
            val sx = width / source.width()
            val sy = height / source.height()
            paint.shader = GlassGeometry.wallpaperShader(wallpaper).apply {
                setLocalMatrix(Matrix().apply {
                    setScale(sx, sy)
                    postTranslate(-source.left * sx, -source.top * sy)
                })
            }
            canvas.drawRect(dest, paint)
            paint.shader = null
            workspace?.let { canvas.drawBitmap(it, null, dest, paint) }
            return result
        } finally {
            if (wallpaper !== snapshot) wallpaper.recycle()
            snapshot.recycle()
        }
    }

    private class GlassBackground(
        view: View,
        private val menu: Menu,
        val original: Drawable?,
        private val radius: Float,
    ) : Drawable() {
        private val owner = WeakReference(view)
        private val rect = RectF()
        private val path = Path()
        private val tint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var opacity = 255

        override fun draw(canvas: Canvas) {
            val view = owner.get()
            val frame = menu.frame
            val style = material?.style
            if (Build.VERSION.SDK_INT >= 33 && view != null && frame != null && style != null &&
                canvas.isHardwareAccelerated && !bounds.isEmpty) {
                rect.set(bounds)
                val corner = radius.coerceAtMost(minOf(rect.width(), rect.height()) * .5f)
                val drawn = runCatching {
                    AlertGlassLens.renderMenu(canvas, view, frame.bitmap, frame.area, rect, corner, opacity, style)
                }.onFailure { error ->
                    GlassLogger.limited(TAG, "menu-render-error") {
                        "App menu glass render failed: ${error.javaClass.simpleName}: ${error.message}"
                    }
                }.getOrDefault(false)
                if (drawn) {
                    if (!menu.loggedDraw) {
                        menu.loggedDraw = true
                        GlassLogger.limited(TAG, "menu-rendered", 25) {
                            val engine = when {
                                !style.liquid -> "Frosted"
                                style.newEngine -> "New Engine"
                                style.engineOptics.softWidth != null -> "Soft Light"
                                else -> "Liquid Glass"
                            }
                            "Rendered $engine with ${style.radius} dp blur"
                        }
                    }
                    if (style.opacity > 0) {
                        val dark = view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                            Configuration.UI_MODE_NIGHT_YES
                        val tone = if (dark) 18 else 252
                        tint.color = Color.argb((style.opacity * opacity * .01f).roundToInt(), tone, tone, tone)
                        path.rewind()
                        path.addRoundRect(rect, corner, corner, Path.Direction.CW)
                        canvas.drawPath(path, tint)
                    }
                    return
                }
            }
            original?.let {
                it.bounds = bounds
                it.draw(canvas)
            }
        }

        override fun setAlpha(alpha: Int) {
            opacity = alpha
            invalidateSelf()
        }
        override fun getAlpha() = opacity
        override fun setColorFilter(colorFilter: ColorFilter?) {
            original?.colorFilter = colorFilter
            invalidateSelf()
        }
        @Suppress("DEPRECATION")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
        override fun getOutline(outline: Outline) {
            if (original != null) {
                original.bounds = bounds
                original.getOutline(outline)
            } else {
                outline.setRoundRect(bounds, radius)
            }
        }
        override fun getPadding(padding: Rect) = original?.getPadding(padding) ?: false
    }
}
