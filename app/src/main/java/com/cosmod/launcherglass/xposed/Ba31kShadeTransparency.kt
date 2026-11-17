package com.cosmod.launcherglass.xposed

import android.os.Bundle
import android.view.View
import com.cosmod.launcherglass.ui.SettingsProvider
import com.cosmod.launcherglass.util.Ba31kAnimationSettings as Settings
import com.cosmod.launcherglass.util.GlassLogger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference

/** Changes only the background scrim of the unlocked shade. */
internal object Ba31kShadeTransparency {
    private var enabled = false
    private var refresh: (() -> Unit)? = null
    private var last: WeakReference<Any>? = null

    fun update(bundle: Bundle, loader: ClassLoader?) {
        val desired = bundle.getBoolean(SettingsProvider.KEY_ENABLED) && bundle.getBoolean(Settings.ENABLED) &&
            bundle.getBoolean(Settings.CLEAR_SHADE)
        if (desired && refresh == null && loader != null) {
            refresh = runCatching { install(loader) }.getOrElse {
                GlassLogger.limited("Ba31kShade", "scrim") { "Native scrim retained: $it" }
                fun() {}
            }
        }
        if (enabled != desired) {
            enabled = desired
            refresh?.invoke()
        }
    }

    private fun install(loader: ClassLoader): () -> Unit {
        val owner = XposedHelpers.findClass("com.android.systemui.statusbar.phone.ScrimController", loader)
        val state = owner.getDeclaredField("mState").apply { isAccessible = true }
        val background = owner.getDeclaredField("mScrimBehind").apply { isAccessible = true }
        val notifications = owner.getDeclaredField("mNotificationsScrim").apply { isAccessible = true }
        val update = owner.getDeclaredMethod("updateScrims").apply { isAccessible = true }
        val color = owner.getDeclaredMethod("updateScrimColor", View::class.java,
            Int::class.javaPrimitiveType, Float::class.javaPrimitiveType)
        XposedBridge.hookMethod(color, object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                val host = p.thisObject
                last = WeakReference(host)
                if (!enabled) return
                runCatching {
                    if ((state.get(host) as? Enum<*>)?.name != "UNLOCKED") return
                    val view = p.args[0]
                    if (view === background.get(host) || view === notifications.get(host)) p.args[2] = 0f
                }
            }
        })
        return {
            last?.get()?.let { current -> runCatching { update.invoke(current) } }
        }
    }
}
