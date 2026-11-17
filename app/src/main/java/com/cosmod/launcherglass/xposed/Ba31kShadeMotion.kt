package com.cosmod.launcherglass.xposed

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import com.cosmod.launcherglass.ui.SettingsProvider
import com.cosmod.launcherglass.util.Ba31kAnimationSettings as Settings
import com.cosmod.launcherglass.util.Ba31kGenieMotion.Spring
import com.cosmod.launcherglass.util.GlassLogger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.abs

/** Applies the shade spring to the visible launcher page using native Quickstep state flags. */
internal object Ba31kShadeMotion {
    private const val SHADE_FLAGS = 4L or 2048L
    private const val LOCK_FLAGS = 8L or 64L or 512L
    private data class Icon(val view: View, val x: Float, val y: Float, val spreadX: Float, val spreadY: Float)
    private var activity: WeakReference<Activity>? = null
    private var iconClass: Class<*>? = null
    private var homeState: Any? = null
    private val nativeEffects = WeakHashMap<View, RenderEffect>()
    private var icons = emptyList<Icon>()
    private val spring = Spring()
    private var target = 0.0
    private var lastFrame = 0L
    private var scheduled = false
    private var writingBlur = false
    private var blurStep = 0
    private var blurEffects = arrayOfNulls<RenderEffect>(5)
    private var enabled = false
    private var blur = true
    private var strength = 1.0
    private var speed = 1.0
    private var animatedClosures = 0
    private var lastFlags = 0L
    private var attempted = false
    private var ready = false

