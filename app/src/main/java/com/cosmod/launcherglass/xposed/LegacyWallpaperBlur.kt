package com.cosmod.launcherglass.xposed

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import com.cosmod.launcherglass.util.GlassLogger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

/** ColorOS 16's wallpaper preblur can invoke the stock EGL helper from a coroutine worker. */
internal object LegacyWallpaperBlur {
    private val worker by lazy {
        val thread = HandlerThread("CPI wallpaper preblur", Process.THREAD_PRIORITY_BACKGROUND)
        thread.start()
        Handler(thread.looper)
    }

    fun install(loader: ClassLoader) {
        if (Build.VERSION.SDK_INT != Build.VERSION_CODES.BAKLAVA) return
        runCatching {
            val callback = loader.loadClass("com.android.launcher3.popup.EffectResultCallbackImp")
            val wallpaperCallback = loader.loadClass(
                "com.android.launcher.wallpaper.WallpaperBlur\$preprocessBlurredWallpaper\$3",
            )
            check(callback.isAssignableFrom(wallpaperCallback))
            val method = loader.loadClass("com.android.launcher3.popup.PopupBlurHelper").getDeclaredMethod(
                "blurBitmap", Bitmap::class.java, Float::class.javaPrimitiveType, Context::class.java,
                callback, Int::class.javaPrimitiveType, Color::class.java, Color::class.java, Float::class.javaPrimitiveType,
            )
            check(method.returnType == Void.TYPE)
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (Looper.myLooper() != null || !wallpaperCallback.isInstance(param.args[3])) return
                    val receiver = param.thisObject
                    val arguments = param.args.clone()
                    // The OEM method already delivers its result through this callback.
                    if (worker.post {
                        runCatching { XposedBridge.invokeOriginalMethod(method, receiver, arguments) }.onFailure { error ->
                            GlassLogger.limited("LegacyWallpaperBlur", "preblur") { "Stock wallpaper preblur failed: ${error.cause ?: error}" }
                        }
                    }) {
                        param.result = null
                        GlassLogger.limited("LegacyWallpaperBlur", "worker") { "Moved stock wallpaper preblur to a Looper worker" }
                    }
                }
            })
        }.onFailure {
            GlassLogger.limited("LegacyWallpaperBlur", "contract") { "Stock wallpaper preblur contract unavailable: $it" }
        }
    }
}
