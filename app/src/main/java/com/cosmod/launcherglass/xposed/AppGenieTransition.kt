package com.cosmod.launcherglass.xposed

import android.animation.ValueAnimator
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.view.SurfaceControl
import android.view.View
import com.cosmod.launcherglass.util.AppGenieMotion
import com.cosmod.launcherglass.util.AppWindowDeformation
import com.cosmod.launcherglass.util.AppGenieTrajectory
import com.cosmod.launcherglass.util.GlassLogger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min

/** Shapes the app surface on the native ColorOS transition timeline. */
internal object AppGenieTransition {
    private data class Config(
        val enabled: Boolean = false, val opening: Boolean = true, val closing: Boolean = true,
        val strength: Double = 1.0, val openResponse: Double = .240, val closeResponse: Double = .336,
        val droplet: Boolean = true, val trajectory: Boolean = false
    )
    @Volatile private var config = Config()
    private var attempted = false
    private var installed = false
    private data class Source(val x: Float, val y: Float, val width: Float)
    private data class RadiusTiming(val opening:Boolean,val start:Long,var last:Long)
    private data class Frame(
        val native: Matrix, val crop: Rect, val screen: RectF, val leash: SurfaceControl, val transaction: Any,
        val record: Any?, val minimized: Double, val iconLeft: Double,
        val iconWidth: Double, val row: Double, val width: Float, val height: Float,
        val opening: Boolean, val damping: Double, val time: Long, val config: Config,
        val droplet: Float, val widget: Boolean, val swipe: Boolean,
        val alternative: Ba31kAppTransitions.Config?, val nativeAlpha: Float,
        var radiusTarget: Any? = null, var replayMatrix: FloatArray? = null
    )
    private val warped = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<SurfaceControl, Boolean>()))
    private val motions = Collections.synchronizedMap(WeakHashMap<Any, AppGenieMotion>())
    private val radiusTiming=WeakHashMap<Any,RadiusTiming>()
    private fun droplet(owner:Any,progress:Float,opening:Boolean,time:Long,config:Config):Float {
        val phase=if(opening)progress else 1f-progress
        if(phase<=.001f || phase>=.999f)return 0f
        val elapsed=synchronized(radiusTiming){
            val previous=radiusTiming[owner]
            val running=previous?.takeIf {it.opening==opening && time-it.last<250_000_000L}
                ?: RadiusTiming(opening,time,time).also {radiusTiming[owner]=it}
            running.last=time
            (time-running.start)*.000001f
        }
        val speed=(if(opening).240/config.openResponse else .336/config.closeResponse).toFloat()
        return AppWindowDeformation.droplet(progress,opening,elapsed,speed)
    }
    private val frame = ThreadLocal<Frame>()
    private val pendingSource = ThreadLocal<Source>()
    private val sources = Collections.synchronizedMap(WeakHashMap<Any, Source>())
    private val paths = Collections.synchronizedMap(WeakHashMap<Any, AppGenieTrajectory>())
    private val widgetTransition = ThreadLocal<Boolean>()
    private val ba31kIconBounds = ThreadLocal<RectF>()
    private val widgetHelpers = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<Any, Boolean>()))
    private val animationDirection = ThreadLocal<Boolean>()
    private val scratch = ThreadLocal.withInitial { FloatArray(16) }
    private val identity = FloatArray(16).apply {
        this[0] = 1f
        this[5] = 1f
        this[10] = 1f
        this[15] = 1f
    }
    private val openFrames = AtomicLong()
    private val closeFrames = AtomicLong()
    private var speedInstalled = false
    private var cornersInstalled = false
    private var launcherContract = "unresolved"
    private var legacyCompatible = false
    private var layers: GenieTransitionLayers? = null
    private val openingTypes = setOf("LAUNCH_FROM_HOME", "PRE_START3_LAUNCH",
        "LAUNCH_FROM_RECENTS", "SWIPE_BREAK_LAUNCH")
    private val closingTypes = setOf("REMOTE_CLOSE_TO_HOME", "PREDICTIVE_BACK_TO_HOME",
        "SWIPE_TO_HOME", "SWIPE_TO_CLIENT_HOME")

    fun update(prefs: Bundle) {
        config = Config(
            enabled = prefs.getBoolean("app_tilt_enabled", false),
            opening = prefs.getBoolean("app_tilt_open", true),
            closing = prefs.getBoolean("app_tilt_close", true),
            strength = prefs.getInt("app_tilt_reference_strength", 100).coerceIn(0, 200) * .01,
            openResponse = .240 / (prefs.getInt("app_genie_open_speed", 100).coerceIn(50, 160) * .01),
            closeResponse = .336 / (prefs.getInt("app_genie_close_speed", 100).coerceIn(50, 160) * .01),
            droplet = prefs.getBoolean("app_genie_droplet", true),
            trajectory = prefs.getBoolean("app_genie_trajectory", false),
        )
    }

    private fun active() = config.enabled || Ba31kAppTransitions.config != null

    fun diagnostics() = "app genie enabled=${config.enabled}, ba31k=${Ba31kAppTransitions.config != null}, installed=$installed, legacyCompatible=$legacyCompatible, speed=$speedInstalled, corners=$cornersInstalled, icon=" + (layers?.iconInstalled == true) + ", iconFrames=${layers?.projectedFrames() ?: 0}, replay=" + (layers?.replayInstalled == true) + ", open=${openFrames.get()}, close=${closeFrames.get()}, contract=$launcherContract"

    fun usesProjectedIcon(): Boolean {
        val values = config
        val alternative = Ba31kAppTransitions.config
        val projecting = if (alternative == null)
            values.enabled && values.strength > 0.0 && (values.opening || values.closing)
        else alternative.strength > 0.0 && (alternative.opening || alternative.closing)
        return installed && layers?.iconInstalled == true && projecting && ValueAnimator.areAnimatorsEnabled()
    }

    fun install(loader: ClassLoader) {
        if (attempted) return
        attempted = true
        val hooks = ArrayList<XC_MethodHook.Unhook>()
        try {
            val (helper,contract) = GenieLauncherContract.window(loader)
            val params = contract.params
            val target = contract.target
            val transaction = contract.surface.transaction
            val builder = contract.surface.builder
            val record = contract.record
            val transform = contract.transform
            val matrix = helper.declaredFields.single {
                !java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == Matrix::class.java
            }.apply { isAccessible = true }
            val crop = helper.getDeclaredField("c").apply { isAccessible = true }
            check(crop.type == Rect::class.java)
            val type = helper.declaredFields.single {
                !java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type.isEnum
            }.apply { isAccessible = true }
            val fullBounds = helper.declaredMethods.single {
                !java.lang.reflect.Modifier.isStatic(it.modifiers) && it.parameterCount == 0 &&
                    it.returnType == Rect::class.java
            }.apply { isAccessible = true }
            val rotating = runCatching {params.getDeclaredMethod("b")}.getOrNull()
                ?: helper.declaredMethods.single {java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                    it.parameterTypes.contentEquals(arrayOf(params)) && it.returnType==Boolean::class.javaPrimitiveType}
            check(rotating.returnType == Boolean::class.javaPrimitiveType)
            val targetLeash = target.getDeclaredField("d").apply { isAccessible = true }
            check(targetLeash.type == SurfaceControl::class.java)
            val canTransform = helper.declaredMethods.single {
                !java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                    it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType, target)) &&
                    it.returnType == Boolean::class.javaPrimitiveType
            }.apply { isAccessible = true }
            val anchorHeight = params.getDeclaredField("d").apply { isAccessible = true }
            check(anchorHeight.type == Float::class.javaPrimitiveType)
            val windowAlpha = params.declaredFields.singleOrNull {
                it.name == "b" && it.type == Float::class.javaPrimitiveType
            }?.apply { isAccessible = true }
            val setMatrix = contract.surface.matrix
            val setPerspective = contract.surface.perspective
            val surface = builder.declaredFields.single {
                !java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == SurfaceControl::class.java
            }.apply { isAccessible = true }
            launcherContract = "${transaction.name} -> ${builder.name}, target=${target.name}, record=${record.name}"
            val transitionLayers = GenieTransitionLayers(setPerspective, identity)
            layers = transitionLayers
            installFrameScope(loader, hooks)
            for(pipeline in GenieLauncherContract.pipelines(loader,params))hooks+=XposedBridge.hookMethod(pipeline,object:XC_MethodHook(){
                override fun beforeHookedMethod(p:MethodHookParam){transitionLayers.beginFrame()}
                override fun afterHookedMethod(p:MethodHookParam){transitionLayers.endFrame()}
            })

            val sourceMethods = setOf("startAppLaunchWindowAnim", "startAppCloseWindowAnim")
            for (name in listOf("OplusLauncherAppTransitionHelper", "OplusLauncherAppTransitionHelperV2")) {
                val owner = XposedHelpers.findClassIfExists("com.android.launcher3.anim.$name", loader) ?: continue
                for (method in owner.declaredMethods) {
                    if (method.name !in sourceMethods) continue
                    val viewIndex = method.parameterTypes.indexOfFirst { View::class.java.isAssignableFrom(it) }
                    if (viewIndex < 0) continue
                    hooks += XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(p: MethodHookParam) {
                            p.setObjectExtra("cpiPreviousWidget", widgetTransition.get())
                            p.setObjectExtra("cpiPreviousSource", pendingSource.get())
                            val view = p.args[viewIndex] as? View
                            val widget = widgetTransition.get() == true || PhotoWidgetMaterial.transitionHost(view) != null
                            widgetTransition.set(widget)
                            if (widget) pendingSource.remove()
                            if (widget || !active() || view == null || !view.isAttachedToWindow ||
                                view.width <= 0 || view.height <= 0) return
                            val location = IntArray(2)
                            view.getLocationOnScreen(location)
                            pendingSource.set(Source(location[0] + view.width * .5f,
                                location[1] + view.height * .5f, view.width.toFloat()))
                        }

                        override fun afterHookedMethod(p: MethodHookParam) {
                            val widget = p.getObjectExtra("cpiPreviousWidget") as? Boolean
                            if (widget == null) widgetTransition.remove() else widgetTransition.set(widget)
                            val source = p.getObjectExtra("cpiPreviousSource") as? Source
                            if (source == null) pendingSource.remove() else pendingSource.set(source)
                        }
                    })
                }
            }
            val sourceCapture = object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    if (widgetTransition.get() == true) widgetHelpers.add(p.thisObject)
                    pendingSource.get()?.let { sources[p.thisObject] = it }
                }
            }
            hooks += XposedBridge.hookAllConstructors(helper, sourceCapture)
            cornersInstalled = runCatching { installDroplet(builder, hooks); true }.getOrElse {
                GlassLogger.limited("AppGenieTransition", "corners") { "Droplet contract unavailable: $it" }
                false
            }

            hooks += XposedBridge.hookMethod(transform, object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    p.setObjectExtra("cpiGeniePrevious", frame.get())
                    frame.remove()
                    val values = config
                    val alternative = Ba31kAppTransitions.config
                    if (!PrefsHelper.enabled || (!values.enabled && alternative == null) || !ValueAnimator.areAnimatorsEnabled()) return
                    try {
                        val widget = widgetTransition.get() == true || p.thisObject in widgetHelpers
                        val parameters = p.args[0]
                        val name = (type.get(p.thisObject) as Enum<*>).name
                        val opening = animationDirection.get() ?: when (name) {
                            in openingTypes -> true
                            in closingTypes -> false
                            else -> return
                        }
                        if (alternative != null && (widget || name == "LAUNCH_FROM_RECENTS")) return
                        if (!(if (opening) alternative?.opening ?: values.opening else alternative?.closing ?: values.closing)) return
                        if ((if(java.lang.reflect.Modifier.isStatic(rotating.modifiers))rotating.invoke(null,parameters) else rotating.invoke(parameters)) == true) return
                        val appTargets = p.args[1] as Array<*>
                        val mode = p.args[2] as Int
                        val preferred = p.args[8]?.takeIf { candidate -> appTargets.any { it === candidate } }
                        val selected = preferred?.takeIf { canTransform.invoke(p.thisObject, mode, it) == true }
                            ?: appTargets.singleOrNull {
                                it != null && canTransform.invoke(p.thisObject, mode, it) == true
                            }
                            ?: return
                        val primary = targetLeash.get(selected) as? SurfaceControl ?: return
                        if (!primary.isValid) return
                        val bounds = fullBounds.invoke(p.thisObject) as Rect
                        val rect = p.args[5] as RectF
                        if (bounds.isEmpty || rect.isEmpty || bounds.width() >= bounds.height()) return
                        val anchor = (anchorHeight.getFloat(parameters) / bounds.height())
                            .takeIf { it in 0.015f..(if (widget) .65f else .30f) } ?: .065f
                        val ratio = rect.height() / bounds.height()
                        val progress = ((ratio - anchor) / (1f - anchor)).coerceIn(0f, 1f)
                        val remaining = (1f - ratio).coerceAtLeast(.2f)
                        val source = (if (alternative != null) ba31kIconBounds.get()
                            ?.let { Source(it.centerX(),it.centerY(),it.width()) } else null)
                            ?: sources[p.thisObject]
                        val fromX = 2f * ((source?.x ?: rect.centerX()) - bounds.exactCenterX()) /
                            (bounds.width() * if (source == null) remaining else 1f)
                        val fromY = 2f * ((source?.y ?: rect.centerY()) - bounds.exactCenterY()) /
                            (bounds.height() * if (source == null) remaining else 1f)
                        if (!ratio.isFinite() || !fromX.isFinite() || !fromY.isFinite()) return
                        val iconWidth = (source?.width ?: anchor * bounds.height()) / bounds.width()
                        val swipe = name == "SWIPE_TO_HOME" || name == "SWIPE_TO_CLIENT_HOME"
                        val time=System.nanoTime()
                        if (alternative?.backdrop == true) {
                            Ba31kTransitionBackdrop.publish(progress.toDouble(),opening,time)
                        }
                        val droplet = if (alternative != null)
                            com.cosmod.launcherglass.util.Ba31kGenieMotion.droplet(progress.toDouble(), opening, alternative.droplet)
                        else if (values.droplet && !widget)
                            droplet(p.args[4] ?: primary,progress,opening,time,values) else 0f
                        if ((alternative?.strength ?: values.strength) == 0.0 && droplet == 0f) return
                        val row = if (alternative == null && !opening && source == null) {
                            fromY + .24f * (1f - abs(fromY)).coerceAtLeast(0f)
                        } else fromY
                        frame.set(Frame(matrix.get(p.thisObject) as Matrix, crop.get(p.thisObject) as Rect,
                            RectF(rect), primary, p.args[3], p.args[4], 1.0 - progress, (fromX + 1.0 - iconWidth) * .5,
                            iconWidth.toDouble(), row.toDouble(), rect.width(), rect.height(), opening,
                            if (swipe) 1.1 else 1.0, time, values, droplet, widget, swipe, alternative,
                            if (alternative == null) Float.NaN else windowAlpha?.getFloat(parameters) ?: Float.NaN))
                    } catch (error: Throwable) {
                        GlassLogger.limited("AppGenieTransition", "frame") { "Native transition retained: $error" }
                    }
                }

                override fun afterHookedMethod(p: MethodHookParam) {
                    val previous = p.getObjectExtra("cpiGeniePrevious") as? Frame
                    if (previous == null) frame.remove() else frame.set(previous)
                }
            })
            hooks += XposedBridge.hookMethod(setMatrix, object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    if (p.hasThrowable()) return
                    val current = frame.get()?.takeIf { p.args[0] === it.native }
                    if (current == null && warped.isEmpty() && !transitionLayers.hasIconWork()) return
                    try {
                        val leash = surface.get(p.thisObject) as? SurfaceControl ?: return
                        if (!leash.isValid) return
                        if (transitionLayers.captureIcon(p.thisObject, leash, p.args[0] as Matrix)) return
                        transitionLayers.clearIcon(p.thisObject, leash)
                        if (current == null || current.leash != leash || current.crop.isEmpty) {
                            if (warped.remove(leash)) setPerspective.invoke(p.thisObject, identity)
                            return
                        }
                        current.radiusTarget = p.thisObject
                        // The record survives changes to the underlying app surface on fast reversals.
                        val owner = current.record ?: leash
                        val alternative = current.alternative
                        val fullEdge = current.minimized / .13
                        val iconEdge = (1.0 - current.minimized) / if (current.swipe) .24 else .13
                        val edge = min(fullEdge, iconEdge).coerceIn(0.0, 1.0)
                        val intensity = current.config.strength * edge * edge * (3.0 - 2 * edge) *
                            if (current.widget) .55 else 1.0
                        val pose = alternative?.let {
                            Ba31kAppTransitions.deformation(owner, it, 1 - current.minimized,
                                current.iconLeft, current.iconWidth, current.row, current.opening, current.swipe, current.time)
                        }
                        val visible = if (alternative == null) {
                            val motion = synchronized(motions) { motions.getOrPut(owner) { AppGenieMotion() } }
                            motion.update(current.minimized, current.iconLeft, current.iconWidth,
                                current.row, intensity, current.opening, current.config.openResponse,
                                current.config.closeResponse, current.damping, current.time)
                                ?.takeIf { it.isVisible(current.width, current.height) }
                        } else null
                        val bounds = current.crop
                        val out = scratch.get()!!
                        val left = bounds.left.toFloat()
                        val top = bounds.top.toFloat()
                        val width = bounds.width().toFloat()
                        val height = bounds.height().toFloat()
                        val perspective = (pose ?: visible)?.let { deformation ->
                            out.also { deformation.writeMatrix(it,left,top,width,height) }.takeIf { it.all(Float::isFinite) }
                        }
                        if (perspective == null) {
                            if (warped.remove(leash)) {
                                setPerspective.invoke(p.thisObject, identity)
                                current.replayMatrix = identity
                            }
                        } else {
                            setPerspective.invoke(p.thisObject, perspective)
                            warped.add(leash)
                            if (current.record != null && transitionLayers.replayInstalled) {
                                current.replayMatrix = perspective.copyOf()
                            }
                        }
                        if (!current.widget) {
                            val shared = if (alternative != null && perspective != null && current.minimized > .20) {
                                val appMatrix = FloatArray(9).also(current.native::getValues)
                                GenieTransitionLayers.SharedProjection(perspective.copyOf(), appMatrix,
                                    1 - current.minimized, current.nativeAlpha)
                            } else null
                            transitionLayers.stageWindow(current.transaction, current.screen,
                                if (perspective != null) visible else null, current.time,
                                shared)
                        }
                        if (perspective == null) return
                        if (alternative != null) {
                            Ba31kAppTransitions.recordFrame(current.opening)
                            return
                        }
                        val count = (if (current.opening) openFrames else closeFrames).incrementAndGet()
                        if (count == 1L) GlassLogger.i("AppGenieTransition",
                            "CherryPick 0.3.20 genie active, opening="+current.opening)
                    } catch (error: Throwable) {
                        GlassLogger.limited("AppGenieTransition", "matrix") { "Native matrix retained: $error" }
                    }
                }
            })
            transitionLayers.installIcon(loader, params, contract.surface, hooks,
                active = {
                    PrefsHelper.enabled && usesProjectedIcon() && widgetTransition.get() != true
                })
            transitionLayers.installReplay(loader, record, hooks, active = { PrefsHelper.enabled && active() }) { owner ->
                frame.get()?.takeIf { it.record === owner }?.let { current ->
                    current.replayMatrix?.let { GenieTransitionLayers.Replay(it, current.time) }
                }
            }
            speedInstalled = installSpeed(loader, type.type, hooks, sourceCapture)
            installed = true
            GlassLogger.i("AppGenieTransition", "ColorOS genie transition hooks installed: $launcherContract")
        } catch (error: Throwable) {
            hooks.forEach { it.unhook() }
            installed = false
            speedInstalled = false
            cornersInstalled = false
            layers = null
            if (android.os.Build.VERSION.SDK_INT == 36) legacyCompatible = Ba31kColorOs16.install(loader)
            if (!legacyCompatible) GlassLogger.e("AppGenieTransition", "Window transform contract unavailable", error)
        }
    }

    private fun installFrameScope(loader: ClassLoader, hooks: MutableList<XC_MethodHook.Unhook>) {
        for (owner in GenieLauncherContract.callbacks(loader)) {
            val opening=owner.name.contains("startAppLaunchWindowAnim")
            if(!opening && !owner.name.contains("startAppCloseWindowAnim"))continue
            val widget = owner.declaredFields.singleOrNull {
                it.name == "\$isWidget" && it.type == Boolean::class.javaPrimitiveType
            }?.apply { isAccessible = true } ?: continue
            val iconRect = owner.declaredFields.firstOrNull {
                it.name in setOf("\$iconRect","\$iconRectF") && it.type == RectF::class.java
            }?.apply { isAccessible = true }
            val fullRect = owner.declaredFields.firstOrNull {
                it.name == "\$screenRectF" && it.type == RectF::class.java
            }?.apply { isAccessible = true }
            val update = owner.declaredMethods.singleOrNull {
                it.name == "onUpdate" && it.parameterTypes.firstOrNull() == RectF::class.java
            } ?: continue
            hooks += XposedBridge.hookMethod(update, object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    if (active()) {
                        layers?.beginFrame()
                        p.setObjectExtra("cpiGenieFrameScope", true)
                    }
                    p.setObjectExtra("cpiPreviousWidget", widgetTransition.get())
                    p.setObjectExtra("cpiPreviousDirection", animationDirection.get())
                    widgetTransition.set(widget.getBoolean(p.thisObject))
                    animationDirection.set(opening)
                    val values = config
                    if (Ba31kAppTransitions.config != null) {
                        p.setObjectExtra("cpiBa31kIconScope",true)
                        p.setObjectExtra("cpiBa31kPreviousIcon",ba31kIconBounds.get())
                        val icon = (iconRect?.get(p.thisObject) as? RectF)?.takeUnless { it.isEmpty }
                        if (icon == null) ba31kIconBounds.remove() else ba31kIconBounds.set(icon)
                    }
                    if (PrefsHelper.enabled && Ba31kAppTransitions.config == null && values.enabled && values.trajectory &&
                        (if (opening) values.opening else values.closing) &&
                        widget.getBoolean(p.thisObject) == false && ValueAnimator.areAnimatorsEnabled()) {
                        val current = p.args[0] as? RectF
                        val icon = iconRect?.get(p.thisObject) as? RectF
                        val full = fullRect?.get(p.thisObject) as? RectF
                        if (current != null && icon != null && full != null && !icon.isEmpty &&
                            !full.isEmpty && full.height() > icon.height() &&
                            NativeIconAccess.field(p.thisObject, "\$needsRotation") != true) {
                            val progress = (current.height() - icon.height()) / (full.height() - icon.height())
                            val path = synchronized(paths) { paths.getOrPut(p.thisObject) { AppGenieTrajectory() } }
                            path.sample(progress, opening)?.let { sample ->
                                val x = icon.centerX() + (full.centerX() - icon.centerX()) * sample.x
                                val y = icon.centerY() + (full.centerY() - icon.centerY()) * sample.y
                                // Native callbacks retain this rectangle in delayed icon/window updaters.
                                // Never mutate and restore the spring's shared rectangle after onUpdate.
                                p.args[0]=RectF(current).apply {
                                    offset((x-current.centerX())*sample.blend,(y-current.centerY())*sample.blend)
                                }
                                GlassLogger.limited("AppGenieTransition","trajectory",2000){"Native trajectory active, opening=$opening, progress=$progress"}
                            }
                        }
                    }
                }

                override fun afterHookedMethod(p: MethodHookParam) {
                    if (p.getObjectExtra("cpiGenieFrameScope") == true) layers?.endFrame()
                    widgetTransition.set(p.getObjectExtra("cpiPreviousWidget") as? Boolean)
                    animationDirection.set(p.getObjectExtra("cpiPreviousDirection") as? Boolean)
                    if (p.getObjectExtra("cpiBa31kIconScope") == true) {
                        val previous = p.getObjectExtra("cpiBa31kPreviousIcon") as? RectF
                        if (previous == null) ba31kIconBounds.remove() else ba31kIconBounds.set(previous)
                    }
                }
            })
        }
    }

    private fun installDroplet(builder: Class<*>, hooks: MutableList<XC_MethodHook.Unhook>) {
        val float = Float::class.javaPrimitiveType
        val methods = builder.declaredMethods.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
        val rounded = methods.single {
            it.parameterTypes.contentEquals(arrayOf(float, float, Int::class.javaPrimitiveType)) &&
                (it.returnType == builder || it.returnType == Void.TYPE)
        }
        val radius = methods.singleOrNull {
            it.name == "d" && it.parameterTypes.contentEquals(arrayOf(float)) &&
                (it.returnType == builder || it.returnType == Void.TYPE)
        }
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                val current = frame.get() ?: return
                if (current.radiusTarget !== p.thisObject || current.droplet <= 0f || current.crop.isEmpty) return
                current.radiusTarget = null
                val native = p.args[0] as Float
                val limit = min(current.crop.width(), current.crop.height()) * .5f
                if (native.isFinite() && native >= 0f && native < limit) {
                    p.args[0] = native + (limit - native) * current.droplet
                }
            }
        }
        hooks += XposedBridge.hookMethod(rounded, hook)
        if (radius != null) hooks += XposedBridge.hookMethod(radius, hook)
    }

    private fun installSpeed(loader: ClassLoader, animationType: Class<*>,
                             hooks: MutableList<XC_MethodHook.Unhook>, sourceCapture: XC_MethodHook): Boolean {
        // 17.3.12 sets up all channels through this setter before starting the shared spring.
        val spring = XposedHelpers.findClassIfExists("com.android.quickstep.util.animation.CustomRectFSpringAnim", loader)
            ?: return false
        val channel = XposedHelpers.findClassIfExists(spring.name + "\$SpringChannel", loader) ?: return false
        val kind = spring.declaredFields.singleOrNull { it.type == animationType }
            ?.apply { isAccessible = true } ?: return false
        val float = Float::class.javaPrimitiveType
        val setter = spring.declaredMethods.singleOrNull {
            it.returnType == Void.TYPE &&
                it.parameterTypes.contentEquals(arrayOf(channel, float, float))
        } ?: return false
        hooks += XposedBridge.hookAllConstructors(spring, sourceCapture)
        hooks += XposedBridge.hookMethod(setter, object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                val values = config
                val alternative = Ba31kAppTransitions.config
                if (!PrefsHelper.enabled || (!values.enabled && alternative == null) || !ValueAnimator.areAnimatorsEnabled() ||
                    widgetTransition.get() == true || p.thisObject in widgetHelpers) return
                val name = (kind.get(p.thisObject) as? Enum<*>)?.name ?: return
                val swipe = name == "SWIPE_TO_HOME" || name == "SWIPE_TO_CLIENT_HOME"
                if ((swipe && alternative == null) || (alternative != null && name == "LAUNCH_FROM_RECENTS")) return
                if (alternative != null) {
                    if (name in openingTypes && alternative.opening)
                        Ba31kAppTransitions.tuneSpring(p.args,alternative,opening=true,swipe=false)
                    else if (name in closingTypes && alternative.closing)
                        Ba31kAppTransitions.tuneSpring(p.args,alternative,opening=false,swipe=swipe)
                    return
                }
                val response = when {
                    name in openingTypes && values.opening -> values.openResponse
                    name in closingTypes && values.closing -> values.closeResponse
                    else -> return
                }
                val frequency = 2 * PI / response
                p.args[1] = (frequency * frequency).toFloat()
                p.args[2] = 1f
            }
        })
        return true
    }
}
