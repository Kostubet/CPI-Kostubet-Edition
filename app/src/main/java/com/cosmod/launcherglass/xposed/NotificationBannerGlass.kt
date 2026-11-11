package com.cosmod.launcherglass.xposed

import android.animation.ValueAnimator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Display
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.LinearInterpolator
import com.cosmod.launcherglass.util.GlassLogger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/** Material and transition for the separate ColorOS heads-up window. */
@android.annotation.TargetApi(33)
internal object NotificationBannerGlass {
    private const val WINDOW = "com.oplus.systemui.notification.headsup.windowframe."
    private const val ROW = "com.android.systemui.statusbar.notification.row."
    private const val STACK = "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout"
    private var enabled = false
    private var attempted = false
    private var installed = false
    private var dedicatedMotion = false
    private var stackGate: StackGate? = null
    private var animate = true
    private var softMotion=false
    private var opacity = 42
    private var corner = 38
    private var shadowStrength = 42
    private var shadowSize = 12
    private var openingBlur = 16
    private var openSpeed = 100
    private var closeSpeed = 100
    private var plateColor = Color.TRANSPARENT
    private var plateDark=false
    private var plateFade = 65
    private var topDarkness = 0
    private var tintHeight = -1
    private var plateGradient: Shader? = null
    private var topGradient: Shader? = null
    private var rendered = 0L
    private var entrances = 0L
    private var exits = 0L
    private var lens = Bundle()
    private val drawn = WeakHashMap<View, Boolean>()
    private val motions = WeakHashMap<Any, Motion>()
    private val entrancesInFlight = WeakHashMap<View, Entrance>()
    private val shadows = WeakHashMap<View, Shadow>()
    private val entranceTimes = floatArrayOf(0f, .23f, .52f, .70f, 1f)
    private val entranceWidths = Curve(.41f, .57f, .92f, 1.025f, 1f)
    private val entranceHeights = Curve(.66f, .90f, 1.055f, .989f, 1f)
    private val entranceOffsets = Curve(-.61f, -.42f, -.08f, .010f, 0f)
    private val softWidths = Curve(.49f,.60f,.90f,1.014f,1f)
    private val softHeights = Curve(.72f,.85f,1.033f,.998f,1f)
    private val softOffsets = Curve(-.53f,-.39f,-.10f,.006f,0f)
    private val entranceAlphas = Curve(.16f, .53f, .99f, 1f, 1f)
    private val bounds = RectF()
    private val edge = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val effects = arrayOfNulls<RenderEffect>(25)
    private val exitEffects = arrayOfNulls<RenderEffect>(6)
    private var effectDensity = 0f

    private class StackGate(row: Class<*>, val stack: Class<*>) {
        private val rowType = row
        private val expanded = stack.getDeclaredField("mIsExpanded").apply { isAccessible = true }
        private val pinned = row.getMethod("isPinned")
        private val leaving = runCatching { row.getMethod("isHeadsUpAnimatingAway") }.getOrNull()

        fun matches(row: View, parent: View): Boolean =
            stack.isInstance(parent) && rowType.isInstance(row) && !expanded.getBoolean(parent) &&
                (pinned.invoke(row) == true || leaving?.invoke(row) == true)
    }

    private class Motion(row: View, val hold: Float) {
        val row = WeakReference(row)
        val matrix = Matrix()
        var scaled = false
        var blur = 0
        var restoreSpeed: (() -> Unit)? = null
    }
    private class Entrance(val animator: ValueAnimator, row: View) {
        var baseX = row.scaleX
        var baseY = row.scaleY
        var baseTranslation = row.translationY
        var baseAlpha = row.alpha
        var appliedX = baseX
        var appliedY = baseY
        var appliedTranslation = baseTranslation
        var appliedAlpha = baseAlpha
        var listener: ViewTreeObserver.OnPreDrawListener? = null
        var observer = row.viewTreeObserver
        var lastProgress = -1f
        var smoothedY = baseTranslation
        var blur = 0
    }

    private class Shadow(row: View) {
        var baseElevation = row.elevation
        val ambient = row.outlineAmbientShadowColor
        val spot = row.outlineSpotShadowColor
        var elevation = baseElevation
        var color = Color.TRANSPARENT
    }

