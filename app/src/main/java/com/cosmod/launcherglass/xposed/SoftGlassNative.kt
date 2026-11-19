package com.cosmod.launcherglass.xposed

import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Canvas
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import com.cosmod.launcherglass.ui.SettingsProvider
import com.cosmod.launcherglass.ui.SoftGlassSettings
import com.cosmod.launcherglass.ui.SoftGlassSettings.Parameter as P
import com.cosmod.launcherglass.ui.SoftGlassSettings.Profile
import com.cosmod.launcherglass.ui.SoftGlassSettings.Surface
import com.cosmod.launcherglass.util.GlassLogger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap
import kotlin.math.cos
import kotlin.math.sin

/** Adapts the vendor's existing backdrop programs within the requested surfaces. */
internal object SoftGlassNative {
    private var systemUi: Session? = null
    private var attemptedSystemUi = false
    private val liveActivityLoaders = WeakHashMap<ClassLoader, Boolean>()

    fun liveActivityLoader(loader: ClassLoader?) {
        if (loader == null || liveActivityLoaders.put(loader, true) != null) return
        systemUi?.let { session ->
            runCatching { session.installMaterial(loader) }.onFailure { error ->
                GlassLogger.limited("SoftGlass", "activities") { "Native activity adapter unavailable: ${error.javaClass.simpleName}" }
            }
        }
    }

    fun updateSystemUi(context: Context, loader: ClassLoader?, prefs: Bundle) {
        if (Build.VERSION.SDK_INT < 37 || loader == null) return
        val profiles = readProfiles(prefs, false)
        if (systemUi == null && profiles.isNotEmpty() && !attemptedSystemUi) {
            attemptedSystemUi = true
            systemUi = create(context, loader, false)
            systemUi?.let { session ->
                liveActivityLoaders.keys.toList().forEach { activityLoader ->
                    runCatching { session.installMaterial(activityLoader) }
                }
            }
        }
        val session = systemUi ?: return
        session.update(profiles)
    }

    fun replaces(surface: Surface): Boolean = systemUi?.profiles?.containsKey(surface) == true
    fun lockedActivityProfile(): Profile? = systemUi?.let { session ->
        if (session.locked()) session.profiles[Surface.LOCK] else null
    }

    fun activityUniforms(shader: RuntimeShader, view: View): Boolean =
        systemUi?.activityUniforms(shader, view) == true

