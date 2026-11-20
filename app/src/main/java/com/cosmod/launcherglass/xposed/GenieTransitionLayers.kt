package com.cosmod.launcherglass.xposed

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.view.SurfaceControl
import android.view.View
import com.cosmod.launcherglass.util.AppWindowDeformation
import com.cosmod.launcherglass.util.GlassLogger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs

/** Coordinates the window and floating icon before ColorOS applies their transaction. */
internal class GenieTransitionLayers(private val setter: Method, private val identity: FloatArray) {
    private companion object {
        const val MATCH_NS = 24_000_000L
        const val POSE_NS = 50_000_000L
    }

    private class IconPass(
        val leash: SurfaceControl, val native: Matrix, val crop: Rect, val screen: RectF,
        val transaction: Any, val time: Long,
    ) {
        var builder: Any? = null
    }
    private data class IconTarget(val pass: IconPass, val builder: Any, val crop: Rect)
    private data class Window(
        val transaction: Any, val screen: RectF,
        val time: Long, val shared: SharedProjection?,
    )
    private data class ViewIcon(val view: View, val screen: RectF, val time: Long)
    data class Replay(val matrix: FloatArray, val time: Long)
    data class SharedProjection(
        val perspective: FloatArray?, val nativeMatrix: FloatArray?,
        val deformation: AppWindowDeformation?, val weight: Float,
    )

