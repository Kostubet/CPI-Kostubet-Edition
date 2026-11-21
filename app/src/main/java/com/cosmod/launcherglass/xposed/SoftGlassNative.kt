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
import androidx.annotation.RequiresApi
import com.cosmod.launcherglass.ui.SettingsProvider
import com.cosmod.launcherglass.ui.SoftGlassSettings
import com.cosmod.launcherglass.ui.SoftGlassSettings.Parameter as P
import com.cosmod.launcherglass.ui.SoftGlassSettings.Profile
import com.cosmod.launcherglass.ui.SoftGlassSettings.Surface
import com.cosmod.launcherglass.util.GlassLogger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap

/** Adapts the vendor's existing backdrop programs within the requested surfaces. */
internal object SoftGlassNative {
    private var systemUi: Session? = null
    private var attemptedSystemUi = false
    private val liveActivityLoaders = WeakHashMap<ClassLoader, Boolean>()
    private val main = Handler(Looper.getMainLooper())

    fun liveActivityLoader(loader: ClassLoader?) {
        if (Build.VERSION.SDK_INT < 33 || loader == null) return
        if (Looper.myLooper() != main.looper) {
            main.post { liveActivityLoader(loader) }
            return
        }
        if (liveActivityLoaders.put(loader, true) != null) return
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
        session.update(profiles, bundleAllowsVolume(prefs))
    }

    private fun bundleAllowsVolume(prefs: Bundle) =
        prefs.getBoolean(com.cosmod.launcherglass.ui.FeatureSections.key("VOLUME"), true) &&
            !prefs.getBoolean("volume_glass_enabled")

    fun replacesVolume(): Boolean = Build.VERSION.SDK_INT >= 33 && systemUi?.let {
        Surface.CONTROL in it.profiles && it.panelTargets.supportsVolume && it.followControlForVolume
    } == true

    fun diagnostics(): String {
        if (Build.VERSION.SDK_INT >= 33) systemUi?.let { return it.diagnostics() }
        return "Soft Glass: inactive"
    }

    fun replaces(surface: Surface): Boolean = Build.VERSION.SDK_INT >= 33 && systemUi?.profiles?.containsKey(surface) == true
    fun lockedActivityProfile(): Profile? = if (Build.VERSION.SDK_INT >= 33) systemUi?.let { session ->
        session.profiles[Surface.LOCK]?.takeIf { session.locked() }
    } else null

