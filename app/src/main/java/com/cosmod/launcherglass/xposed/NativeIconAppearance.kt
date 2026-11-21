package com.cosmod.launcherglass.xposed

import android.content.Context
import android.content.res.Configuration
import android.graphics.Path
import android.graphics.RectF
import com.cosmod.launcherglass.ui.onextbox.addOplusSmoothRoundRect
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method

/** Read the same resolved icon appearance as Flux, including its independent light/dark setting. */
object NativeIconAppearance {
    private var attempted = false
    private var configClass: Class<*>? = null
    private var darkMethod: Method? = null

    private fun config(context: Context): Any? {
        if (!attempted) {
            attempted = true
            configClass = XposedHelpers.findClassIfExists("com.android.launcher.theme.LauncherIconConfig", context.classLoader)
            darkMethod = try {
                configClass?.getDeclaredMethod("isDarkIconEnable", Configuration::class.java)?.apply { isAccessible = true }
            } catch (_: Throwable) { null }
        }
        return try {
            // Do not initialize native icon configuration before the launcher itself is ready.
            val type = configClass ?: return null
            val instance = XposedHelpers.getStaticObjectField(type, "sInstance") ?: return null
            if (XposedHelpers.getObjectField(instance, "mIconConfig") == null) null else instance
        } catch (_: Throwable) { null }
    }

    fun read(context: Context, configuration: Configuration): Boolean? =
        if (config(context) == null) null
        else runCatching { darkMethod?.invoke(null, configuration) as? Boolean }.getOrNull()

    fun isClassic(context: Context): Boolean = config(context)?.let {
        runCatching { XposedHelpers.callMethod(it, "getTheme") == 2 }.getOrDefault(false)
    } ?: false

    fun iconOutline(context: Context, path: Path, bounds: RectF) {
        path.rewind()
        val size = minOf(bounds.width(), bounds.height())
        if (size <= 0f) return
        val radius = config(context)?.let {
            runCatching { XposedHelpers.callMethod(it, "getIconRadius", size, true) as? Float }.getOrNull()
        }?.takeIf { it.isFinite() && it in 0f..size * .5f } ?: size * .23f
        val supplied = runCatching {
            val manager = XposedHelpers.findClass("com.android.launcher3.util.SmoothRoundedManager", context.classLoader)
            XposedHelpers.callStaticMethod(manager, "createIconFolderClipPath", path, bounds, radius, radius, 1f, 1f)
            !path.isEmpty
        }.getOrDefault(false)
        if (!supplied) {
            path.rewind()
            if (!addOplusSmoothRoundRect(path, bounds, radius)) path.addRoundRect(bounds, radius, radius, Path.Direction.CW)
        }
    }
}
