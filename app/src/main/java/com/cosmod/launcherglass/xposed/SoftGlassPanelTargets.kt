// Native drawable ownership follows the ONextBox panel adapter.
// SPDX-License-Identifier: GPL-3.0-only
package com.cosmod.launcherglass.xposed

import android.graphics.Canvas
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.Drawable
import android.view.View
import com.cosmod.launcherglass.util.GlassLogger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/** Carries the native track identity through the wrapper that draws its material. */
internal class SoftGlassPanelTargets(
    private val active: () -> Boolean,
    private val fallback: (Canvas, Drawable, Owner) -> Unit
) {
    enum class Kind(val role: Float) { SEEK_TRACK(9f), VOLUME_PANEL(2f), VOLUME_MORE(6f), FILL(-1f) }

    class Owner(val kind: Kind, host: View?, val radius: Float) {
        val view = WeakReference(host)
    }

    private val owners = Collections.synchronizedMap(WeakHashMap<Drawable, Owner>())
    private class Draw(val owner: Owner, val nested: Draw?, var rendered: Boolean = false)
    private val drawing = ThreadLocal<Draw>()
    private val hooked = mutableSetOf<Method>()
    var supportsVolume = false
        private set

    fun current() = drawing.get()?.owner
    fun nativeDrawn() { drawing.get()?.rendered = true }

    fun owner(drawable: Drawable): Owner? {
        current()?.let { return it }
        var link: Drawable? = drawable
        repeat(12) {
            val node = link ?: return null
            owners[node]?.let { return it }
            link = node.callback as? Drawable
        }
        return null
    }

    fun install(loader: ClassLoader) {
        val qs = find(loader, "com.oplus.systemui.qs.base.util.QsSeekBarBlurManager")
        qs?.declaredMethods?.forEach { method ->
            val kind = when (method.name) {
                "getSeekBarBackground", "applySeekBarBgBlurConfig" -> Kind.SEEK_TRACK
                "getSeekBarActiveDrawable", "applySeekBarActiveBlurConfig" -> Kind.FILL
                else -> return@forEach
            }
            val factory = Drawable::class.java.isAssignableFrom(method.returnType)
            val arg = method.parameterTypes.indexOfFirst { Drawable::class.java.isAssignableFrom(it) }
            if (!factory && arg < 0) return@forEach
            hook(method, resolve = { param ->
                val old = if (factory) null else (param.args[arg] as? Drawable)?.let(owners::get)
                val view = param.args.firstOrNull { it is View } as? View ?: old?.view?.get()
                val target = Owner(kind, view, (param.args.getOrNull(2) as? Float) ?: old?.radius ?: 0f)
                if (!factory) (param.args[arg] as? Drawable)?.let { owners[it] = target }
                target
            }, remember = factory)
        }
        val factory = find(loader, "com.oplus.systemui.volume.utils.material.VolumeSurfaceFactory")
        val create = factory?.declaredMethods?.singleOrNull {
            it.name == "createContent" && it.parameterCount == 1 && Drawable::class.java.isAssignableFrom(it.returnType)
        }
        if (create != null) {
            val request = create.parameterTypes[0]
            val kind = request.getMethod("getKind")
            val corner = request.getMethod("getCorner")
            val radius = corner.returnType.getMethod("getCornerRadius")
            val host = request.methods.firstOrNull { it.name == "getHost" && it.parameterCount == 0 &&
                View::class.java.isAssignableFrom(it.returnType) }
            hook(create, resolve = { param ->
                val spec = param.args[0] ?: return@hook null
                val name = (kind.invoke(spec) as? Enum<*>)?.name
                val view = host?.invoke(spec) as? View
                val role = when (name) {
                    "BAR_PROGRESS" -> Kind.FILL
                    "BAR_BACKGROUND", "PANEL_BACKGROUND", "SETTINGS_BUTTON" -> Kind.VOLUME_PANEL
                    "SIDE_ACCESSORY" -> if (view != null && view.id > 0 &&
                        view.resources.getResourceEntryName(view.id) == "more_row_stream_ll") Kind.VOLUME_MORE else Kind.VOLUME_PANEL
                    else -> return@hook null
                }
                Owner(role, view, (radius.invoke(corner.invoke(spec)) as Number).toFloat())
            }, remember = true)
            supportsVolume = true
        }
        find(loader, "com.oplusos.systemui.common.blurability.drawable.AutoBlurDrawable")
            ?.getDeclaredMethod("draw", Canvas::class.java)?.let(::hookDraw)
        if (supportsVolume) {
            hookDraw(LayerDrawable::class.java.getDeclaredMethod("draw", Canvas::class.java))
            hookDraw(android.graphics.drawable.GradientDrawable::class.java.getDeclaredMethod("draw", Canvas::class.java))
        }
    }

    private fun hookDraw(method: Method) = hook(method, drawingMethod = true, resolve = { param ->
        if (active()) owners[param.thisObject as Drawable] else null
    })

    private fun hook(method: Method, resolve: (XC_MethodHook.MethodHookParam) -> Owner?,
        remember: Boolean = false, drawingMethod: Boolean = false) {
        if (!hooked.add(method)) return
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val owner = runCatching { resolve(param) }.onFailure { error ->
                    GlassLogger.limited("SoftGlass", "panelTarget") { "Stock slider target retained: ${error.javaClass.simpleName}" }
                }.getOrNull() ?: return
                val draw = Draw(owner, drawing.get())
                param.setObjectExtra("softPanelDraw", draw)
                drawing.set(draw)
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                val draw = param.getObjectExtra("softPanelDraw") as? Draw ?: return
                val previous = draw.nested
                if (previous == null) drawing.remove() else drawing.set(previous)
                if (param.hasThrowable()) return
                if (remember) (param.result as? Drawable)?.let { owners[it] = draw.owner }
                if (drawingMethod && !draw.rendered && draw.owner.kind != Kind.FILL && previous == null) {
                    runCatching { fallback(param.args[0] as Canvas, param.thisObject as Drawable, draw.owner) }
                        .onFailure { error -> GlassLogger.limited("SoftGlass", "sliderFallback") {
                            "Native track retained: ${error.javaClass.simpleName}" } }
                }
            }
        })
    }

    private fun find(loader: ClassLoader, name: String) = runCatching { loader.loadClass(name) }.getOrNull()
}
