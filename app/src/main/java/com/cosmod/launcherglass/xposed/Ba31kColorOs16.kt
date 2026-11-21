package com.cosmod.launcherglass.xposed

import android.animation.ValueAnimator
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.view.SurfaceControl
import com.cosmod.launcherglass.util.Ba31kGenieMotion
import com.cosmod.launcherglass.util.GlassLogger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap

/** Named ColorOS 16 launcher contract. The surface callback can run outside applySurfaceParams. */
internal object Ba31kColorOs16 {
    private val opens = setOf("OPEN_FROM_HOME", "REVERSE_TO_OPEN", "REVERSE_TO_OPEN_REMOTE")
    private val closes = setOf("REMOTE_CLOSE_TO_HOME", "SWIPE_TO_HOME")
    private val warped = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<SurfaceControl, Boolean>()))
    private val buffer = ThreadLocal.withInitial { FloatArray(16) }
    private val mappedBounds = ThreadLocal.withInitial { RectF() }
    private val identity = FloatArray(16).apply { for (i in 0..3) this[i * 5] = 1f }

    fun install(loader: ClassLoader): Boolean = runCatching {
        val helper = loader.loadClass("com.android.quickstep.util.animation.RectTransformHelper")
        val transform = helper.declaredMethods.single { method ->
            val args = method.parameterTypes
            method.name == "applySurfaceParams" && args.size == 7 && args[1].isArray && args[5] == RectF::class.java
        }
        val (params, targets, _, tx, record) = transform.parameterTypes.take(5)
        val props = tx.getMethod("forSurface", SurfaceControl::class.java).returnType
        val bounds = helper.getDeclaredField("mRealWindowRect").apply { isAccessible = true }
        val crop = helper.getDeclaredField("mClipRect").apply { isAccessible = true }
        val kind = helper.getDeclaredField("mAnimType").apply { isAccessible = true }
        check(bounds.type == Rect::class.java && crop.type == Rect::class.java)
        val radiusValue = params.getMethod("getRadius")
        val taskView = params.getMethod(
            if (params.methods.any { it.name == "isTaskView" && it.parameterCount == 0 }) "isTaskView" else "getIsTaskView",
        )
        check(taskView.returnType == Boolean::class.javaPrimitiveType)
        val matrixCommit = helper.declaredMethods.singleOrNull {
            it.name == "zuyqaSetMatrix" && it.returnType == props &&
                it.parameterTypes.contentEquals(arrayOf(props, Matrix::class.java, params))
        }
        val hooks = ArrayList<XC_MethodHook.Unhook>()
        try {
            val nativeProjection = matrixCommit != null && runCatching {
                val sourceBounds = helper.getDeclaredField("mSourceWindowRect").apply { isAccessible = true }
                val surfaceField = props.getDeclaredField("mSurface").apply { isAccessible = true }
                val transactionField = props.getDeclaredField("this\$0").apply { isAccessible = true }
                val transaction = tx.getMethod("getTransaction")
                val setPerspective = SurfaceControl.Transaction::class.java.getDeclaredMethod(
                    "setTransformationMatrix4x4", SurfaceControl::class.java, FloatArray::class.java,
                )
                val thumb = loader.loadClass("com.android.launcher3.anim.ThumbSurfaceControlUtils")
                    .getDeclaredMethod("getSAppThumbSurfaceControl")
                check(sourceBounds.type == Rect::class.java && surfaceField.type == SurfaceControl::class.java)
                check(transactionField.type == tx && transaction.returnType == SurfaceControl.Transaction::class.java)
                check(Modifier.isStatic(thumb.modifiers) && thumb.returnType == SurfaceControl::class.java)
                hooks += XposedBridge.hookMethod(matrixCommit, object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        val config = Ba31kAppTransitions.config
                        if (p.hasThrowable() || config == null && warped.isEmpty()) return
                        runCatching {
                            val prop = p.result ?: return
                            val surface = surfaceField.get(prop) as? SurfaceControl ?: return
                            if (!surface.isValid || surface == thumb.invoke(null)) return
                            val owner = p.thisObject
                            val name = (kind.get(owner) as? Enum<*>)?.name
                            val settings = config.takeIf { isActive(it, name) && taskView.invoke(p.args[2]) == false }
                            val full = bounds.get(owner) as Rect
                            val clip = (crop.get(owner) as Rect).takeUnless(Rect::isEmpty)
                                ?: (sourceBounds.get(owner) as Rect)
                            val rect = mappedBounds.get()!!
                            rect.set(clip)
                            // Use the transform from this transaction, including the lightweight home gesture.
                            val mapped = (p.args[1] as Matrix).mapRect(rect)
                            val scale = if (mapped && !clip.isEmpty) rect.height() / clip.height().toDouble() else 0.0
                            val opening = name in opens
                            val projection = if (scale > 0.0 && settings?.projectsWindow == true) {
                                sampleProjection(surface, settings, full, clip, rect, scale, opening, name == "SWIPE_TO_HOME")
                            } else null
                            if (projection == null && surface !in warped) return
                            val native = transaction.invoke(transactionField.get(prop))
                            setPerspective.invoke(native, surface, projection ?: identity)
                            recordProjection(surface, opening, projection)
                        }.onFailure {
                            GlassLogger.limited("Ba31kColorOs16", "matrix") { "Native matrix retained: ${it.cause ?: it}" }
                        }
                    }
                })
            }.onFailure {
                GlassLogger.limited("Ba31kColorOs16", "matrixContract") { "Dedicated matrix path unavailable: $it" }
            }.isSuccess
            val target = requireNotNull(targets.componentType)
            val float = Float::class.javaPrimitiveType
            val callback = helper.declaredMethods.single {
                it.name.startsWith("applySurfaceParams\$lambda\$") && Modifier.isStatic(it.modifiers) &&
                    it.parameterTypes.contentEquals(arrayOf(target, helper, tx, params,
                        Boolean::class.javaPrimitiveType, record, float, RectF::class.java))
            }
            val shell = tx.getMethod("forSurface", SurfaceControl::class.java)
            val perspective = if (nativeProjection) null else props.getMethod("setMatrix4x4", FloatArray::class.java)
            val radius = props.getMethod("setCornerRadius", float)
            val leash = target.getField("leash")
            check(leash.type == SurfaceControl::class.java)
            hooks += XposedBridge.hookMethod(callback, object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    val config = Ba31kAppTransitions.config
                    if (p.hasThrowable() || config == null && warped.isEmpty()) return
                    runCatching {
                        val settings = config.takeIf { !nativeProjection || it?.backdrop == true || (it?.droplet ?: 0.0) > 0.0 }
                        if (nativeProjection && settings == null) return
                        val owner = p.args[1]
                        val input = p.args[3]
                        val name = (kind.get(owner) as? Enum<*>)?.name
                        val opening = name in opens
                        val active = isActive(settings, name) && taskView.invoke(input) == false
                        val surface = leash.get(p.args[0]) as? SurfaceControl ?: return
                        if (!surface.isValid || !active && (nativeProjection || surface !in warped)) return
                        val full = bounds.get(owner) as Rect
                        val clip = (crop.get(owner) as Rect).takeUnless(Rect::isEmpty) ?: full
                        val rect = p.args[7] as RectF
                        val scale = if (full.isEmpty || rect.isEmpty) 0.0 else rect.height() / full.height().toDouble()
                        val prop = shell.invoke(p.args[2], surface) ?: return
                        val ready = active && scale > 0.0 && full.width() < full.height()
                        if (perspective != null) {
                            val projection = if (ready && settings?.projectsWindow == true) {
                                sampleProjection(p.args[5] ?: surface, settings, full, clip, rect,
                                    scale, opening, name == "SWIPE_TO_HOME")
                            } else null
                            if (projection != null || surface in warped) {
                                perspective.invoke(prop, projection ?: identity)
                                recordProjection(surface, opening, projection)
                            }
                        }
                        if (!ready || settings == null) return
                        val fraction = openFraction(scale)
                        if (settings.backdrop) Ba31kTransitionBackdrop.publish(fraction, opening, System.nanoTime())
                        val amount = Ba31kGenieMotion.droplet(fraction, opening, settings.droplet)
                        if (amount <= 0f) return
                        val native = (radiusValue.invoke(input) as Number).toFloat()
                        val limit = minOf(clip.width(), clip.height()) * .5f
                        if (native.isFinite() && native in 0f..<limit)
                            radius.invoke(prop, native + (limit - native) * amount)
                    }.onFailure {
                        GlassLogger.limited("Ba31kColorOs16", "frame") { "Native window retained: $it" }
                    }
                }
            })
            GlassLogger.i("Ba31kColorOs16", "ColorOS 16 window projection installed, direct=$nativeProjection")
            runCatching { installSpeed(loader, hooks) }.onFailure {
                GlassLogger.limited("Ba31kColorOs16", "spring") { "Native spring retained: $it" }
            }
        } catch (error: Throwable) {
            hooks.forEach { it.unhook() }
            throw error
        }
        true
    }.getOrElse {
        GlassLogger.limited("Ba31kColorOs16", "contract") { "Native launcher retained: $it" }
        false
    }

    private fun isActive(config: Ba31kAppTransitions.Config?, name: String?): Boolean =
        config != null && ((name in opens && config.opening) || (name in closes && config.closing)) &&
            PrefsHelper.enabled && ValueAnimator.areAnimatorsEnabled()

    private fun openFraction(scale: Double) = ((scale - .065) / .935).coerceIn(0.0, 1.0)

    private fun sampleProjection(
        owner: Any,
        config: Ba31kAppTransitions.Config,
        full: Rect,
        clip: Rect,
        rect: RectF,
        scale: Double,
        opening: Boolean,
        swipe: Boolean,
    ): FloatArray? {
        if (clip.isEmpty || full.isEmpty || full.width() >= full.height()) return null
        val remaining = (1.0 - scale).coerceAtLeast(.2)
        val x = (rect.centerX() - full.exactCenterX()) / (full.width() * remaining)
        val y = 2.0 * (rect.centerY() - full.exactCenterY()) / (full.height() * remaining)
        val iconWidth = .065 * full.height() / full.width()
        val deformation = Ba31kAppTransitions.deformation(owner, config, openFraction(scale),
            .5 + x - iconWidth * .5, iconWidth, y, opening, swipe, System.nanoTime()) ?: return null
        val out = buffer.get()!!
        deformation.writeMatrix(out, clip.left.toFloat(), clip.top.toFloat(), clip.width().toFloat(), clip.height().toFloat())
        return out.takeIf { it.all(Float::isFinite) }
    }

    private fun recordProjection(surface: SurfaceControl, opening: Boolean, projection: FloatArray?) {
        if (projection == null) {
            warped.remove(surface)
        } else {
            warped.add(surface)
            Ba31kAppTransitions.recordFrame(opening)
        }
    }

    private fun installSpeed(loader: ClassLoader, hooks: MutableList<XC_MethodHook.Unhook>) {
        val type = loader.loadClass("com.android.quickstep.util.animation.CustomRectFSpringAnim")
        val kind = type.getDeclaredField("mAnimType").apply { isAccessible = true }
        val setter = type.declaredMethods.single {
            it.name == "setSpringHolderParamByType" && it.parameterTypes.size == 3
        }
        hooks += XposedBridge.hookMethod(setter, object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                val config = Ba31kAppTransitions.config ?: return
                if (!PrefsHelper.enabled || !ValueAnimator.areAnimatorsEnabled()) return
                val name = (kind.get(p.thisObject) as? Enum<*>)?.name ?: return
                if (name in opens && config.opening)
                    Ba31kAppTransitions.tuneSpring(p.args,config,opening=true,swipe=false)
                else if (name in closes && config.closing)
                    Ba31kAppTransitions.tuneSpring(p.args,config,opening=false,swipe=name=="SWIPE_TO_HOME")
            }
        })
    }
}