    // Match the incoming and outgoing velocity at each keyframe. A turning
    // point has zero velocity so the small overshoot never grows on its own.
    private class Curve(private vararg val values: Float) {
        private val tangents = FloatArray(values.size)

        init {
            for (i in 1 until values.lastIndex) {
                val left = (values[i] - values[i - 1]) / (entranceTimes[i] - entranceTimes[i - 1])
                val right = (values[i + 1] - values[i]) / (entranceTimes[i + 1] - entranceTimes[i])
                if (left * right > 0f) tangents[i] = 2f * left * right / (left + right)
            }
        }

        fun at(segment: Int, t: Float, duration: Float): Float {
            if (segment == values.lastIndex - 1) {
                val mix = settle(t)
                return values[segment] + (values[segment + 1] - values[segment]) * mix
            }
            val squared = t * t
            val cubed = squared * t
            val mix = 3f * squared - 2f * cubed
            return values[segment] * (1f - mix) + values[segment + 1] * mix +
                duration * (tangents[segment] * (cubed - 2f * squared + t) +
                    tangents[segment + 1] * (cubed - squared))
        }
    }

    private fun settle(t: Float): Float = t * t * t * (t * (6f * t - 15f) + 10f)

    fun diagnostics() = "banners enabled=$enabled, installed=$installed, window=$dedicatedMotion, stack=${stackGate!=null}, rendered=$rendered, entrances=$entrances, exits=$exits"

    fun configure(active: Boolean, prefs: Bundle, loader: ClassLoader?): Boolean {
        if (active && !attempted && loader != null && Build.VERSION.SDK_INT >= 33) {
            attempted = true
            try {
                install(loader)
                installed = true
                GlassLogger.i("BannerGlass", "ColorOS heads-up hooks installed")
            } catch (error: Throwable) {
                GlassLogger.e("BannerGlass", "ColorOS banner contract unavailable", error)
            }
        }
        enabled = active && installed
        animate = prefs.getBoolean("banner_glass_motion", true)
        softMotion=prefs.getBoolean("banner_motion_soft",false)
        opacity = prefs.getInt("banner_glass_opacity", 42).coerceIn(0, 100)
        corner = prefs.getInt("banner_glass_corner", 38).coerceIn(0, 50)
        shadowStrength = prefs.getInt("banner_shadow_strength", 42).coerceIn(0, 100)
        shadowSize = prefs.getInt("banner_shadow_size", 12).coerceIn(0, 24)
        openingBlur = prefs.getInt("banner_motion_blur", 16).coerceIn(0, 24)
        openSpeed = prefs.getInt("banner_open_speed", 100).coerceIn(50, 180)
        closeSpeed = prefs.getInt("banner_close_speed", 100).coerceIn(50, 180)
        plateColor = if (prefs.getBoolean("banner_plate_enabled", false)) Color.HSVToColor(
            prefs.getInt("banner_plate_opacity", 45).coerceIn(0, 100) * 255 / 100,
            floatArrayOf(prefs.getInt("banner_plate_hue", 0).coerceIn(0, 360).toFloat(),
                prefs.getInt("banner_plate_saturation", 0).coerceIn(0, 100) / 100f,
                prefs.getInt("banner_plate_brightness", 10).coerceIn(0, 100) / 100f)
        ).let {color->
            val tone=prefs.getInt("banner_plate_lightness",0).coerceIn(-100,100)
            val rgb=androidx.core.graphics.ColorUtils.blendARGB(color or Color.BLACK,
                if(tone<0)Color.BLACK else Color.WHITE,kotlin.math.abs(tone)/100f)
            androidx.core.graphics.ColorUtils.setAlphaComponent(rgb,Color.alpha(color))
        } else Color.TRANSPARENT
        plateDark=androidx.core.graphics.ColorUtils.calculateLuminance(plateColor or 0xff000000.toInt())<.32
        plateFade = prefs.getInt("banner_plate_bottom_fade", 65).coerceIn(0, 100)
        topDarkness = prefs.getInt("banner_top_darkness", 0).coerceIn(0, 100)
        tintHeight = -1
        lens = Bundle(prefs).apply {
            putInt("island_glass_width", prefs.getInt("banner_glass_width", 42))
            putInt("island_glass_corner", corner)
            putInt("island_glass_blur", prefs.getInt("banner_glass_blur", 14))
            putInt("island_glass_distortion", prefs.getInt("banner_glass_distortion", 85))
            putInt("island_glass_mix", 100)
            putInt("island_glass_dispersion", prefs.getInt("banner_glass_dispersion", 0))
        }
        if (!enabled || !animate) {
            motions.keys.toList().forEach(::resetMotion)
            entrancesInFlight.keys.toList().forEach(::endEntrance)
        }
        if (!enabled || shadowStrength == 0 || shadowSize == 0) shadows.keys.toList().forEach(::resetShadow)
        if(!enabled)BannerTextContrast.clearAll()
        drawn.keys.toList().forEach {
            if (!enabled) AlertBackdrop.forgetBanner(it)
            it.invalidate()
        }
        return enabled
    }

