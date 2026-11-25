package com.cosmod.launcherglass.xposed

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.PopupWindow
import com.cosmod.launcherglass.ui.FeatureSections
import com.cosmod.launcherglass.ui.OpticalSettings
import com.cosmod.launcherglass.util.GlassLogger
import de.robv.android.xposed.XC_MethodHook
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** Colors and blur radius for the launcher's native COUI shortcut popup. */
internal object LauncherAppMenuGlass {
    private const val BLUR = "app_menu_glass_blur"
    private const val OPACITY = "app_menu_glass_opacity"
    private const val ORIGINAL = "cpiAppMenuGlassOriginal"

    private data class Material(val blurDp: Int, val opacity: Float)
    private data class Original(val radius: Int, val lightMix: FloatArray?, val darkMix: FloatArray?)

    @Volatile private var material: Material? = null
    private val popupRoots = WeakHashMap<View, Boolean>()

    fun update(settings: Bundle, moduleEnabled: Boolean) {
        material = if (moduleEnabled && settings.getBoolean(FeatureSections.key("APP_MENU"))) {
            Material(
                settings.getInt(BLUR, OpticalSettings.integers.getValue(BLUR))
                    .coerceIn(0, OpticalSettings.maximum(BLUR)),
                settings.getInt(OPACITY, OpticalSettings.integers.getValue(OPACITY))
                    .coerceIn(0, 100) / 100f,
            )
        } else {
            null
        }
    }

    fun install(loader: ClassLoader) {
        runCatching {
            val container = loader.loadClass("com.android.launcher3.popup.view.COUIPopupListWindowContainer")
            val base = loader.loadClass("com.android.launcher3.popup.view.BasePopupListWindowContainer")
            check(container.superclass == base)
            val ensureWindow = container.getDeclaredMethod("ensureWindow", Context::class.java)
            val getWindow = base.getDeclaredMethod("getMPopupListWindow").apply { isAccessible = true }
            check(PopupWindow::class.java.isAssignableFrom(getWindow.returnType))

            // Confirmed on stock ColorOS 17. Both popup levels use this frame and blur builder.
            val frameType = loader.loadClass("com.coui.appcompat.poplist.RoundFrameLayout")
            val builder = frameType.getDeclaredField("w").type
            check(builder.name == "pg.a")
            val target = builder.getDeclaredField("b").apply { isAccessible = true }
            val radius = builder.getDeclaredField("s").apply { isAccessible = true }
            val lightMix = builder.getDeclaredField("w").apply { isAccessible = true }
            val darkMix = builder.getDeclaredField("x").apply { isAccessible = true }
            check(target.type == View::class.java && radius.type == Int::class.javaPrimitiveType)
            check(lightMix.type == FloatArray::class.java && darkMix.type == FloatArray::class.java)
            val applyMaterial = builder.getDeclaredMethod("a")
            val applyRadius = builder.getDeclaredMethod("e", String::class.java)
            check(applyMaterial.returnType == Void.TYPE && applyRadius.returnType == Void.TYPE)

            ModuleHooks.hookMethod(ensureWindow, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.hasThrowable()) return
                    val popup = getWindow.invoke(param.thisObject) as? PopupWindow ?: return
                    popup.contentView?.let { popupRoots[it] = true }
                }
            })

            val applySettings = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val settings = material ?: return
                    val instance = param.thisObject
                    val view = target.get(instance) as? View ?: return
                    if (!frameType.isInstance(view) || !belongsToPopup(view)) return
                    val pixels = (settings.blurDp * view.resources.displayMetrics.density).roundToInt()
                    // COUI builds its tint during a() and can reapply the radius separately during e().
                    val tint = param.method == applyMaterial
                    val light = if (tint) lightMix.get(instance) as? FloatArray else null
                    val dark = if (tint) darkMix.get(instance) as? FloatArray else null
                    val original = Original(radius.getInt(instance), light, dark)
                    if (!tint && original.radius == pixels) return
                    param.setObjectExtra(ORIGINAL, original)
                    radius.setInt(instance, pixels)
                    if (light?.size == 4) lightMix.set(instance, light.withAlpha(settings.opacity))
                    if (dark?.size == 4) darkMix.set(instance, dark.withAlpha(settings.opacity))
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val original = param.getObjectExtra(ORIGINAL) as? Original ?: return
                    radius.setInt(param.thisObject, original.radius)
                    original.lightMix?.let { lightMix.set(param.thisObject, it) }
                    original.darkMix?.let { darkMix.set(param.thisObject, it) }
                }
            }
            ModuleHooks.hookMethod(applyMaterial, applySettings)
            ModuleHooks.hookMethod(applyRadius, applySettings)
        }.onFailure {
            GlassLogger.limited("LauncherAppMenuGlass", "compatibility") {
                "Using stock app menu material: ${it.javaClass.simpleName}"
            }
        }
    }

    private fun belongsToPopup(view: View): Boolean =
        generateSequence(view) { it.parent as? View }.any(popupRoots::containsKey)

    private fun FloatArray.withAlpha(alpha: Float): FloatArray = copyOf().also { it[3] = alpha }
}