    private val passes = ThreadLocal<IconPass>()
    private val icons = ThreadLocal<IconTarget>()
    private val windows = ThreadLocal<Window>()
    private val viewIcons = ThreadLocal<ViewIcon>()
    private val projections = ThreadLocal.withInitial { GenieIconProjection() }
    private val warpedIcons = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<SurfaceControl, Boolean>())
    )
    private val replayFrames = Collections.synchronizedMap(WeakHashMap<Any, Replay>())
    private data class DrawPose(val matrix: Matrix, val time: Long)
    private val viewPoses = Collections.synchronizedMap(WeakHashMap<View, DrawPose>())
    private val drawingView = ThreadLocal<View>()
    private val frameDepth = ThreadLocal<Int>()
    private val projectedFrames = java.util.concurrent.atomic.AtomicLong()
    fun projectedFrames() = projectedFrames.get()
    var iconInstalled = false
        private set
    private var viewIconInstalled = false
    fun supportsIconProjection() = iconInstalled || viewIconInstalled
    var replayInstalled = false
        private set

    fun hasIconWork() = passes.get() != null || warpedIcons.isNotEmpty()

    fun beginFrame() {
        val depth=frameDepth.get() ?: 0
        frameDepth.set(depth+1)
        if(depth>0)return
        icons.remove()
        windows.remove()
        viewIcons.remove()
    }

    fun endFrame() {
        val depth=(frameDepth.get() ?: 1)-1
        if(depth>0){frameDepth.set(depth);return}
        frameDepth.remove()
        icons.remove()
        windows.remove()
        viewIcons.remove()
    }

    fun captureIcon(builder: Any, leash: SurfaceControl, affine: Matrix): Boolean {
        val pass = passes.get()?.takeIf { leash == it.leash && affine === it.native } ?: return false
        clearIcon(builder, leash)
        pass.builder = builder
        if (pass.crop.isEmpty) return true
        val crop = Rect(pass.crop)
        val icon = IconTarget(pass, builder, crop)
        icons.set(icon)
        matchLayers()
        return true
    }

    fun clearIcon(builder: Any, leash: SurfaceControl) {
        if (warpedIcons.remove(leash)) setter.invoke(builder, identity)
    }

    fun stageWindow(transaction: Any, screen: RectF, time: Long, shared: SharedProjection?) {
        windows.set(Window(transaction, RectF(screen), time, shared))
        matchLayers()
        matchView()
    }

    private fun matchLayers() {
        val icon = icons.get() ?: return
        val window = windows.get() ?: return
        val pass = icon.pass
        if (abs(window.time - pass.time) > MATCH_NS) {
            if (window.time > pass.time) icons.remove() else windows.remove()
            return
        }
        if (pass.transaction !== window.transaction || !pass.leash.isValid || !near(pass.screen, window)) return
        icons.remove()
        projectIcon(icon, window)
    }

    private fun projectIcon(icon: IconTarget, window: Window) {
        val projection = projections.get()!!
        val shared = window.shared ?: return
        if (projection.share(icon.pass.native, shared, icon.crop)) {
            setter.invoke(icon.builder, projection.matrix)
            warpedIcons.add(icon.pass.leash)
            val count = projectedFrames.incrementAndGet()
            if (count == 1L) GlassLogger.i("AppGenieTransition", "Icon uses the window's deformation")
        }
    }

    private fun near(icon: RectF, window: Window): Boolean {
        val bounds = window.screen
        // The icon and native crop may have different bounds during handoff.
        val tolerance = if (window.shared != null) .30f else .12f
        return abs(icon.centerX() - bounds.centerX()) <= maxOf(8f, bounds.width() * tolerance) &&
            abs(icon.centerY() - bounds.centerY()) <= maxOf(8f, bounds.height() * tolerance)
    }

    fun installIcon(loader: ClassLoader, params: Class<*>, surface: GenieLauncherContract.Surface,
                    hooks: MutableList<XC_MethodHook.Unhook>, active: () -> Boolean) {
        iconInstalled = bind(hooks, "icon") {
            val contract = GenieLauncherContract.icon(loader, params, surface)
            val updater = contract.owner
            val updateIcon = contract.update
            val matrix = updater.declaredFields.single {
                !Modifier.isStatic(it.modifiers) && it.type == Matrix::class.java
            }.apply { isAccessible = true }
            val crop = updater.declaredFields.single {
                !Modifier.isStatic(it.modifiers) && it.type == Rect::class.java
            }.apply { isAccessible = true }
            hooks += XposedBridge.hookMethod(updateIcon, object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    p.setObjectExtra("cpiPriorIconPass", passes.get())
                    passes.remove()
                    if (!active()) return
                    val leash = p.args[3] as? SurfaceControl ?: return
                    val screen = p.args[2] as? RectF ?: return
                    if (screen.isEmpty || !leash.isValid) return
                    passes.set(IconPass(leash, matrix.get(p.thisObject) as Matrix,
                        crop.get(p.thisObject) as Rect, RectF(screen), p.args[4], System.nanoTime()))
                }

                override fun afterHookedMethod(p: MethodHookParam) {
                    val previous = p.getObjectExtra("cpiPriorIconPass") as? IconPass
                    if (previous == null) passes.remove() else passes.set(previous)
                }
            })
            GlassLogger.i("AppGenieTransition", "Icon updater resolved: ${updater.name}.${updateIcon.name}")
        }
        installViewIcon(loader, hooks, active)
    }

    private fun matchView() {
        val window = windows.get() ?: return
        val icon = viewIcons.get() ?: return
        if (abs(window.time - icon.time) > MATCH_NS || !near(icon.screen, window)) return
        viewIcons.remove()
        val shared = window.shared
        if (shared == null) {
            if(viewPoses.remove(icon.view)!=null)icon.view.postInvalidateOnAnimation()
            return
        }
        projectView(icon, shared)
    }

    private fun projectView(icon: ViewIcon, shared: SharedProjection) {
        val view = icon.view
        val projection = projections.get()!!
        val crop=Rect(0,0,view.width,view.height)
        val localToScreen = Matrix().apply {
            setRectToRect(RectF(crop), icon.screen, Matrix.ScaleToFit.FILL)
        }
        if (projection.share(localToScreen, shared, crop)) {
            val pose=projection.asMatrix()
            viewPoses[view] = DrawPose(pose, icon.time)
            projectedFrames.incrementAndGet()
            view.postInvalidateOnAnimation()
        }
    }

    private fun installViewIcon(loader: ClassLoader, hooks: MutableList<XC_MethodHook.Unhook>,
                                active: () -> Boolean) {
        viewIconInstalled = bind(hooks, "classic floating icon") {
            val floating = loader.loadClass("com.android.launcher3.views.FloatingIconView")
            val clip = floating.getDeclaredField("mClipIconView").apply { isAccessible = true }
            val oplus = runCatching { loader.loadClass("com.android.launcher3.views.OplusFloatingIconView") }.getOrNull()
            val updateMethods = listOfNotNull(floating, oplus).flatMap { owner ->
                owner.declaredMethods.filter { it.name == "update" && RectF::class.java in it.parameterTypes }
            }
            check(updateMethods.isNotEmpty())
            for (method in updateMethods) {
                val rectIndex = method.parameterTypes.indexOf(RectF::class.java)
                hooks += XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        val view = clip.get(p.thisObject) as? View ?: return
                        if (p.hasThrowable() || !active() || !view.isAttachedToWindow ||
                            view.width <= 0 || view.height <= 0) {
                            if (viewPoses.remove(view) != null) view.postInvalidateOnAnimation()
                            return
                        }
                        val rect = p.args[rectIndex] as? RectF ?: return
                        val icon = ViewIcon(view, RectF(rect), System.nanoTime())
                        viewIcons.set(icon)
                        matchView()
                    }
                })
            }
            for (name in listOf("recycle", "onDetachedFromWindow")) {
                floating.declaredMethods.firstOrNull { it.name == name && it.parameterCount == 0 }?.let { method ->
                    hooks += XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(p: MethodHookParam) {
                            val view = clip.get(p.thisObject) as? View ?: return
                            viewPoses.remove(view)
                        }
                    })
                }
            }
            var drawHooks = 0
            for (name in listOf("ClipIconView", "OplusClipIconView")) {
                val owner = runCatching { loader.loadClass("com.android.launcher3.views.$name") }.getOrNull() ?: continue
                val draw = owner.declaredMethods.firstOrNull {
                    it.name == "draw" && it.parameterTypes.contentEquals(arrayOf(Canvas::class.java))
                } ?: continue
                drawHooks++
                hooks += XposedBridge.hookMethod(draw, object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        val view = p.thisObject as View
                        if (!active() || drawingView.get() === view || IconGlassHook.isRecordingNative() || NativeSurfaceLive.recordingMaterial) return
                        val pose = viewPoses[view] ?: return
                        if (System.nanoTime() - pose.time > POSE_NS) {
                            viewPoses.remove(view)
                            return
                        }
                        val canvas = p.args[0] as Canvas
                        p.setObjectExtra("cpiIconCanvasSave", canvas.save())
                        p.setObjectExtra("cpiIconPreviousDraw", drawingView.get())
                        drawingView.set(view)
                        // Keep the OEM View translation and scale intact.
                        canvas.concat(pose.matrix)
                        // Ensure the final transformed display list is replaced even
                        // when ColorOS stops updating this reused floating view.
                        view.postInvalidateOnAnimation()
                    }

                    override fun afterHookedMethod(p: MethodHookParam) {
                        val save = p.getObjectExtra("cpiIconCanvasSave") as? Int ?: return
                        (p.args[0] as Canvas).restoreToCount(save)
                        drawingView.set(p.getObjectExtra("cpiIconPreviousDraw") as? View)
                    }
                })
            }
            check(drawHooks > 0)
        }
    }

    fun installReplay(loader: ClassLoader, record: Class<*>, hooks: MutableList<XC_MethodHook.Unhook>,
                      active: () -> Boolean, snapshot: (Any) -> Replay?) {
        replayInstalled = bind(hooks, "replay") {
            val native = SurfaceControl.Transaction::class.java
            val wrapper = loader.loadClass("com.oplus.wrapper.view.SurfaceControl" + "\$Transaction")
            val matrixSetter = wrapper.getDeclaredMethod("setTransformationMatrix4x4", native,
                SurfaceControl::class.java, FloatArray::class.java)
            check(Modifier.isStatic(matrixSetter.modifiers))
            val apply = record.declaredMethods.single {
                !Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE &&
                    it.parameterTypes.contentEquals(arrayOf(native, SurfaceControl::class.java))
            }
            val clear = record.getDeclaredMethod("c")
            val valid = record.getDeclaredField("a").apply { isAccessible = true }
            val float = Float::class.javaPrimitiveType
            val write = record.declaredMethods.single {
                !Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE &&
                    it.parameterTypes.contentEquals(arrayOf(float, float, float, float, float, float,
                        Rect::class.java, float, Int::class.javaPrimitiveType, float, float))
            }
            hooks += XposedBridge.hookMethod(write, object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    val current = if (p.hasThrowable()) null else snapshot(p.thisObject)
                    if (current == null) replayFrames.remove(p.thisObject) else replayFrames[p.thisObject] = current
                }
            })
            hooks += XposedBridge.hookMethod(apply, object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    if (p.hasThrowable() || !active() || !valid.getBoolean(p.thisObject)) return
                    val replay = replayFrames[p.thisObject] ?: return
                    val leash = p.args[1] as? SurfaceControl ?: return
                    if (!leash.isValid || System.nanoTime() - replay.time > 150_000_000L) return
                    // The record remains tied to the task when ColorOS replaces its surface.
                    matrixSetter.invoke(null, p.args[0], leash, replay.matrix)
                }
            })
            hooks += XposedBridge.hookMethod(clear, object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    replayFrames.remove(p.thisObject)
                }
            })
        }
    }

    private inline fun bind(hooks: MutableList<XC_MethodHook.Unhook>, name: String, register: () -> Unit): Boolean {
        val start = hooks.size
        return try {
            register()
            true
        } catch (error: Exception) {
            while (hooks.size > start) hooks.removeAt(hooks.lastIndex).unhook()
            GlassLogger.limited("AppGenieTransition", name) { "Launcher contract unavailable: $error" }
            false
        }
    }
}
