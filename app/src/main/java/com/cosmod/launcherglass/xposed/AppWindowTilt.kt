package com.cosmod.launcherglass.xposed

import android.animation.ValueAnimator
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.view.SurfaceControl
import com.cosmod.launcherglass.util.GlassLogger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Pitches the app surface on ColorOS's native transition timeline. */
internal object AppWindowTilt {
    private data class Config(
        val enabled: Boolean = false, val opening: Boolean = true, val closing: Boolean = true,
        val strength: Float = 1f, val enter: Float = 1f, val exit: Float = 1f,
        val perspective: Float = 1f, val smoothness: Float = .70f,
        val center: Float = 0f, val yaw: Float = 1f
    )
    @Volatile private var config = Config()
    private var attempted = false
    private var installed = false
    private data class Pose(val pitch: Float, val yaw: Float, val opening: Boolean, val distance: Float)
    private data class Frame(val native: Matrix, val crop: Rect, val pose: Pose)
    private val pitched = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<SurfaceControl, Boolean>()))
    private val frame = ThreadLocal<Frame>()
    private val widgetTransition=ThreadLocal<Boolean>()
    private val widgetHelpers=Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<Any,Boolean>()))
    private val scratch = ThreadLocal.withInitial { FloatArray(16) }
    private val identity = FloatArray(16).apply {
        this[0] = 1f
        this[5] = 1f
        this[10] = 1f
        this[15] = 1f
    }
    private val openFrames = AtomicLong()
    private val closeFrames = AtomicLong()
    private val openingTypes = setOf("LAUNCH_FROM_HOME", "PRE_START3_LAUNCH", "LAUNCH_FROM_INTEGRATION",
        "LAUNCH_FROM_RECENTS", "SWIPE_BREAK_LAUNCH")
    private val closingTypes = setOf("REMOTE_CLOSE_TO_HOME", "PREDICTIVE_BACK_TO_HOME",
        "REMOTE_CLOSE_TO_INTEGRATION", "SWIPE_TO_HOME", "SWIPE_TO_CLIENT_HOME")

    fun update(prefs: Bundle) {
        config = Config(
            enabled = prefs.getBoolean("app_tilt_enabled", false),
            opening = prefs.getBoolean("app_tilt_open", true),
            closing = prefs.getBoolean("app_tilt_close", true),
            strength = prefs.getInt("app_tilt_reference_strength", 100).coerceIn(0, 200) * .01f,
            enter = prefs.getInt("app_tilt_enter", 100).coerceIn(0, 150) * .01f,
            exit = prefs.getInt("app_tilt_exit", 100).coerceIn(0, 150) * .01f,
            perspective = prefs.getInt("app_tilt_reference_perspective", 100).coerceIn(0, 150) * .01f,
            smoothness = prefs.getInt("app_tilt_smoothness", 70).coerceIn(0, 100) * .01f,
            center = prefs.getInt("app_tilt_reference_center", 0).coerceIn(0, 100) * .01f,
            yaw = prefs.getInt("app_tilt_yaw", 100).coerceIn(0, 150) * .01f,
        )
    }

    fun diagnostics() = "app tilt enabled=${config.enabled}, installed=$installed, open=${openFrames.get()}, close=${closeFrames.get()}"

    fun install(loader: ClassLoader) {
        if (attempted) return
        attempted = true
        val hooks = ArrayList<XC_MethodHook.Unhook>()
        try {
            val helper = loader.loadClass("com.android.quickstep.util.animation.RectTransformHelper")
            val params = loader.loadClass(helper.name + "\$TransformParams")
            val target = loader.loadClass("gi.c")
            val targets = java.lang.reflect.Array.newInstance(target, 0).javaClass
            val transaction = loader.loadClass("xi.q")
            val builder = loader.loadClass("xi.q\$b")
            val transform = helper.getDeclaredMethod("a", params, targets, Int::class.javaPrimitiveType,
                transaction, loader.loadClass("ni.e"), RectF::class.java, Boolean::class.javaPrimitiveType,
                RectF::class.java, target)
            val matrix = helper.getDeclaredField("d").apply { isAccessible = true }
            check(matrix.type == Matrix::class.java)
            val crop = helper.getDeclaredField("c").apply { isAccessible = true }
            check(crop.type == Rect::class.java)
            val type = helper.getDeclaredField("e").apply { isAccessible = true }
            check(type.type.isEnum)
            val fullBounds = helper.getDeclaredMethod("f")
            check(fullBounds.returnType == Rect::class.java)
            val rotating = params.getDeclaredMethod("b")
            check(rotating.returnType == Boolean::class.javaPrimitiveType)
            val anchorWidth=params.declaredFields.firstOrNull {it.name=="c" && it.type==Float::class.javaPrimitiveType}
                ?.apply {isAccessible=true}
            val anchorHeight=params.declaredFields.firstOrNull {it.name=="d" && it.type==Float::class.javaPrimitiveType}
                ?.apply {isAccessible=true}
            // The stock spring callbacks copy their current progress to this field each frame.
            val springProgress = params.declaredFields.firstOrNull {
                it.name == "l" && it.type == Float::class.javaPrimitiveType
            }?.apply { isAccessible = true }
            val windowAlpha = params.declaredFields.firstOrNull {
                it.name == "b" && it.type == Float::class.javaPrimitiveType
            }?.apply { isAccessible = true }
            val iconFadeThreshold = params.declaredFields.firstOrNull {
                it.name == "F" && it.type == Float::class.javaPrimitiveType
            }?.apply { isAccessible = true }
            val setMatrix = builder.getDeclaredMethod("i", Matrix::class.java)
            check(setMatrix.returnType == builder)
            val setPerspective = builder.getDeclaredMethod("j", FloatArray::class.java)
            check(setPerspective.returnType == Void.TYPE)
            val surface = builder.getDeclaredField("a").apply { isAccessible = true }
            check(surface.type == SurfaceControl::class.java)

            val widgetCard=XposedHelpers.findClassIfExists("com.android.launcher3.card.LauncherCardView",loader)
            val clock=XposedHelpers.findClassIfExists("com.android.launcher3.inspirationclock.InspirationClock",loader)
            val transitions=listOf("OplusLauncherAppTransitionHelper","OplusLauncherAppTransitionHelperV2")
            for(name in transitions){
                val owner=XposedHelpers.findClassIfExists("com.android.launcher3.anim.$name",loader) ?: continue
                for(method in owner.declaredMethods){
                    if(method.name !in setOf("startAppLaunchWindowAnim","startAppCloseWindowAnim"))continue
                    val viewIndex=method.parameterTypes.indexOfFirst {android.view.View::class.java.isAssignableFrom(it)}
                    if(viewIndex<0)continue
                    hooks+=XposedBridge.hookMethod(method,object:XC_MethodHook(){
                        override fun beforeHookedMethod(p:MethodHookParam){
                            p.setObjectExtra("cpiPreviousWidget",widgetTransition.get())
                            val source=p.args[viewIndex]
                            widgetTransition.set(widgetTransition.get()==true || source is android.appwidget.AppWidgetHostView ||
                                widgetCard?.isInstance(source)==true || clock?.isInstance(source)==true)
                        }
                        override fun afterHookedMethod(p:MethodHookParam){
                            val previous=p.getObjectExtra("cpiPreviousWidget") as? Boolean
                            if(previous==null)widgetTransition.remove() else widgetTransition.set(previous)
                        }
                    })
                }
            }
            hooks+=XposedBridge.hookAllConstructors(helper,object:XC_MethodHook(){
                override fun afterHookedMethod(p:MethodHookParam){
                    if(widgetTransition.get()==true)widgetHelpers.add(p.thisObject)
                }
            })

            hooks += XposedBridge.hookMethod(transform, object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    p.setObjectExtra("cpiTiltPrevious", frame.get())
                    frame.remove()
                    val values = config
                    val parameters = p.args[0]
                    if (!PrefsHelper.enabled || !values.enabled || values.strength == 0f ||
                        !ValueAnimator.areAnimatorsEnabled()) return
                    try {
                        if(widgetTransition.get()==true || p.thisObject in widgetHelpers)return
                        val name = (type.get(p.thisObject) as Enum<*>).name
                        val opening = name in openingTypes
                        if ((!opening || !values.opening) && (name !in closingTypes || !values.closing)) return
                        if (rotating.invoke(p.args[0]) == true) return
                        val bounds = fullBounds.invoke(p.thisObject) as Rect
                        val rect = p.args[5] as RectF
                        if (bounds.isEmpty || rect.isEmpty) return
                        val ratio = min(rect.width() / bounds.width(), rect.height() / bounds.height())
                        if (!ratio.isFinite()) return
                        val anchor=if(anchorWidth!=null && anchorHeight!=null){
                            min(anchorWidth.getFloat(p.args[0])/bounds.width(),anchorHeight.getFloat(p.args[0])/bounds.height())
                                .takeIf {it in 0.015f..0.35f} ?: .065f
                        }else .065f
                        val progress=((ratio-anchor)/(1f-anchor)).coerceIn(0f,1f)
                        // Use the actual path towards the icon. In particular,
                        // windows coming from the top row pitch the other way.
                        val travel = 1f - ratio
                        val remaining = sqrt(travel * travel + .22f * .22f)
                        val fromX = ((rect.centerX() - bounds.exactCenterX()) * 2f /
                            (bounds.width() * remaining)).coerceIn(-1f, 1f)
                        val fromY = ((rect.centerY() - bounds.exactCenterY()) * 2f /
                            (bounds.height() * remaining)).coerceIn(-1f, 1f)
                        // The tilt follows the already interpolated spring progress in either direction.
                        val phase = springProgress?.getFloat(parameters)?.takeIf { it.isFinite() } ?: progress
                        val frameProgress = phase.coerceIn(0f, 1f)
                        val arc = 4f * frameProgress * (1f - frameProgress)
                        val angleWeight = arc + (smoothStep(arc) - arc) * values.smoothness
                        val handoff = smoothStep((progress - .10f) / .30f)
                        val fadeThreshold = iconFadeThreshold?.getFloat(parameters) ?: 1f
                        val appAlpha = windowAlpha?.getFloat(parameters) ?: fadeThreshold
                        val visibility = if (fadeThreshold.isFinite() && fadeThreshold > 0f && appAlpha.isFinite()) {
                            (appAlpha / fadeThreshold).coerceIn(0f, 1f)
                        } else 1f
                        val envelope = angleWeight * handoff * visibility
                        val verticalOffset = abs(fromY)
                        val center = values.center * (1f - verticalOffset) * smoothStep(verticalOffset / .20f)
                        val direction = if (fromY < 0f) fromY - center else fromY + center
                        val strength = values.strength * (if (opening) values.enter else 1.25f * values.exit)
                        val pitch = limitAngle(.31415927f * envelope * direction * strength, .62831855f)
                        val yaw = limitAngle(.19f * envelope * fromX * strength * values.yaw, .38f)
                        val distance = if (values.perspective == 0f) Float.POSITIVE_INFINITY
                            else -1f / (1.2f * values.perspective)
                        val pose = Pose(pitch, yaw, opening, distance)
                        frame.set(Frame(matrix.get(p.thisObject) as Matrix, crop.get(p.thisObject) as Rect, pose))
                    } catch (error: Throwable) {
                        GlassLogger.limited("AppWindowTilt", "frame") { "Native transition retained: $error" }
                    }
                }

                override fun afterHookedMethod(p: MethodHookParam) {
                    val previous = p.getObjectExtra("cpiTiltPrevious") as? Frame
                    if (previous == null) frame.remove() else frame.set(previous)
                }
            })
            hooks += XposedBridge.hookMethod(setMatrix, object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    if (p.hasThrowable()) return
                    val current = frame.get()?.takeIf { p.args[0] === it.native }
                    if (current == null && pitched.isEmpty()) return
                    try {
                        val leash = surface.get(p.thisObject) as? SurfaceControl ?: return
                        val pose = current?.pose
                        val bounds = current?.crop
                        if (pose == null || bounds == null || bounds.isEmpty ||
                            (abs(pose.pitch) < .00001f && abs(pose.yaw) < .00001f)) {
                            if (leash in pitched) {
                                setPerspective.invoke(p.thisObject, identity)
                                pitched.remove(leash)
                            }
                            return
                        }
                        val matrix4x4 = scratch.get()!!
                        buildPerspective(matrix4x4, bounds, pose.pitch, pose.yaw, pose.distance)
                        setPerspective.invoke(p.thisObject, matrix4x4)
                        pitched.add(leash)
                        val count = (if (pose.opening) openFrames else closeFrames).incrementAndGet()
                        if (count == 1L) GlassLogger.i("AppWindowTilt",
                            "Native window perspective active, opening=${pose.opening}")
                    } catch (error: Throwable) {
                        GlassLogger.limited("AppWindowTilt", "matrix") { "Native matrix retained: $error" }
                    }
                }
            })
            installed = true
            GlassLogger.i("AppWindowTilt", "ColorOS window transform hooks installed")
        } catch (error: Throwable) {
            hooks.forEach { it.unhook() }
            GlassLogger.e("AppWindowTilt", "Window transform contract unavailable", error)
        }
    }

    private fun smoothStep(value: Float): Float {
        val t = value.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun limitAngle(angle: Float, limit: Float): Float {
        val start = limit * .8f
        if (abs(angle) <= start) return angle
        val t = ((abs(angle) - start) / ((limit - start) * 2f)).coerceAtMost(1f)
        val capped = start + (limit - start) * t * (2f - t)
        return if (angle < 0f) -capped else capped
    }

    private fun buildPerspective(matrix:FloatArray,crop:Rect,pitch:Float,yaw:Float,distance:Float) {
        // The ColorOS transaction takes a row-major 4×4 in leash-local pixels.
        // Rotate around the crop centre before projecting the resulting plane.
        val x = crop.exactCenterX()
        val y = crop.exactCenterY()
        val pitchSine = sin(pitch)
        val pitchCosine = cos(pitch)
        val yawSine = sin(yaw)
        val yawCosine = cos(yaw)
        val inverseDistance = 1f / (crop.height() * distance)
        matrix[8] = -yawSine
        matrix[9] = yawCosine * pitchSine
        matrix[10] = yawCosine * pitchCosine
        matrix[11] = -matrix[8] * x - matrix[9] * y
        matrix[12] = -matrix[8] * inverseDistance
        matrix[13] = -matrix[9] * inverseDistance
        matrix[14] = -matrix[10] * inverseDistance
        matrix[15] = 1f - matrix[11] * inverseDistance
        matrix[0] = yawCosine + x * matrix[12]
        matrix[1] = yawSine * pitchSine + x * matrix[13]
        matrix[2] = yawSine * pitchCosine + x * matrix[14]
        matrix[3] = x - matrix[0] * x - matrix[1] * y
        matrix[4] = y * matrix[12]
        matrix[5] = pitchCosine + y * matrix[13]
        matrix[6] = -pitchSine + y * matrix[14]
        matrix[7] = y - matrix[4] * x - matrix[5] * y
    }
}
