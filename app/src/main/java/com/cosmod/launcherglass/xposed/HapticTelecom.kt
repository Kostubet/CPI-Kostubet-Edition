package com.cosmod.launcherglass.xposed

import android.content.Context
import android.database.ContentObserver
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.annotation.RequiresApi
import com.cosmod.launcherglass.haptic.HapticRingStore
import com.cosmod.launcherglass.haptic.HapticMotor
import com.cosmod.launcherglass.haptic.HapticPatternCodec
import com.cosmod.launcherglass.haptic.HapticWaveform
import com.cosmod.launcherglass.ui.SettingsProvider
import com.cosmod.launcherglass.util.GlassLogger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/** Only substitutes an effect at a verified ringtone-vibration call site. */
internal object HapticTelecom {
    const val PACKAGE = "com.android.server.telecom"
    private const val COLOROS_VERSION = 17_007_010L
    private data class Binding(val uri: Uri?, val effect: VibrationEffect)
    private data class State(val defaultUri: Uri?, val bindings: Map<String, Binding>) {
        fun match(uri: Uri?, slot: Int): Binding? {
            val actual = if (uri == RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)) defaultUri else uri
            fun matches(item: Binding) = item.uri == null || item.uri == actual
            val perSlot = when (slot) { 0 -> bindings["sim1"]; 1 -> bindings["sim2"]; else -> null }
            return perSlot?.takeIf(::matches) ?: bindings["default"]?.takeIf(::matches)
        }
    }

    @Volatile private var state = State(null, emptyMap())
    @Volatile private var worker: Handler? = null
    private var activeSource: Any? = null
    private var cancelActive: (() -> Unit)? = null
    private val timeout = Runnable { cancel() }

    fun observeSystemTelecom(loader: ClassLoader) {
        val service = XposedHelpers.findClassIfExists("$PACKAGE.TelecomLoaderService", loader) ?: return
        val boot = service.declaredMethods.firstOrNull {
            it.name == "onBootPhase" && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
        } ?: return
        XposedBridge.hookMethod(boot, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (worker != null || param.hasThrowable() || (param.args[0] as Int) < 550) return
                runCatching {
                    val context = XposedHelpers.callMethod(param.thisObject, "getContext") as Context
                    val telecom = context.createPackageContext(PACKAGE, Context.CONTEXT_INCLUDE_CODE)
                    install(telecom.classLoader, telecom)
                }
            }
        })
    }

    @Synchronized fun install(loader: ClassLoader, providedContext: Context? = null) {
        if (worker != null) return
        val thread = HandlerThread("CPI ringtone settings").apply { start() }
        val handler = Handler(thread.looper)
        worker = handler
        handler.post {
            val context = providedContext ?: telecomContext()
            if (context == null) {
                worker = null
                thread.quitSafely()
                return@post
            }
            val adapter = runCatching {
                val packageVersion = context.packageManager.getPackageInfo(PACKAGE, 0).longVersionCode
                when {
                    Build.VERSION.SDK_INT >= 33 && packageVersion == COLOROS_VERSION &&
                        installColorOs(loader, context) -> "coloros17"
                    installAosp(loader) -> "aosp"
                    else -> "unsupported"
                }
            }.getOrElse {
                GlassLogger.limited("HapticTelecom", "adapter") { "Ringtone vibration unchanged: ${it.javaClass.simpleName}" }
                "unsupported"
            }
            runCatching {
                context.contentResolver.call(SettingsProvider.CONTENT_URI, HapticRingStore.REPORT, adapter, null)
            }
            if (adapter != "unsupported") runCatching {
                val observer = object : ContentObserver(handler) {
                    override fun onChange(selfChange: Boolean) = refresh(context, adapter)
                }
                context.contentResolver.registerContentObserver(SettingsProvider.CONTENT_URI, true, observer)
                context.contentResolver.registerContentObserver(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), false, observer)
                refresh(context, adapter)
            }
        }
    }

    private fun telecomContext(): Context? = runCatching {
        val thread = Class.forName("android.app.ActivityThread")
        val application = XposedHelpers.callStaticMethod(thread, "currentApplication") as? Context
        val current = application ?: XposedHelpers.callMethod(
            XposedHelpers.callStaticMethod(thread, "currentActivityThread"), "getSystemContext") as? Context
        current?.createPackageContext(PACKAGE, 0)
    }.getOrNull()

    private fun refresh(context: Context, adapter: String) {
        val snapshot = runCatching {
            context.contentResolver.call(SettingsProvider.CONTENT_URI, HapticRingStore.REPORT, adapter, null)
            val settings = context.contentResolver.call(SettingsProvider.CONTENT_URI, HapticRingStore.READ, null, null)
                ?: Bundle.EMPTY
            val motor by lazy { HapticMotor(context) }
            val bindings = buildMap {
                for (tag in listOf("default", "sim1", "sim2")) {
                    val times = settings.getString("times_$tag")?.split(',')?.map(String::toLong)?.toLongArray()
                        ?: continue
                    val levels = settings.getString("levels_$tag")?.split(',')?.map(String::toInt)?.toIntArray()
                        ?: continue
                    if (times.size != levels.size || times.size !in 1..7500 ||
                        times.any { it !in 1L..120_000L } || times.sum() > 120_000L ||
                        levels.any { it !in 0..255 }) continue
                    val native = if (adapter == "coloros17") settings.getString("native_$tag")?.let { encoded ->
                        runCatching {
                            val project = encoded.byteInputStream().use { HapticPatternCodec.read(it, false) }
                            motor.nativeRingtone(project)
                        }.getOrNull()
                    } else null
                    val effect = native ?: HapticWaveform(times, levels).effect(true) ?: continue
                    put(tag, Binding(settings.getString("uri_$tag")?.let(Uri::parse), effect))
                }
            }
            State(RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE), bindings)
        }.getOrElse { State(null, emptyMap()) }
        state = snapshot
        if (snapshot.bindings.isEmpty()) cancel()
    }

    @RequiresApi(33)
    private fun installColorOs(loader: ClassLoader, context: Context): Boolean {
        val ringer = XposedHelpers.findClassIfExists("com.android.server.telecom.AsyncRingtonePlayer", loader) ?: return false
        val call = XposedHelpers.findClassIfExists("$PACKAGE.Call", loader) ?: return false
        val native = XposedHelpers.findClassIfExists("n2.d", loader) ?: return false
        if (native.interfaces.none { it.name == "h7.a" }) return false
        ringer.getDeclaredMethod("h", Ringtone::class.java, Handler::class.java)
        val start = native.getDeclaredMethod("b", call, Uri::class.java, Int::class.javaPrimitiveType)
        val stop = native.getDeclaredMethod("d")
        val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return false
        if (!vibrator.hasVibrator()) return false
        val attributes = VibrationAttributes.createForUsage(VibrationAttributes.USAGE_RINGTONE)
        // Install cancellation first so a partial install cannot start a custom effect.
        val stopHook = XposedBridge.hookMethod(stop, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) { cancel(param.thisObject) }
        })
        try {
            XposedBridge.hookMethod(start, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    // The native path already checked sound mode, contact policy and embedded haptics.
                    val binding = state.match(param.args[1] as? Uri, param.args[2] as Int) ?: return
                    val started = runCatching {
                        synchronized(this@HapticTelecom) {
                            cancel()
                            vibrator.vibrate(binding.effect, attributes)
                            activeSource = param.thisObject
                            cancelActive = { vibrator.cancel() }
                            worker?.postDelayed(timeout, 75_000)
                        }
                    }.isSuccess
                    if (started) param.result = null
                }
            })
        } catch (error: Throwable) {
            stopHook.unhook()
            throw error
        }
        return true
    }

    private fun installAosp(loader: ClassLoader): Boolean {
        val ringer = XposedHelpers.findClassIfExists("$PACKAGE.Ringer", loader) ?: return false
        val call = XposedHelpers.findClassIfExists("$PACKAGE.Call", loader) ?: return false
        val factory = XposedHelpers.findClassIfExists("$PACKAGE.RingtoneFactory", loader) ?: return false
        val hook = ringer.declaredMethods.singleOrNull {
            it.name == "getVibrationEffectForCall" && it.returnType == VibrationEffect::class.java &&
                it.parameterTypes.contentEquals(arrayOf(factory, call))
        } ?: return false
        val ringtone = call.methods.firstOrNull {
            it.name == "getRingtone" && it.parameterCount == 0 && it.returnType == Uri::class.java
        } ?: return false
        XposedBridge.hookMethod(hook, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.hasThrowable() || state.bindings.isEmpty()) return
                val uri = runCatching { ringtone.invoke(param.args[1]) as? Uri }.getOrNull()
                state.match(uri, -1)?.let { param.result = it.effect }
            }
        })
        return true
    }

    @Synchronized private fun cancel(source: Any? = null) {
        if (source != null && activeSource !== source) return
        worker?.removeCallbacks(timeout)
        cancelActive?.let { runCatching(it) }
        cancelActive = null
        activeSource = null
    }
}