    private fun install(loader: ClassLoader): Boolean {
        return runCatching {
            val service = XposedHelpers.findClass("com.android.quickstep.TouchInteractionService", loader)
            iconClass = XposedHelpers.findClass("com.android.launcher3.BubbleTextView", loader)
            val main = Handler(Looper.getMainLooper())
            val direct = runCatching {
                val legacy = runCatching {
                    service.getDeclaredMethod("onSystemUiFlagsChanged", Long::class.javaPrimitiveType)
                }.getOrNull()
                val state = generateSequence(service) { it.superclass }.mapNotNull { owner ->
                    runCatching { owner.getDeclaredField(if (legacy != null) "mDeviceState" else "f18253p") }.getOrNull()
                }.first().apply { isAccessible = true }
                val read: (Any) -> Long = if (legacy != null) {
                    val getter = state.type.getMethod("getSystemUiStateFlags")
                    val readFlags: (Any) -> Long = { (getter.invoke(it) as Number).toLong() }
                    readFlags
                } else {
                    val flags = state.type.getDeclaredField("f18706d").apply { isAccessible = true }
                    check(flags.type == Long::class.javaPrimitiveType)
                    flags::getLong
                }
                val changed = legacy ?: service.getDeclaredMethod("f0", Long::class.javaPrimitiveType)
                XposedBridge.hookMethod(changed, object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        if (p.hasThrowable() || !enabled) return
                        runCatching {
                            val previous=p.args[0] as Long
                            val current=read(state.get(p.thisObject))
                            if (Looper.myLooper() == Looper.getMainLooper()) dispatch(previous,current)
                            else main.post { dispatch(previous,current) }
                        }
                    }
                })
            }.isSuccess
            if (!direct) {
                val changed = service.declaredClasses.flatMap { it.declaredMethods.toList() }
                    .single { method ->
                        method.name == "onSystemUiStateChanged" && method.returnType == Void.TYPE &&
                            method.parameterTypes.singleOrNull() in setOf(Int::class.javaPrimitiveType, Long::class.javaPrimitiveType)
                    }
                XposedBridge.hookMethod(changed, object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        if (p.hasThrowable() || !enabled) return
                        val current = (p.args[0] as Number).toLong()
                        main.post {
                            val previous = lastFlags
                            lastFlags = current
                            dispatch(previous,current)
                        }
                    }
                })
            }
            // The launcher can set its own effect after resume. Track it only once this
            // optional feature is used, so the render hook never runs during startup.
            runCatching {
                XposedBridge.hookMethod(View::class.java.getMethod("setRenderEffect", RenderEffect::class.java),
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(p: MethodHookParam) {
                            val view = p.thisObject as View
                            if (writingBlur || iconClass?.isInstance(view) != true) return
                            val effect = p.args[0] as? RenderEffect
                            synchronized(nativeEffects) {
                                if (effect == null) nativeEffects.remove(view) else nativeEffects[view] = effect
                            }
                        }
                    })
            }
            GlassLogger.i("Ba31kShadeMotion", "Quickstep shade flags resolved")
            true
        }.getOrElse {
            GlassLogger.limited("Ba31kShadeMotion", "contract") { "Native shade retained: $it" }
            false
        }
    }

    fun bind(launcher: Activity) {
        activity = WeakReference(launcher)
        if (enabled) prepare(launcher)
    }

    fun unbind(launcher: Activity) {
        if (activity?.get() !== launcher) return
        activity = null
        stop()
    }

    private fun prepare(launcher: Activity) {
        if (attempted) return
        attempted = true
        ready = install(launcher.classLoader)
    }

    private fun dispatch(previous: Long,current: Long) {
        if (enabled && ready) runCatching { onFlags(previous,current) }.onFailure { stop() }
    }

    fun update(prefs: Bundle) {
        val next = prefs.getBoolean(SettingsProvider.KEY_ENABLED) && prefs.getBoolean(Settings.ENABLED) &&
            prefs.getBoolean(Settings.SHADE)
        val nextBlur = prefs.getBoolean(Settings.SHADE_BLUR, true)
        val nextStrength = prefs.getInt(Settings.SPREAD, 100) / 100.0
        val nextSpeed = prefs.getInt(Settings.SPEED, 100) / 100.0
        if (!next || nextBlur != blur || nextStrength != strength || nextSpeed != speed) stop()
        enabled = next
        blur = nextBlur
        strength = nextStrength
        speed = nextSpeed
        if (enabled) activity?.get()?.let(::prepare)
    }

    fun diagnostics() = "ba31k shade enabled=$enabled, ready=$ready, closures=$animatedClosures"

    private fun onFlags(previous: Long, current: Long) {
        if ((current and LOCK_FLAGS) != 0L || !ValueAnimator.areAnimatorsEnabled()) {
            stop()
            return
        }
        val expanded = (current and SHADE_FLAGS) != 0L
        if (expanded == ((previous and SHADE_FLAGS) != 0L) || strength <= 0.0) return
        val host = activity?.get() ?: return
        if (!host.isResumedForShade()) { stop(); return }
        if (icons.isEmpty()) capture(host)
        if (icons.isEmpty()) return
        if (!expanded) animatedClosures++
        target = if (expanded) 1.0 else 0.0
        schedule()
    }

    private fun Activity.isResumedForShade(): Boolean {
        if (isFinishing || isDestroyed || !window.decorView.isShown) return false
        val manager = XposedHelpers.callMethod(this, "getStateManager") ?: return false
        val current = XposedHelpers.callMethod(manager, "getState") ?: return false
        // Reading an existing state first guarantees the launcher already initialized it.
        val home = homeState ?: XposedHelpers.getStaticObjectField(current.javaClass, "NORMAL")
            .also { homeState = it }
        return current === home
    }

    private fun capture(host: Activity) {
        val workspace = XposedHelpers.callMethod(host, "getWorkspace") as? ViewGroup ?: return
        if (workspace.width <= 0 || workspace.height <= 0) return
        val index = XposedHelpers.callMethod(workspace, "getCurrentPage") as? Int ?: return
        val page = workspace.getChildAt(index) as? ViewGroup ?: return
        val type = iconClass ?: return
        val bounds = IntArray(2)
        workspace.getLocationOnScreen(bounds)
        val centerX = bounds[0] + workspace.width / 2f
        val centerY = bounds[1] + workspace.height / 2f
        val dp = workspace.resources.displayMetrics.density
        blurEffects = if (blur) Array(5) { i ->
            if (i == 0) null else RenderEffect.createBlurEffect(i * dp * 1.5f, i * dp * 1.5f, Shader.TileMode.DECAL)
        } else arrayOfNulls(5)
        val selected = ArrayList<Icon>()
        fun collect(view: View, depth: Int) {
            if (depth > 5 || !view.isShown || selected.size >= 64) return
            if (type.isInstance(view)) {
                val at = IntArray(2)
                view.getLocationOnScreen(at)
                val x = ((at[0] + view.width / 2f - centerX) / workspace.width).coerceIn(-.5f, .5f)
                val y = ((at[1] + view.height / 2f - centerY) / workspace.height).coerceIn(-.5f, .5f)
                val reach = (strength * 72 * dp).toFloat()
                selected += Icon(view, view.translationX, view.translationY, x * reach, y * reach - 8 * dp)
            } else if (view is ViewGroup && view !is android.appwidget.AppWidgetHostView) {
                for (index in 0 until view.childCount) collect(view.getChildAt(index), depth + 1)
            }
        }
        collect(page, 0)
        icons = selected
    }

    private val frame = Choreographer.FrameCallback { now ->
        scheduled = false
        if (enabled && icons.isNotEmpty()) {
            val elapsed = if (lastFrame == 0L) .008 else ((now - lastFrame) * 1e-9).coerceIn(0.0, .05)
            lastFrame = now
            spring.advance(target, .36 / speed, .84, elapsed)
            val offset = spring.position.toFloat()
            val nextBlur = if (blur) (abs(spring.velocity) * .40).toInt().coerceIn(0, 4) else 0
            icons.forEach {
                it.view.translationX = it.x + it.spreadX * offset
                it.view.translationY = it.y + it.spreadY * offset
                if (blurStep != nextBlur) setBlur(it.view, blurEffects[nextBlur])
            }
            blurStep = nextBlur
            if (abs(spring.position - target) > .002 || abs(spring.velocity) > .02) schedule()
            else if (target == 0.0) stop()
        }
    }

    private fun setBlur(view: View, effect: RenderEffect?) {
        val native = synchronized(nativeEffects) { nativeEffects[view] }
        if (effect != null && native != null) return
        writingBlur = true
        try { view.setRenderEffect(native ?: effect) } finally { writingBlur = false }
    }

    private fun schedule() {
        if (scheduled) return
        scheduled = true
        Choreographer.getInstance().postFrameCallback(frame)
    }

    fun stop() {
        if (scheduled) Choreographer.getInstance().removeFrameCallback(frame)
        scheduled = false
        icons.forEach {
            it.view.translationX = it.x
            it.view.translationY = it.y
            if (blurStep != 0) setBlur(it.view, null)
        }
        icons = emptyList()
        blurStep = 0
        lastFrame = 0L
        target = 0.0
        spring.reset(0.0)
    }
}