    @RequiresApi(33)
    fun activityUniforms(plate: SoftGlassShader.Plate, view: View): Boolean =
        systemUi?.activityUniforms(plate, view) == true

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
                        if (Build.VERSION.SDK_INT < 33) return
                        val preferences = runCatching { app.contentResolver.call(uri, SettingsProvider.METHOD_GET_ALL, null, null) }
                            .getOrNull() ?: return
                        val profiles = readProfiles(preferences, true)
                        if (session == null && profiles.isNotEmpty()) session = create(app, loader, true)
                        session?.update(profiles)
                    }
                }
                runCatching {
                    val sidebarProcess = Application.getProcessName()
                    com.cosmod.launcherglass.util.HostControl.install(app, "sidebar", details = {
                        "process=$sidebarProcess, material=${session != null}"
                    })
                    app.contentResolver.registerContentObserver(uri, false, observer)
                    observer.onChange(false)
                }.onFailure { GlassLogger.e("SoftGlass", "Sidebar preferences unavailable", it) }
            }
        })
    }

    private fun readProfiles(prefs: Bundle, sidebar: Boolean) = Surface.entries
        .filter { (it == Surface.SIDEBAR) == sidebar }
        .mapNotNull { surface -> SoftGlassSettings.read(prefs, surface)?.let { surface to it } }.toMap()

    @RequiresApi(33)
    private fun create(context: Context, loader: ClassLoader, sidebar: Boolean): Session? = runCatching {
        Session(context, sidebar).also { it.install(loader) }
    }.onFailure { GlassLogger.e("SoftGlass", "Native material unavailable in ${context.packageName}", it) }.getOrNull()

    @RequiresApi(33)
    private class Session(private val context: Context, private val sidebar: Boolean) {
        @Volatile var profiles: Map<Surface, Profile> = emptyMap()
            private set
        var followControlForVolume = true
            private set
        private val lock = Any()
        private class ShaderState(var applied: Applied?)
        private val shaders = WeakHashMap<RuntimeShader, ShaderState>()
        private val effects = WeakHashMap<RenderEffect, EffectState>()
        private val nodes = WeakHashMap<RenderNode, Binding>()
        private val drawables = WeakHashMap<Drawable, Surface>()
        private val rebound = WeakHashMap<RuntimeShader, Pair<Applied?, WeakReference<RenderEffect>>>()
        private val sliderMaterial = if (sidebar) null else SoftGlassSliderMaterial(context)
        val panelTargets = SoftGlassPanelTargets({ Surface.CONTROL in profiles }) { canvas, drawable, target ->
            if (Looper.myLooper() != Looper.getMainLooper()) return@SoftGlassPanelTargets
            val view = target.view.get() ?: host(drawable) ?: return@SoftGlassPanelTargets
            val state = state(view, target) ?: return@SoftGlassPanelTargets
            sliderMaterial?.draw(canvas, drawable, view, state.profile, state.role, target.radius, state.dark, state.x, state.y)
        }
        private val sources = LruCache<String, String>(16)
        private val materialBuilders = hashSetOf<Method>()
        private val materialDraws = hashSetOf<Method>()
        private val keyguard = context.getSystemService(KeyguardManager::class.java)
        private val checking = ThreadLocal<Boolean>()
        private val rendered = mutableSetOf<Float>()
        private val main = Handler(Looper.getMainLooper())
        private val legacyActivities = WeakHashMap<View, Unit>()
        private val ownerRoles = WeakHashMap<Class<*>, OwnerRole>()
        private enum class OwnerRole { SKIP, ACTIVITY, LOCK_MEDIA, ROW, ROW_BACKGROUND, CONTROL, SHADE, PARENT }
        private var tiltX = 0f
        private var tiltY = 0f
        private val tilt = SoftGlassTilt(main, { context }, ::hasVisibleTilt) { x, y ->
            tiltX = x
            tiltY = y
            for ((drawable, surface) in drawables) {
                if (profiles[surface]?.let(::tracksTilt) == true && drawable.isVisible &&
                    host(drawable)?.let { it.isShown && it.windowVisibility == View.VISIBLE } == true) drawable.invalidateSelf()
            }
            for (view in legacyActivities.keys) if (view.isShown && view.windowVisibility == View.VISIBLE) {
                view.postInvalidateOnAnimation()
            }
        }

        private data class EffectState(val shader: RuntimeShader, val state: Applied?)
        private data class Binding(val effect: EffectState, val setter: Method, val mode: Int?)
        private data class Applied(val surface: Surface, val profile: Profile, val dark: Boolean,
            val density: Float, val width: Int, val height: Int, val x: Float, val y: Float, val role: Float)

        fun update(values: Map<Surface, Profile>, followVolume: Boolean = followControlForVolume) {
            val previous = profiles
            val volumeChanged = followControlForVolume != followVolume
            if (previous == values && !volumeChanged) return
            profiles = values
            followControlForVolume = followVolume
            if (Surface.LOCK !in values) legacyActivities.clear()
            if (Surface.CONTROL !in values || volumeChanged) sliderMaterial?.clear()
            if (values.values.none(::tracksTilt)) tilt.stop()
            val redraw = drawables.entries.mapNotNull { (drawable, surface) ->
                if (previous[surface] != values[surface] || (surface == Surface.CONTROL && volumeChanged)) drawable
                else null
            }
            redraw.forEach(Drawable::invalidateSelf)
        }

        fun diagnostics(): String =
            "Soft Glass: profiles=${profiles.keys}, slider=${sliderMaterial?.diagnostics()}"

        private fun tracksTilt(profile: Profile) = profile.tilt && profile[P.RESPONSE] > 0f &&
            profile[P.SHINE] > 0f && profile[P.WIDTH] > 0f

        fun activityUniforms(plate: SoftGlassShader.Plate, view: View): Boolean {
            val profile = profiles[Surface.LOCK] ?: return false
            val dark = view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            val moves = tracksTilt(profile)
            plate.apply(profile, Surface.LOCK.shaderRole, dark, view.resources.displayMetrics,
                if (moves) tiltX else 0f, if (moves) tiltY else 0f)
            if (moves) {
                legacyActivities[view] = Unit
                tilt.request()
            } else legacyActivities.remove(view)
            return true
        }

        private fun hasVisibleTilt() = (profiles[Surface.LOCK]?.let(::tracksTilt) == true &&
            legacyActivities.isNotEmpty() && locked() &&
            legacyActivities.keys.any { it.isShown && it.windowVisibility == View.VISIBLE }) || drawables.any { (drawable, surface) ->
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

        private fun state(view: View?, target: SoftGlassPanelTargets.Owner?): Applied? {
            val current = profiles
            if (current.isEmpty()) return null
            val role = target?.kind
            val surface = when {
                role == SoftGlassPanelTargets.Kind.FILL -> null
                (role == SoftGlassPanelTargets.Kind.VOLUME_PANEL ||
                    role == SoftGlassPanelTargets.Kind.VOLUME_MORE) && !followControlForVolume -> null
                role != null -> Surface.CONTROL
                sidebar -> Surface.SIDEBAR
                view != null -> owner(view)
                else -> null
            }
            val profile = current[surface ?: return null] ?: return null
            val resources = view?.resources ?: context.resources
            val metrics = resources.displayMetrics
            val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            val moves = tracksTilt(profile)
            if (moves) tilt.request()
            return Applied(surface, profile, dark, metrics.density, metrics.widthPixels, metrics.heightPixels,
                if (moves) tiltX else 0f, if (moves) tiltY else 0f, role?.role ?: surface.shaderRole)
        }

        private fun owner(view: View): Surface? {
            if (sidebar) return Surface.SIDEBAR
            val onLockscreen = locked()
            var current: View? = view
            repeat(12) {
                val child = current ?: return null
                when (val role = ownerRoles.getOrPut(child.javaClass) { classifyOwner(child.javaClass) }) {
                    OwnerRole.SKIP -> return null
                    OwnerRole.ACTIVITY -> return Surface.LOCK.takeIf { onLockscreen }
                    OwnerRole.LOCK_MEDIA -> if (onLockscreen) return Surface.LOCK
                    OwnerRole.ROW, OwnerRole.ROW_BACKGROUND -> {
                        val row = if (role == OwnerRole.ROW) child else child.parent
                        if (row != null && NativeIconAccess.call(row, "isHeadsUp") == true) return null
                        return if (onLockscreen) Surface.LOCK else Surface.SHADE
                    }
                    OwnerRole.CONTROL -> return Surface.CONTROL
                    OwnerRole.SHADE -> if (!onLockscreen) return Surface.SHADE
                    OwnerRole.PARENT -> Unit
                }
                current = child.parent as? View
            }
            return null
        }

        private fun classifyOwner(type: Class<*>): OwnerRole {
            val name = type.name.lowercase(java.util.Locale.ROOT)
            return when {
                "headsuppinned" in name || ".volume." in name ||
                    ("seedling" in name && ".capsule." in name) -> OwnerRole.SKIP
                "seedling" in name && ".card." in name -> OwnerRole.ACTIVITY
                "keyguardmedia" in name || "lockscreenmedia" in name -> OwnerRole.LOCK_MEDIA
                name.endsWith("expandablenotificationrow") -> OwnerRole.ROW
                name.endsWith("notificationbackgroundview") -> OwnerRole.ROW_BACKGROUND
                ".qs." in name || "controlcenter" in name || "quicksettings" in name -> OwnerRole.CONTROL
                "dismissview" in name || "notificationstackscrolllayout" in name -> OwnerRole.SHADE
                else -> OwnerRole.PARENT
            }
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
            if (!sidebar) runCatching { panelTargets.install(loader) }.onFailure { error ->
                GlassLogger.e("SoftGlass", "Some slider contracts are unavailable", error)
            }
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
                        val tracked = synchronized(lock) { shaders[shader] } ?: return
                        val target = if (sidebar || Surface.CONTROL !in profiles) null else panelTargets.owner(plate)
                        val state = if (profiles.isEmpty()) null else state(target?.view?.get() ?: host(plate), target)
                        if (tracked.applied != state) {
                            apply(shader, state)
                            synchronized(lock) { tracked.applied = state }
                        }
                        for (field in nodeFields) {
                            val node = field.get(plate) as? RenderNode ?: continue
                            val binding = synchronized(lock) { nodes[node] }
                            if (binding?.effect?.shader === shader && binding.effect.state != state) rebind(node, binding, state)
                        }
                        if (state == null) drawables.remove(plate)
                        else {
                            drawables[plate] = state.surface
                            target?.let(panelTargets::nativeDrawn)
                            if (rendered.add(state.role))
                                GlassLogger.i("SoftGlass", "Native material applied: ${state.surface} role=${state.role}")
                        }
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
            synchronized(lock) {
                if (shaders.containsKey(value)) return
                val target = panelTargets.current()
                val applied = if (Looper.myLooper() == main.looper && (sidebar || target != null)) {
                    state(target?.view?.get(), target)?.also { apply(value, it) }
                } else null
                shaders[value] = ShaderState(applied)
            }
        }

        private fun installBindings() {
            val factory = RenderEffect::class.java.getMethod("createRuntimeShaderEffect", RuntimeShader::class.java, String::class.java)
            XposedBridge.hookMethod(factory, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val effect = param.result as? RenderEffect ?: return
                    if (param.args[1] != SoftGlassShader.INPUT) return
                    val shader = param.args[0] as RuntimeShader
                    synchronized(lock) {
                        shaders[shader]?.let { effects[effect] = EffectState(shader, it.applied) }
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

        private fun rebind(node: RenderNode, binding: Binding, state: Applied?) {
            val shader = binding.effect.shader
            val cached = rebound[shader]
            val effect = cached?.takeIf { it.first == state }?.second?.get()
                ?: RenderEffect.createRuntimeShaderEffect(shader, SoftGlassShader.INPUT).also {
                    rebound[shader] = state to WeakReference(it)
                }
            if (binding.mode == null) binding.setter.invoke(node, effect) else binding.setter.invoke(node, effect, binding.mode)
        }

        private fun apply(shader: RuntimeShader, state: Applied?) {
            if (state == null) {
                shader.setFloatUniform(SoftGlassShader.UNIFORM, 0f, 0f, 0f)
                return
            }
            SoftGlassShader.applyProfile(shader, state.profile, state.role, state.dark, state.density,
                state.width.toFloat(), state.height.toFloat(), state.x, state.y)
        }
    }
}