    fun isBanner(background: View): Boolean {
        if (!enabled) return false
        val row = background.parent as? View ?: return false
        val parent = row.parent as? View ?: return false
        if (parent.javaClass.name == WINDOW + "HeadsUpLayout") return true
        if (dedicatedMotion && Build.VERSION.SDK_INT>=37) return false
        return try { stackGate?.matches(row,parent)==true } catch (_:ReflectiveOperationException) { false }
    }

    private fun field(owner: Class<*>, name: String): Field? =
        generateSequence(owner) { it.superclass }.firstNotNullOfOrNull { type ->
            runCatching { type.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()
        }

    private fun actualHeight(view: View, method: Method?) =
        (method?.invoke(view) as? Int) ?: view.height

    private fun install(loader: ClassLoader) {
        BannerTextContrast.install()
        val row = loader.loadClass(ROW + "ExpandableNotificationRow")
        val background = loader.loadClass(ROW + "NotificationBackgroundView")
        val normal = checkNotNull(field(row,"mBackgroundNormal"))
        val height = runCatching { background.getMethod("getActualHeight") }.getOrNull()
        stackGate = runCatching { StackGate(row,loader.loadClass(STACK)) }.getOrNull()
        val draw = background.getDeclaredMethod("onDraw", Canvas::class.java)
        val expanding = field(background,"mExpandAnimationRunning")
        val clipTop = field(background,"mClipTopAmount")
        val clipBottom = field(background,"mClipBottomAmount")
        val overlapTop = field(background,"mTopOverlap")
        val overlapBottom = field(background,"mBottomOverlap")
        val ext = field(background,"mExt")
        val click = runCatching { ext?.type?.getMethod("drawClickEffectIfNeed",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Canvas::class.java) }.getOrNull()
        XposedBridge.hookMethod(draw, object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    val view = p.thisObject as View
                    if (!isBanner(view)) {
                        if (drawn.remove(view)!=null) {
                            AlertBackdrop.forgetBanner(view)
                            (view.parent as? View)?.let {endEntrance(it);resetShadow(it);BannerTextContrast.clear(it)}
                        }
                        return
                    }
                    safely {
                        val parent = view.parent as? View ?: return@safely
                        if (normal.get(parent) !== view) return@safely
                        if (expanding?.getBoolean(view)==true) {
                            resetShadow(parent)
                            BannerTextContrast.clear(parent)
                            return@safely
                        }
                        val canvas = p.args[0] as Canvas
                        if (!canvas.isHardwareAccelerated || view.display?.displayId != Display.DEFAULT_DISPLAY) return@safely
                        val h = actualHeight(view,height).coerceAtMost(view.height)
                        val top = maxOf(clipTop?.getInt(view) ?: 0, overlapTop?.getInt(view) ?: 0)
                        val bottom = h - maxOf(clipBottom?.getInt(view) ?: 0, overlapBottom?.getInt(view) ?: 0)
                        if (view.width <= 0 || bottom <= top || !view.isShown) return@safely
                        val first = drawn.put(view,true)==null
                        val frame = AlertBackdrop.bannerFrame(view)
                        if(first && animate && ValueAnimator.areAnimatorsEnabled() &&
                            (!dedicatedMotion || (parent.parent as? View)?.javaClass?.name != WINDOW+"HeadsUpLayout")) {
                            view.post {
                                if(isBanner(view) && animate && AlertBackdrop.canSample())safely {startEntrance(parent,view,height)}
                            }
                        }
                        val fresh=frame?.takeIf {SystemClock.uptimeMillis()-it.time<=250}
                        if (fresh==null && !BannerTextContrast.has(parent)) {
                            resetShadow(parent)
                            return@safely
                        }
                        val save = canvas.save()
                        try {
                            canvas.clipRect(0, top, view.width, bottom)
                            if (drawGlass(canvas, view, parent, fresh, h)) {
                                p.result = null
                                updateShadow(parent)
                                click?.invoke(ext?.get(view), 0, 0, view.width, h, canvas)
                            }
                        } finally {
                            canvas.restoreToCount(save)
                        }
                    }
                }
        })
        dedicatedMotion = try {
            installWindowMotion(loader,row,normal,height)
            true
        } catch (error:Throwable) {
            GlassLogger.i("BannerGlass","Using compatible heads-up rendering and native dismissal")
            false
        }
    }

    private fun installWindowMotion(loader:ClassLoader,row:Class<*>,normal:Field,height:Method?) {
        val layout = loader.loadClass(WINDOW + "HeadsUpLayout")
        val container = loader.loadClass(WINDOW + "HeadsUpContainerWindow")
        val animator = loader.loadClass(WINDOW + "OplusHeadsUpAnimator")
        val callback = loader.loadClass("kotlin.jvm.functions.Function0")
        val animateTo = animator.getDeclaredMethod("animateTo", Float::class.javaPrimitiveType, callback)
        val startDrag = animator.getDeclaredMethod("onStartDrag")
        val translate = container.getDeclaredMethod("setTranslationY", Float::class.javaPrimitiveType)
        val setView = container.getDeclaredMethod("setView", View::class.java)
        val add = layout.getDeclaredMethod("onViewAdded", View::class.java)
        val remove = layout.getDeclaredMethod("onViewRemoved", View::class.java)
        val currentView = container.getDeclaredMethod("getView")
        val control = animator.getDeclaredField("control").apply { isAccessible = true }
        val holdY = animator.getDeclaredField("holdY").apply { isAccessible = true }
        val speedControl = runCatching {
            val animation = animator.getDeclaredMethod("getSpringAnimation").apply { isAccessible = true }
            val getSpring = animation.returnType.getMethod("getSpring")
            val getStiffness = getSpring.returnType.getMethod("getStiffness")
            val setStiffness = getSpring.returnType.getMethod("setStiffness", Float::class.javaPrimitiveType)
            val change: (Any, Int) -> (() -> Unit)? = change@{ owner, speed ->
                val instance = animation.invoke(owner) ?: return@change null
                val spring = getSpring.invoke(instance) ?: return@change null
                val original = (getStiffness.invoke(spring) as Number).toFloat()
                val factor = speed / 100f
                setStiffness.invoke(spring, original * factor * factor)
                val restore: () -> Unit = { setStiffness.invoke(spring, original) }
                restore
            }
            change
        }.getOrNull()
        val hooks = ArrayList<XC_MethodHook.Unhook>()
        try {
            hooks += hookBefore(animateTo) { p ->
                val window = control.get(p.thisObject) ?: return@hookBefore
                resetMotion(window)
                val view = currentView.invoke(window) as? View ?: return@hookBefore
                if (!row.isInstance(view) || !layout.isInstance(view.parent) ||
                    view.display?.displayId != Display.DEFAULT_DISPLAY) return@hookBefore
                val bg = normal.get(view) as? View ?: return@hookBefore
                AlertBackdrop.bannerFrame(bg)
                if (!animate || !ValueAnimator.areAnimatorsEnabled() || !AlertBackdrop.canSample()) return@hookBefore
                val hold = holdY.getFloat(p.thisObject)
                val target = p.args[0] as Float
                if (target > -actualHeight(bg,height).coerceAtLeast(1) * .5f) return@hookBefore
                endEntrance(view)
                val motion = Motion(view, hold)
                if (closeSpeed != 100 && speedControl != null) safely {
                    motion.restoreSpeed = speedControl(p.thisObject, closeSpeed)
                }
                motions[window] = motion
            }
            hooks += XposedBridge.hookMethod(translate, object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    val motion = motions[p.thisObject] ?: return
                    safely {
                        val view = motion.row.get()
                        if (view == null || !layout.isInstance(view.parent)) {
                            resetMotion(p.thisObject)
                            return@safely
                        }
                        val bg = normal.get(view) as? View ?: return@safely
                        val h = actualHeight(bg,height)
                        if (h > 0 && view.width > 0) updateMotion(view, bg, motion, p.args[0] as Float, h)
                    }
                }
            })
            hooks += hookBefore(startDrag) { p ->
                val window = control.get(p.thisObject) ?: return@hookBefore
                resetMotion(window)
                (currentView.invoke(window) as? View)?.let(::endEntrance)
            }
            hooks += hookBefore(setView) { p ->
                val window = p.thisObject
                val previous = currentView.invoke(window) as? View ?: return@hookBefore
                if (previous === p.args[0]) return@hookBefore
                resetMotion(window)
                endEntrance(previous)
                resetShadow(previous)
                BannerTextContrast.clear(previous)
                if (row.isInstance(previous)) (normal.get(previous) as? View)?.let(AlertBackdrop::forgetBanner)
            }
            hooks += XposedBridge.hookMethod(add, object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    if (!enabled || p.hasThrowable()) return
                    safely {
                        val view = p.args[0] as View
                        if (!row.isInstance(view)) return@safely
                        val bg = normal.get(view) as? View ?: return@safely
                        AlertBackdrop.bannerFrame(bg)
                        if (animate && ValueAnimator.areAnimatorsEnabled() && AlertBackdrop.canSample()) {
                            startEntrance(view, bg, height)
                        }
                    }
                }
            })
            hooks += hookBefore(remove) { p ->
                val view = p.args[0] as View
                endEntrance(view)
                resetShadow(view)
                BannerTextContrast.clear(view)
                motions.entries.firstOrNull { it.value.row.get() === view }?.key?.let(::resetMotion)
                if (row.isInstance(view)) (normal.get(view) as? View)?.let(AlertBackdrop::forgetBanner)
            }
        } catch (error: Throwable) {
            hooks.forEach { it.unhook() }
            throw error
        }
    }

    private fun hookBefore(method: Method, action: (XC_MethodHook.MethodHookParam) -> Unit) =
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                if (enabled) safely { action(p) }
            }
        })

    private inline fun safely(action: () -> Unit) {
        try {
            action()
        } catch (error: Throwable) {
            GlassLogger.limited("BannerGlass", "runtime") { "Heads-up material fallback: $error" }
        }
    }

    private fun drawGlass(canvas:Canvas,view:View,row:View,frame:AlertBackdrop.Frame?,height:Int):Boolean {
        bounds.set(0f, 0f, view.width.toFloat(), height.toFloat())
        val radius = minOf(bounds.height() * corner / 100f, bounds.width() * .5f)
        val live=frame!=null && AlertGlassLens.renderBanner(canvas,view,lens,frame,bounds,radius)
        if(!live && !BannerTextContrast.has(row))return false
        val darkTheme=view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES
        val dark=topDarkness>=45 || if(Color.alpha(plateColor)>=164)plateDark else darkTheme
        BannerTextContrast.use(row,dark)
        paint.color = if (dark) Color.rgb(18, 18, 20) else Color.rgb(247, 247, 249)
        paint.alpha = if(live)opacity*255/100 else 255
        canvas.drawRoundRect(bounds, radius, radius, paint)
        drawTint(canvas, height, radius)
        paint.color=if(dark)0x38000000 else 0x20ffffff
        canvas.drawRoundRect(bounds,radius,radius,paint)
        edge.rewind()
        edge.addRoundRect(bounds, radius, radius, Path.Direction.CW)
        EdgeLight.drawPath(canvas, edge, bounds, view, .85f, GlassSurface.BANNERS)
        rendered++
        if (rendered == 1L) GlassLogger.i("BannerGlass", "Rendered live heads-up backdrop")
        return true
    }

    private fun drawTint(canvas: Canvas, height: Int, radius: Float) {
        if (tintHeight != height) {
            tintHeight = height
            plateGradient = if (Color.alpha(plateColor) == 0) null else {
                val alpha = Color.alpha(plateColor)
                val rgb = plateColor and 0x00ffffff
                val end = rgb or ((alpha * (100 - plateFade) / 100) shl 24)
                val middle = rgb or ((alpha * (200 - plateFade) / 200) shl 24)
                LinearGradient(0f, 0f, 0f, height.toFloat(),
                    intArrayOf(plateColor, plateColor, middle, end),
                    floatArrayOf(0f, .45f, .725f, 1f), Shader.TileMode.CLAMP)
            }
            topGradient = if (topDarkness == 0) null else LinearGradient(0f, 0f, 0f, height * .64f,
                Color.argb(topDarkness * 255 / 100, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
        paint.alpha = 255
        plateGradient?.let { paint.shader = it; canvas.drawRoundRect(bounds, radius, radius, paint) }
        topGradient?.let { paint.shader = it; canvas.drawRoundRect(bounds, radius, radius, paint) }
        paint.shader = null
    }

    private fun updateShadow(row: View) {
        if (shadowSize == 0 || shadowStrength == 0) {
            resetShadow(row)
            return
        }
        val state = shadows.getOrPut(row) { Shadow(row) }
        if (row.elevation != state.elevation) state.baseElevation = row.elevation
        state.elevation = state.baseElevation + shadowSize * row.resources.displayMetrics.density
        state.color = Color.argb(shadowStrength * 255 / 100, 0, 0, 0)
        if (row.elevation != state.elevation) row.elevation = state.elevation
        if (row.outlineAmbientShadowColor != state.color) row.outlineAmbientShadowColor = state.color
        if (row.outlineSpotShadowColor != state.color) row.outlineSpotShadowColor = state.color
    }

    private fun resetShadow(row: View) {
        val state = shadows.remove(row) ?: return
        if (row.elevation == state.elevation) row.elevation = state.baseElevation
        if (row.outlineAmbientShadowColor == state.color) row.outlineAmbientShadowColor = state.ambient
        if (row.outlineSpotShadowColor == state.color) row.outlineSpotShadowColor = state.spot
    }

    private fun updateMotion(row: View, background: View, motion: Motion, y: Float, height: Int) {
        // ColorOS keeps ownership of the spring, translation and finish callbacks.
        val progress = (1f + (y - motion.hold) / height).coerceIn(0f, 1.05f)
        val squeeze=if(softMotion)settle(progress.coerceAtMost(1f)) else progress
        val width = .89f + .11f * squeeze
        if (abs(width - 1f) > .002f && (motion.scaled || row.animationMatrix?.isIdentity != false)) {
            if (!motion.scaled) exits++
            motion.matrix.setScale(width, 1f, row.width * .5f, 0f)
            row.animationMatrix = motion.matrix
            motion.scaled = true
        } else if (motion.scaled) {
            row.animationMatrix = null
            motion.scaled = false
        }
        val blur = ((1f - progress / .72f) * 5f).roundToInt().coerceIn(0, 5)
        if (blur != motion.blur) {
            row.setRenderEffect(transitionBlur(blur, row.resources.displayMetrics.density, closing = true))
            motion.blur = blur
        }
        // Translation alone would otherwise reuse a backdrop recorded at the old position.
        background.invalidate()
    }

    private fun startEntrance(row: View, background: View, heightMethod: Method?) {
        endEntrance(row)
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 58500L / openSpeed
            interpolator = LinearInterpolator()
        }
        val entrance = Entrance(animator, row)
        entrancesInFlight[row] = entrance
        entrances++
        fun apply(progress: Float) {
            // COUI writes the row transform during the animation phase. Compose
            // the glass pose after those writes and before the frame is recorded.
            if (row.scaleX != entrance.appliedX) entrance.baseX = row.scaleX
            if (row.scaleY != entrance.appliedY) entrance.baseY = row.scaleY
            if (row.translationY != entrance.appliedTranslation) entrance.baseTranslation = row.translationY
            if (row.alpha != entrance.appliedAlpha) entrance.baseAlpha = row.alpha
            if (entrance.lastProgress < 0f) {
                entrance.smoothedY = entrance.baseTranslation
            } else {
                val elapsed = (progress - entrance.lastProgress).coerceAtLeast(0f)
                val follow = 1f - exp(-elapsed * 23f)
                entrance.smoothedY += (entrance.baseTranslation - entrance.smoothedY) * follow
            }
            entrance.lastProgress = progress
            var segment = 0
            while (segment < entranceTimes.lastIndex - 1 && progress >= entranceTimes[segment + 1]) {
                segment++
            }
            val segmentDuration = entranceTimes[segment + 1] - entranceTimes[segment]
            val fraction = (progress - entranceTimes[segment]) / segmentDuration
            fun between(curve: Curve) = curve.at(segment, fraction, segmentDuration)
            val h = actualHeight(background,heightMethod).coerceAtLeast(0)
            entrance.appliedX = entrance.baseX * between(if(softMotion)softWidths else entranceWidths)
            entrance.appliedY = entrance.baseY * between(if(softMotion)softHeights else entranceHeights)
            val settleToNative = settle(((progress - .62f) / .38f).coerceIn(0f, 1f))
            entrance.appliedTranslation = entrance.smoothedY +
                (entrance.baseTranslation - entrance.smoothedY) * settleToNative +
                between(if(softMotion)softOffsets else entranceOffsets) * h
            entrance.appliedAlpha = entrance.baseAlpha * between(entranceAlphas)
            row.scaleX = entrance.appliedX
            row.scaleY = entrance.appliedY
            row.translationY = entrance.appliedTranslation
            row.alpha = entrance.appliedAlpha
            val focus = ((progress - .10f) / .65f).coerceIn(0f, 1f)
            val blur = (openingBlur * (1f - focus * focus * (3f - 2f * focus))).roundToInt()
            if (blur != entrance.blur) {
                row.setRenderEffect(transitionBlur(blur, row.resources.displayMetrics.density))
                entrance.blur = blur
            }
            background.invalidate()
        }
        val listener = ViewTreeObserver.OnPreDrawListener {
            entrance.observer = row.viewTreeObserver
            safely { apply(animator.animatedFraction) }
            true
        }
        entrance.listener = listener
        entrance.observer.addOnPreDrawListener(listener)
        animator.addUpdateListener { row.invalidate() }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                safely { endEntrance(row) }
            }
        })
        animator.start()
    }

    private fun endEntrance(row: View) {
        val entrance = entrancesInFlight.remove(row) ?: return
        val observer = entrance.observer.takeIf { it.isAlive } ?: row.viewTreeObserver
        entrance.listener?.let { observer.removeOnPreDrawListener(it) }
        entrance.animator.removeAllListeners()
        entrance.animator.removeAllUpdateListeners()
        entrance.animator.cancel()
        if (row.scaleX == entrance.appliedX) row.scaleX = entrance.baseX
        if (row.scaleY == entrance.appliedY) row.scaleY = entrance.baseY
        if (row.translationY == entrance.appliedTranslation) row.translationY = entrance.baseTranslation
        if (row.alpha == entrance.appliedAlpha) row.alpha = entrance.baseAlpha
        if (entrance.blur != 0) row.setRenderEffect(null)
        row.invalidate()
    }

    private fun transitionBlur(level: Int, density: Float, closing: Boolean = false): RenderEffect? {
        if (level == 0) return null
        if (density != effectDensity) {
            effects.fill(null)
            exitEffects.fill(null)
            effectDensity = density
        }
        val cache = if (closing) exitEffects else effects
        val radius = level * density * if (closing) 1.8f else 1f
        return cache[level] ?: RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.DECAL).also {
            cache[level] = it
        }
    }

    private fun resetMotion(window: Any) {
        val motion = motions.remove(window) ?: return
        safely { motion.restoreSpeed?.invoke() }
        val row = motion.row.get() ?: return
        if (motion.scaled) row.animationMatrix = null
        if (motion.blur != 0) row.setRenderEffect(null)
    }
}
