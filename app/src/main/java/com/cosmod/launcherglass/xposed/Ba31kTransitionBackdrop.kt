package com.cosmod.launcherglass.xposed

import android.view.SurfaceControl
import com.cosmod.launcherglass.util.Ba31kGenieMotion
import com.cosmod.launcherglass.util.GlassLogger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

/** Ramps the native launcher backdrop command as part of its own surface transaction. */
internal object Ba31kTransitionBackdrop {
    private data class Frame(val amount: Double, val time: Long)
    private data class Target(val surface: SurfaceControl, val maxRadius: Int, val amount: Double)
    @Volatile private var frame: Frame? = null
    @Volatile private var available = false
    private val scope = ThreadLocal<Target>()

    fun publish(openFraction: Double, opening: Boolean, time: Long) {
        if (available) frame = Frame(Ba31kGenieMotion.backdrop(openFraction,opening),time)
    }

    fun install(loader: ClassLoader) {
        if (available) return
        val hooks = ArrayList<XC_MethodHook.Unhook>(2)
        runCatching {
            val depth = loader.loadClass("com.android.launcher3.statehandlers.DepthController")
            val dispatch = depth.getDeclaredMethod("dispatchTransactionSurface",Float::class.javaPrimitiveType)
            val surface = depth.getDeclaredField("mSurface").apply { isAccessible = true }
            val supported = depth.getDeclaredField("mCrossWindowBlursEnabled").apply { isAccessible = true }
            val radius = depth.getDeclaredField("mMaxBlurRadius").apply { isAccessible = true }
            val setter = SurfaceControl.Transaction::class.java.getMethod("setBackgroundBlurRadius",
                SurfaceControl::class.java,Int::class.javaPrimitiveType)
            hooks += XposedBridge.hookMethod(dispatch, object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    p.setObjectExtra("cpiPreviousAppDepth",scope.get())
                    scope.remove()
                    val current = frame ?: return
                    if (Ba31kAppTransitions.config?.backdrop != true || !PrefsHelper.enabled ||
                        System.nanoTime() - current.time !in 0L..70_000_000L) return
                    runCatching {
                        if (!supported.getBoolean(p.thisObject)) return
                        val leash = surface.get(p.thisObject) as? SurfaceControl ?: return
                        val maxRadius = radius.getInt(p.thisObject)
                        if (!leash.isValid || maxRadius <= 0) return
                        scope.set(Target(leash,maxRadius,current.amount))
                    }
                }

                override fun afterHookedMethod(p: MethodHookParam) {
                    val previous = p.getObjectExtra("cpiPreviousAppDepth") as? Target
                    if (previous == null) scope.remove() else scope.set(previous)
                }
            })
            hooks += XposedBridge.hookMethod(setter, object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    val current = scope.get() ?: return
                    if (p.args[0] != current.surface) return
                    val nativeRadius = p.args[1] as? Int ?: return
                    // Keep the launcher's support and opaque-scrim veto. Only shape the blur it requests.
                    if (nativeRadius > 0) p.args[1] = (minOf(nativeRadius,current.maxRadius) * current.amount).toInt()
                }
            })
            available = true
        }.onFailure {
            hooks.forEach { it.unhook() }
            GlassLogger.limited("Ba31kTransitionBackdrop","app-depth") { "Native blur retained: $it" }
        }
    }
}
