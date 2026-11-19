package com.cosmod.launcherglass.xposed

import android.content.Context
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import com.cosmod.launcherglass.ui.ShadeGlassSettings
import com.cosmod.launcherglass.util.GlassLogger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

/** Tunes the original control-center background independently of the optical material. */
internal object ShadeGlass {
    private var background: ShadeGlassSettings.Profile? = null
    private var installed = false

    fun diagnostics() = "Control center backdrop: enabled=${background != null}, hooks=$installed"

    fun configure(context: Context, loader: ClassLoader?, bundle: Bundle, master: Boolean) {
        if (Build.VERSION.SDK_INT != 37 || loader == null) return
        background = ShadeGlassSettings.controlBackground(
            master && bundle.getBoolean(ShadeGlassSettings.CONTROL_BACKGROUND),
            bundle.getBoolean(ShadeGlassSettings.Panel.CONTROL.highRefresh),
        ) { key -> bundle.getInt(key, ShadeGlassSettings.integers.getValue(key)) }.takeIf { it.enabled }
        if (background == null || installed) return
        val root = loader.loadClass("com.oplus.systemui.plugins.qs.OplusQSRootView")
        val native = ShadeNativeBlur(context, loader, { background }, { true })
        val draw = ViewGroup::class.java.getDeclaredMethod("dispatchDraw", Canvas::class.java)
        XposedBridge.hookMethod(draw, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (background != null && root.isInstance(param.thisObject)) native.observe(param.thisObject as View)
            }
        })
        installed = true
        GlassLogger.i("ShadeGlass", "Native control-center backdrop adapter installed")
    }
}
