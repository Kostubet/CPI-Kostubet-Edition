package com.cosmod.launcherglass.xposed

import android.content.ContentResolver
import android.content.Context
import android.content.res.Configuration
import com.cosmod.launcherglass.util.GlassLogger
import de.robv.android.xposed.XposedHelpers

/** Change the launcher theme through the same ColorOS configuration used by its icon picker. */
internal class NativeClassicIconStyle(loader: ClassLoader) {
    private val activityManager = loader.loadClass("android.app.ActivityManager")
    private val extraConfig = loader.loadClass("android.content.res.OplusBaseConfiguration")
        .getMethod("getOplusExtraConfiguration")
    private val themeManager = loader.loadClass("android.content.res.OplusThemeManager")
    private val writeSettings = themeManager.getMethod(
        "setIconConfigToSettings", ContentResolver::class.java, Long::class.javaPrimitiveType,
        Int::class.javaPrimitiveType,
    )

    fun apply(context: Context) {
        val service = XposedHelpers.callStaticMethod(activityManager, "getService") ?: return
        val current = XposedHelpers.callMethod(service, "getConfiguration") as? Configuration ?: return
        val original = extraConfig.invoke(current) ?: return
        val iconConfig = XposedHelpers.getLongField(original, "mUxIconConfig")
        if (iconConfig <= 0L) return
        // Theme 2 is ColorOS Classic. Keep the user's native size and shape.
        // Dark icon, wallpaper tint and forced-night bits belong to the previous theme.
        val previousStyle = (0xfL shl 4) or (0x3L shl 60) or (0x3L shl 10)
        val classic = (iconConfig and previousStyle.inv()) or (2L shl 4)
        val activePack = XposedHelpers.getObjectField(original, "mIconPackName") as? String
        if (iconConfig != classic || !activePack.isNullOrEmpty()) {
            val updated = Configuration(current)
            val extra = extraConfig.invoke(updated) ?: return
            XposedHelpers.setLongField(extra, "mUxIconConfig", classic)
            XposedHelpers.setObjectField(extra, "mIconPackName", "")
            XposedHelpers.callMethod(service, "updateConfiguration", updated)
        }
        val manager = XposedHelpers.callStaticMethod(themeManager, "getInstance") ?: return
        val userId = XposedHelpers.callMethod(context, "getUserId") as Int
        writeSettings.invoke(manager, context.contentResolver, classic, userId)
        GlassLogger.i("DesktopAppearance", "Applied native ColorOS Classic icons")
    }
}
