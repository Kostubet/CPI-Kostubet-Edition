package com.cosmod.launcherglass.xposed

import android.content.ContentResolver
import android.content.Context
import android.content.res.Configuration
import com.cosmod.launcherglass.util.GlassLogger
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.Executors

/** Change the launcher theme through the same ColorOS configuration used by its icon picker. */
internal class NativeFluxIconStyle private constructor(loader: ClassLoader) {
    companion object {
        private val writer by lazy { Executors.newSingleThreadExecutor() }

        fun request(context: Context) {
            val launcher = context.applicationContext
            writer.execute {
                if (!PrefsHelper.usesDesktopAppearance()) return@execute
                runCatching { NativeFluxIconStyle(launcher.classLoader).apply(launcher) }.onFailure {
                    GlassLogger.limited("DesktopAppearance", "flux") { "Could not select native Flux: ${it.message}" }
                }
            }
        }
    }

    private val activityManager = loader.loadClass("android.app.ActivityManager")
    private val extraConfig = loader.loadClass("android.content.res.OplusBaseConfiguration")
        .getMethod("getOplusExtraConfiguration")
    private val themeManager = loader.loadClass("android.content.res.OplusThemeManager")
    private val writeSettings = themeManager.getMethod(
        "setIconConfigToSettings", ContentResolver::class.java, Long::class.javaPrimitiveType,
        Int::class.javaPrimitiveType,
    )

    private fun apply(context: Context) {
        val service = XposedHelpers.callStaticMethod(activityManager, "getService") ?: return
        val current = XposedHelpers.callMethod(service, "getConfiguration") as? Configuration ?: return
        val original = extraConfig.invoke(current) ?: return
        val iconConfig = XposedHelpers.getLongField(original, "mUxIconConfig")
        if (iconConfig <= 0L) return
        // CPI supplies the tone. Keep native geometry and use a plain Flux base for every profile.
        val previousStyle = (0xfL shl 4) or (0x3L shl 60) or (0x3L shl 10)
        val flux = (iconConfig and previousStyle.inv()) or (3L shl 4)
        val activePack = XposedHelpers.getObjectField(original, "mIconPackName") as? String
        if (iconConfig == flux && activePack.isNullOrEmpty()) return
        val updated = Configuration(current)
        val extra = extraConfig.invoke(updated) ?: return
        XposedHelpers.setLongField(extra, "mUxIconConfig", flux)
        XposedHelpers.setObjectField(extra, "mIconPackName", "")
        XposedHelpers.callMethod(service, "updateConfiguration", updated)
        val manager = XposedHelpers.callStaticMethod(themeManager, "getInstance") ?: return
        val userId = XposedHelpers.callMethod(context, "getUserId") as Int
        writeSettings.invoke(manager, context.contentResolver, flux, userId)
        GlassLogger.i("DesktopAppearance", "Applied native ColorOS Flux icons")
    }
}
