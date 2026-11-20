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
        private const val THEME_SHIFT = 4
        private const val THEME_MASK = 0xfL shl THEME_SHIFT
        private const val CLASSIC = 2L
        private const val GRADIENT = 3L
        private const val ALWAYS_DARK = 1L shl 10
        private const val ENABLE_DARK_MODE = 1L shl 61

        internal fun requiredConfig(current: Long): Long {
            if (current <= 0L) return current
            val theme = (current and THEME_MASK) ushr THEME_SHIFT
            val display = current and (ALWAYS_DARK or ENABLE_DARK_MODE)
            if (theme != CLASSIC && (theme != GRADIENT || display != 0L)) return current
            return (current and THEME_MASK.inv()) or (GRADIENT shl THEME_SHIFT) or ALWAYS_DARK or ENABLE_DARK_MODE
        }

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
        val flux = requiredConfig(iconConfig)
        if (iconConfig == flux) return
        val updated = Configuration(current)
        val extra = extraConfig.invoke(updated) ?: return
        XposedHelpers.setLongField(extra, "mUxIconConfig", flux)
        XposedHelpers.callMethod(service, "updateConfiguration", updated)
        val manager = XposedHelpers.callStaticMethod(themeManager, "getInstance") ?: return
        val userId = XposedHelpers.callMethod(context, "getUserId") as Int
        writeSettings.invoke(manager, context.contentResolver, flux, userId)
        GlassLogger.i("DesktopAppearance", "Selected ColorOS Gradient with Dark display")
    }
}
