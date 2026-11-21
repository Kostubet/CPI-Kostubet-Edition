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
    private val identity = FloatArray(16).apply { for (i in 0..3) this[i * 5] = 1f }

    fun install(loader: ClassLoader): Boolean = runCatching {
        val helper = loader.loadClass("com.android.quickstep.util.animation.RectTransformHelper")
        val transform = helper.declaredMethods.single { method ->
            val args = method.parameterTypes
            method.name == "applySurfaceParams" && args.size == 7 && args[1].isArray && args[5] == RectF::class.java
        }
        val (params, targets, _, tx, record) = transform.parameterTypes.take(5)
        val target = requireNotNull(targets.componentType)
        val float = Float::class.javaPrimitiveType
        val callback = helper.declaredMethods.single {
            it.name.startsWith("applySurfaceParams\$lambda\$") && Modifier.isStatic(it.modifiers) &&
                it.parameterTypes.contentEquals(arrayOf(target, helper, tx, params,
                    Boolean::class.javaPrimitiveType, record, float, RectF::class.java))
        }
        val shell = tx.getMethod("forSurface", SurfaceControl::class.java)
        val perspective = tx.declaredClasses.flatMap { it.declaredMethods.toList() }.single {
            it.name == "setMatrix4x4" && it.parameterTypes.contentEquals(arrayOf(FloatArray::class.java))
        }
        val radius = perspective.declaringClass.getMethod("setCornerRadius", float)
        val leash = target.getField("leash")
        check(leash.type == SurfaceControl::class.java)
        val bounds = helper.getDeclaredField("mRealWindowRect").apply { isAccessible = true }
        val crop = helper.getDeclaredField("mClipRect").apply { isAccessible = true }
        val kind = helper.getDeclaredField("mAnimType").apply { isAccessible = true }
        val matrix = helper.getDeclaredField("mTmpMatrix").apply { isAccessible = true }
        check(matrix.type == Matrix::class.java && bounds.type == Rect::class.java && crop.type == Rect::class.java)
        val radiusValue = params.getMethod("getRadius")
        val taskView = params.getMethod(
            if (params.methods.any { it.name == "isTaskView" && it.parameterCount == 0 }) "isTaskView" else "getIsTaskView",
        )
        check(taskView.returnType == Boolean::class.javaPrimitiveType)
        val hooks = ArrayList<XC_MethodHook.Unhook>()
        try {
            hooks += XposedBridge.hookMethod(callback, object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    val config = Ba31kAppTransitions.config
                    if (p.hasThrowable() || config == null && warped.isEmpty()) return
                    runCatching {
                        val surface = leash.get(p.args[0]) as? SurfaceControl ?: return
                        if (!surface.isValid) return
                        val owner = p.args[1]
                        val transaction = p.args[2]
                        val input = p.args[3]
                        val rect = p.args[7] as RectF
                        val name = (kind.get(owner) as? Enum<*>)?.name
                        val opening = name in opens
                        val close = name in closes
                        val shouldAnimate = config != null && PrefsHelper.enabled && ValueAnimator.areAnimatorsEnabled() &&
                            ((opening && config.opening) || (close && config.closing)) &&
                            taskView.invoke(input) == false
                        val full = bounds.get(owner) as Rect
                        // ColorOS's lightweight home gesture does not always submit a crop.
                        val clip = (crop.get(owner) as Rect).takeUnless(Rect::isEmpty) ?: full
                        val prop = if (shouldAnimate || surface in warped) shell.invoke(transaction, surface) else return
                        if (!perspective.declaringClass.isInstance(prop)) return
                        if (!shouldAnimate || full.isEmpty || rect.isEmpty || full.width() >= full.height()) {
                            if (warped.remove(surface)) perspective.invoke(prop, identity)
                            return
                        }
                        val settings = requireNotNull(config)
                        val fraction = ((rect.height() / full.height() - .065) / .935).coerceIn(0.0, 1.0)
                        val now = System.nanoTime()
                        if (settings.backdrop) {
                            Ba31kTransitionBackdrop.publish(fraction,opening,now)
                        }
                        val remaining = (1.0 - rect.height() / full.height()).coerceAtLeast(.2)
                        val x = (rect.centerX() - full.exactCenterX()) / (full.width() * remaining)
                        val y = 2.0 * (rect.centerY() - full.exactCenterY()) / (full.height() * remaining)
                        val iconWidth = .065 * full.height() / full.width()
                        val deform = Ba31kAppTransitions.deformation(p.args[5] ?: surface, settings, fraction,
                            .5 + x - iconWidth * .5, iconWidth, y, opening, name == "SWIPE_TO_HOME", now)
                        if (deform == null) {
                            if (warped.remove(surface)) perspective.invoke(prop, identity)
                        } else {
                            val out = buffer.get()!!
                            deform.writeMatrix(out,clip.left.toFloat(),clip.top.toFloat(),clip.width().toFloat(),clip.height().toFloat())
                            if (out.all(Float::isFinite)) {
                                perspective.invoke(prop, out)
                                warped.add(surface)
                                Ba31kAppTransitions.recordFrame(opening)
                            }
                        }
                        val amount = Ba31kGenieMotion.droplet(fraction, opening, settings.droplet)
                        val native = (radiusValue.invoke(input) as Number).toFloat()
                        val limit = minOf(clip.width(), clip.height()) * .5f
                        if (amount > 0f && native.isFinite() && native in 0f..<limit)
                            radius.invoke(prop, native + (limit - native) * amount)
                    }.onFailure {
                        GlassLogger.limited("Ba31kColorOs16", "frame") { "Native window retained: $it" }
                    }
                }
            })
            runCatching { installSpeed(loader, hooks) }.onFailure {
                GlassLogger.limited("Ba31kColorOs16", "spring") { "Native spring retained: $it" }
            }
        } catch (error: Throwable) {
            hooks.forEach { it.unhook() }
            throw error
        }
        GlassLogger.i("Ba31kColorOs16", "Native launcher 16 window callback resolved")
        true
    }.getOrElse {
        GlassLogger.limited("Ba31kColorOs16", "contract") { "Native launcher retained: $it" }
        false
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