    fun installSidebar(loader: ClassLoader) {
        if (Build.VERSION.SDK_INT < 37) return
        XposedHelpers.findAndHookMethod(Application::class.java, "attach", Context::class.java, object : XC_MethodHook() {
            private var bound = false

            override fun afterHookedMethod(param: MethodHookParam) {
                val app = param.thisObject as Application
                if (bound || app.packageName != SoftGlassSettings.SIDEBAR_PACKAGE) return
                bound = true
                var session: Session? = null
                val uri = SettingsProvider.CONTENT_URI
                val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        val preferences = runCatching { app.contentResolver.call(uri, SettingsProvider.METHOD_GET_ALL, null, null) }
                            .getOrNull() ?: return
                        val profiles = readProfiles(preferences, true)
                        if (session == null && profiles.isNotEmpty()) session = create(app, loader, true)
                        session?.update(profiles)
                    }
                }
                runCatching {
                    app.contentResolver.registerContentObserver(uri, false, observer)
                    observer.onChange(false)
                }.onFailure { GlassLogger.e("SoftGlass", "Sidebar preferences unavailable", it) }
            }
        })
    }

    private fun readProfiles(prefs: Bundle, sidebar: Boolean) = Surface.entries
        .filter { (it == Surface.SIDEBAR) == sidebar }
        .mapNotNull { surface -> SoftGlassSettings.read(prefs, surface)?.let { surface to it } }.toMap()

    private fun create(context: Context, loader: ClassLoader, sidebar: Boolean): Session? = runCatching {
        Session(context, sidebar).also { it.install(loader) }
    }.onFailure { GlassLogger.e("SoftGlass", "Native material unavailable in ${context.packageName}", it) }.getOrNull()

    private class Session(private val context: Context, private val sidebar: Boolean) {
        @Volatile var profiles: Map<Surface, Profile> = emptyMap()
            private set
        private val lock = Any()
        private val shaders = WeakHashMap<RuntimeShader, Applied?>()
        private val effects = WeakHashMap<RenderEffect, EffectState>()
        private val nodes = WeakHashMap<RenderNode, Binding>()
        private val drawables = WeakHashMap<Drawable, Surface>()
        private val foreground = WeakHashMap<Drawable, Boolean>()
        private val sources = LruCache<String, String>(16)
        private val materialBuilders = hashSetOf<Method>()
        private val materialDraws = hashSetOf<Method>()
        private val keyguard = context.getSystemService(KeyguardManager::class.java)
        private val checking = ThreadLocal<Boolean>()
        private val rendered = mutableSetOf<Surface>()
        private val main = Handler(Looper.getMainLooper())
        private val legacyActivities = WeakHashMap<View, Applied>()
        private var tiltX = 0f
        private var tiltY = 0f
        private val tilt = SoftGlassTilt(main, { context }, ::hasVisibleTilt) { x, y ->
            tiltX = x
            tiltY = y
            drawables.keys.toList().filter { host(it)?.isShown == true }.forEach(Drawable::invalidateSelf)
            legacyActivities.keys.toList().filter(View::isShown).forEach(View::postInvalidateOnAnimation)
        }

        private data class EffectState(val shader: RuntimeShader, val state: Applied?)
        private data class Binding(val effect: EffectState, val setter: Method, val mode: Int?)
        private data class Applied(val surface: Surface?, val profile: Profile?, val dark: Boolean,
            val density: Float, val width: Int, val height: Int, val x: Float, val y: Float)

        fun update(values: Map<Surface, Profile>) {
            if (profiles == values) return
            profiles = values
            if (Surface.LOCK !in values) legacyActivities.clear()
            if (values.values.none(::tracksTilt)) tilt.stop()
            drawables.keys.toList().forEach(Drawable::invalidateSelf)
        }

        private fun tracksTilt(profile: Profile) = profile.tilt && profile[P.RESPONSE] > 0f &&
            profile[P.SHINE] > 0f && profile[P.WIDTH] > 0f

        fun activityUniforms(shader: RuntimeShader, view: View): Boolean {
            val profile = profiles[Surface.LOCK] ?: return false
            val metrics = view.resources.displayMetrics
            val dark = view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            val moves = tracksTilt(profile)
            val state = Applied(Surface.LOCK, profile, dark, metrics.density, metrics.widthPixels, metrics.heightPixels,
                if (moves) tiltX else 0f, if (moves) tiltY else 0f)
            apply(shader, state, fullMix = true)
            if (moves) {
                legacyActivities[view] = state
                tilt.request()
            } else legacyActivities.remove(view)
            return true
        }

        private fun hasVisibleTilt() = legacyActivities.any { (view, state) ->
            state.profile?.let(::tracksTilt) == true && locked() && view.isShown && view.windowVisibility == View.VISIBLE
        } || drawables.any { (drawable, surface) ->
            profiles[surface]?.let(::tracksTilt) == true && drawable.isVisible &&
                host(drawable)?.let { it.isShown && it.windowVisibility == View.VISIBLE } == true
        }

        private fun host(drawable: Drawable): View? {
            var callback = drawable.callback
            repeat(10) {
                if (callback is View) return callback as View
                callback = (callback as? Drawable)?.callback ?: return null
            }
            return null
        }

        private fun isForeground(drawable: Drawable): Boolean {
            if (synchronized(lock) { foreground.containsKey(drawable) }) return true
            var callback = drawable.callback
            repeat(10) {
                val wrapper = callback as? Drawable ?: return false
                if (synchronized(lock) { foreground.containsKey(wrapper) }) return true
                callback = wrapper.callback
            }
            return false
        }

        private fun owner(view: View): Surface? {
            if (sidebar) return Surface.SIDEBAR
            val onLockscreen = locked()
            var current: View? = view
            repeat(12) {
                val child = current ?: return null
                val name = child.javaClass.name.lowercase(java.util.Locale.ROOT)
                if ("headsuppinned" in name || ".volume." in name) return null
                if ("seedling" in name) {
                    if (".capsule." in name) return null
                    if (".card." in name) return Surface.LOCK.takeIf { onLockscreen }
                }
                if (onLockscreen && ("keyguardmedia" in name || "lockscreenmedia" in name)) return Surface.LOCK
                if (name.endsWith("notificationbackgroundview") || name.endsWith("expandablenotificationrow")) {
                    val row = if (name.endsWith("expandablenotificationrow")) child else child.parent
                    if (row != null && NativeIconAccess.call(row, "isHeadsUp") == true) return null
                    return if (onLockscreen) Surface.LOCK else Surface.SHADE
                }
                if (".qs." in name || "controlcenter" in name || "quicksettings" in name) return Surface.CONTROL
                if (!onLockscreen && ("dismissview" in name || "notificationstackscrolllayout" in name))
                    return Surface.SHADE
                current = child.parent as? View
            }
            return null
        }

        private var sampledLock = false
        private var lockSampleAt = -500L
        fun locked(): Boolean {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lockSampleAt >= 200L) {
                sampledLock = keyguard?.isKeyguardLocked == true
                lockSampleAt = now
            }
            return sampledLock
        }

        fun install(loader: ClassLoader) {
            installMaterial(loader)
            XposedBridge.hookMethod(RuntimeShader::class.java.getDeclaredConstructor(String::class.java), object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!param.hasThrowable() && checking.get() != true)
                        track(param.thisObject, param.args[0])
                }
            })
            runCatching { loader.loadClass("com.oplus.graphics.OplusRuntimeShader") }.getOrNull()?.declaredMethods
                ?.filter { it.name == "createRuntimeShader" && it.parameterTypes.firstOrNull() == String::class.java &&
                    it.returnType == RuntimeShader::class.java }
                ?.forEach { method -> XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) { track(param.result, param.args[0]) }
                }) }
            installBindings()
            if (!sidebar) installForeground(loader)
        }

        fun installMaterial(loader: ClassLoader) {
            val builder = listOf("com.oplus.posteffect.agsl.BlurDrawableShaderBaseStringKt", "com.oplus.posteffect.agsl.a")
                .firstNotNullOfOrNull { name ->
                    runCatching { loader.loadClass(name) }.getOrNull()?.declaredMethods?.singleOrNull {
                        val types = it.parameterTypes
                        Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE && types.size == 6 &&
                            types[0] == StringBuilder::class.java && types[1] == Integer.TYPE && types[2] == Integer.TYPE &&
                            types[4] == java.lang.Boolean.TYPE && types[5] == List::class.java &&
                            (name.endsWith("Kt") || types[3].name == "com.oplus.posteffect.params.CornerType")
                    }
                } ?: error("ColorOS post-effect shader builder missing")
            val drawable = loader.loadClass("com.oplus.posteffect.drawable.BaseDrawable")
            val shaderOwner = drawable.declaredFields.single { it.type.name == "com.oplus.posteffect.agsl.DrawableShader" }
                .apply { isAccessible = true }
            val program = shaderOwner.type.declaredFields.single { it.type == RuntimeShader::class.java }.apply { isAccessible = true }
            val nodeFields = drawable.declaredFields.filter { it.type == RenderNode::class.java }.onEach { it.isAccessible = true }
            val draw = drawable.getDeclaredMethod("draw", Canvas::class.java)

            if (materialBuilders.add(builder)) XposedBridge.hookMethod(builder, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.hasThrowable() || profiles.isEmpty()) return
                    val buffer = param.args[0] as StringBuilder
                    val source = buffer.toString()
                    val modified = synchronized(sources) {
                        sources.get(source) ?: validate(source).also { sources.put(source, it) }
                    }
                    if (modified.isNotEmpty()) {
                        buffer.setLength(0)
                        buffer.append(modified)
                    }
                }
            })
            if (materialDraws.add(draw)) XposedBridge.hookMethod(draw, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (Looper.myLooper() != main.looper || !(param.args[0] as Canvas).isHardwareAccelerated) return
                    val plate = param.thisObject as Drawable
                    runCatching {
                        val native = shaderOwner.get(plate) ?: return
                        val shader = program.get(native) as? RuntimeShader ?: return
                        if (!synchronized(lock) { shaders.containsKey(shader) }) return
                        val view = host(plate) ?: return
                        val surface = if (isForeground(plate)) null else owner(view)
                        val profile = surface?.let(profiles::get)
                        if (surface != null && profile != null) drawables[plate] = surface else drawables.remove(plate)
                        val metrics = view.resources.displayMetrics
                        val dark = view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
                        val moves = profile?.let(::tracksTilt) == true
                        if (moves) tilt.request()
                        val state = Applied(surface, profile, dark, metrics.density, metrics.widthPixels, metrics.heightPixels,
                            if (moves) tiltX else 0f, if (moves) tiltY else 0f)
                        val previous = synchronized(lock) { shaders[shader] }
                        if (previous != state) {
                            apply(shader, state)
                            synchronized(lock) { shaders[shader] = state }
                        }
                        nodeFields.mapNotNull { it.get(plate) as? RenderNode }.forEach { node ->
                            val binding = synchronized(lock) { nodes[node] }
                            if (binding?.effect?.shader === shader && binding.effect.state != state) rebind(node, binding)
                        }
                        if (surface != null && profile != null && rendered.add(surface))
                            GlassLogger.i("SoftGlass", "Native material applied: $surface")
                    }.onFailure { error ->
                        GlassLogger.limited("SoftGlass", "draw") { "Native material retained: ${error.javaClass.simpleName}" }
                    }
                }
            })
        }

        private fun validate(source: String): String {
            val modified = SoftGlassShader.extend(source) ?: return ""
            checking.set(true)
            return try {
                RuntimeShader(modified)
                modified
            } catch (error: RuntimeException) {
                GlassLogger.limited("SoftGlass", "shader") { "Unsupported post-effect shader: ${error.message}" }
                ""
            } finally {
                checking.remove()
            }
        }

        private fun track(value: Any?, source: Any?) {
            if (value !is RuntimeShader || source !is String || !SoftGlassShader.owns(source)) return
            synchronized(lock) { if (!shaders.containsKey(value)) shaders[value] = null }
        }

        private fun installBindings() {
            val factory = RenderEffect::class.java.getMethod("createRuntimeShaderEffect", RuntimeShader::class.java, String::class.java)
            XposedBridge.hookMethod(factory, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val effect = param.result as? RenderEffect ?: return
                    if (param.args[1] != SoftGlassShader.INPUT) return
                    val shader = param.args[0] as RuntimeShader
                    synchronized(lock) {
                        if (shaders.containsKey(shader)) effects[effect] = EffectState(shader, shaders[shader])
                    }
                }
            })
            RenderNode::class.java.declaredMethods.filter {
                it.name == "setBackdropRenderEffect" && it.parameterTypes.firstOrNull() == RenderEffect::class.java &&
                    (it.parameterCount == 1 || it.parameterTypes.contentEquals(arrayOf(RenderEffect::class.java, Integer.TYPE)))
            }.forEach { setter ->
                setter.isAccessible = true
                XposedBridge.hookMethod(setter, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.hasThrowable()) return
                        val node = param.thisObject as RenderNode
                        synchronized(lock) {
                            val effect = (param.args[0] as? RenderEffect)?.let(effects::get)
                            if (effect == null) nodes.remove(node)
                            else nodes[node] = Binding(effect, setter, param.args.getOrNull(1) as? Int)
                        }
                    }
                })
            }
        }

        private fun installForeground(loader: ClassLoader) {
            val manager = runCatching { loader.loadClass("com.oplus.systemui.qs.base.util.QsSeekBarBlurManager") }.getOrNull() ?: return
            manager.declaredMethods.filter { it.name == "getSeekBarActiveDrawable" || it.name == "applySeekBarActiveBlurConfig" }
                .forEach { method ->
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val fill = param.result as? Drawable ?: param.args.firstOrNull { it is Drawable } as? Drawable ?: return
                            synchronized(lock) { foreground[fill] = true }
                        }
                    })
                }
        }

        private fun rebind(node: RenderNode, binding: Binding) {
            val effect = RenderEffect.createRuntimeShaderEffect(binding.effect.shader, SoftGlassShader.INPUT)
            if (binding.mode == null) binding.setter.invoke(node, effect) else binding.setter.invoke(node, effect, binding.mode)
        }

        private fun apply(shader: RuntimeShader, state: Applied, fullMix: Boolean = false) {
            val profile = state.profile
            if (profile == null) {
                shader.setFloatUniform(SoftGlassShader.UNIFORM, 0f, 0f, 0f)
                return
            }
            shader.setFloatUniform("onbPanelSurface", state.surface!!.shaderRole, if (state.dark) .30f else .63f)
            shader.setFloatUniform("onbOptics", if (fullMix) 1f else profile[P.STRENGTH], profile[P.BLUR], profile[P.WIDTH], profile[P.SHINE])
            shader.setFloatUniform("onbToneControls", profile[P.REFRACTION], profile[P.SATURATION], profile[P.EXPOSURE])
            shader.setFloatUniform("onbTint", profile[P.WARMTH], profile[P.CONTRAST])
            shader.setFloatUniform("onbDimming", profile[P.DIMMING])
            shader.setFloatUniform("onbKeepWhite", if (profile.white) 1f else 0f)
            shader.setFloatUniform("onbSelectionState", if (state.surface == Surface.CONTROL) 1f else 0f)
            val angle = Math.toRadians(profile.value(P.ANGLE).toDouble())
            val c = cos(angle).toFloat()
            val s = sin(angle).toFloat()
            shader.setFloatUniform("onbLampOffset", .4f * (1f - c) + .6f * s + state.x * profile[P.RESPONSE],
                .6f * (1f - c) - .4f * s + state.y * profile[P.RESPONSE])
            shader.setFloatUniform(SoftGlassShader.UNIFORM, state.density, state.width.toFloat(), state.height.toFloat())
        }
    }
}
