package com.cosmod.launcherglass.xposed

import android.app.Application
import android.content.Context
import android.database.ContentObserver
import android.hardware.camera2.CaptureResult
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.cosmod.launcherglass.BuildConfig
import com.cosmod.launcherglass.camera.CameraZoomSettings as Settings
import com.cosmod.launcherglass.camera.ZoomSmoothing
import com.cosmod.launcherglass.camera.ZoomTuning
import com.cosmod.launcherglass.ui.SettingsProvider
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.ref.WeakReference
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile
import kotlin.math.abs

internal object CameraVideoZoom {
    private enum class Input(val key: String, val gesture: Boolean) {
        PINCH(Settings.PINCH, true), SLIDER(Settings.SLIDER, true), STEPS(Settings.STEPS, false)
    }

    fun install(loader: ClassLoader) {
        val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
        XposedBridge.hookMethod(attach, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val app = param.thisObject as Application
                if (app.packageName != Settings.PACKAGE) return
                val runtime = Runtime(app)
                runtime.observeSettings()
                if (!compatible(app)) {
                    runtime.status(Settings.STATE_UNSUPPORTED, "Unverified camera version or firmware")
                    return
                }
                runCatching { runtime.bind(loader) }.onFailure {
                    runtime.status(Settings.STATE_ERROR, "Zoom presenter contract unavailable")
                }
            }
        })
    }

    @Suppress("DEPRECATION")
    private fun compatible(app: Application): Boolean = runCatching {
        val info = app.packageManager.getPackageInfo(Settings.PACKAGE, 0)
        if (Build.MODEL != "CPH2649" || Build.VERSION.SDK_INT != 37 ||
            !Build.DISPLAY.startsWith("CPH2649_17.0.0.102") ||
            info.longVersionCode != 60000L || info.versionName != "7.006.144") return false
        ZipFile(app.applicationInfo.sourceDir).use {
            it.getEntry("classes12.dex")?.crc == 873631590L &&
                it.getEntry("classes26.dex")?.crc == 353206216L
        }
    }.getOrDefault(false)

    private class Runtime(private val app: Application) {
        private data class Config(val enabled: Boolean = false, val diagnostics: Boolean = false,
            val inputs: Set<Input> = emptySet(), val tuning: ZoomTuning = ZoomTuning())
        private data class Move(
            val presenter: WeakReference<Any>,
            val engine: ZoomSmoothing,
            val region: Int,
            var lastCommand: Float,
            val lower: Float,
            val upper: Float,
        )

        private val main = Handler(Looper.getMainLooper())
        private val input = ThreadLocal<Input?>()
        private val frameQueued = AtomicBoolean(false)
        @Volatile private var config = Config()
        @Volatile private var move: Move? = null
        @Volatile private var reportedRatio = Float.NaN
        private var state = Settings.STATE_READY
        private var mechanism = "Oplus ZoomPresenter · vendor and capture request"
        @Volatile private var lastStatus = Long.MIN_VALUE / 2
        private var recentFrame = Long.MIN_VALUE / 2
        private var stockTarget = Float.NaN
        @Volatile private var failed = false
        private lateinit var submit: Method
        private lateinit var dispatch: Method
        private lateinit var checks: List<Method>
        private lateinit var boundaries: List<Method>
        private lateinit var sat: Method
        private lateinit var zoomState: java.lang.reflect.Field
        private lateinit var currentRatio: java.lang.reflect.Field
        private lateinit var zoomUi: java.lang.reflect.Field
        private lateinit var recordMin: java.lang.reflect.Field
        private lateinit var recordMax: java.lang.reflect.Field

        private val timeout = Runnable {
            if (move != null && SystemClock.uptimeMillis() - recentFrame >= 280L) {
                finishWithStockTarget()
                status(Settings.STATE_READY, "Camera frames paused · stock target restored")
            }
        }

        fun observeSettings() {
            fun reload() {
                val prefs = runCatching { app.contentResolver.call(SettingsProvider.CONTENT_URI, Settings.READ, null, null) }.getOrNull()
                val next = if (prefs == null) Config() else Config(
                    prefs.getBoolean(Settings.ENABLED), prefs.getBoolean(Settings.DIAGNOSTICS),
                    Input.entries.filter { prefs.getBoolean(it.key) }.toSet(),
                    Settings.tuning { key -> prefs.getInt(key, Settings.integers.getValue(key)) },
                )
                val previous = config
                config = next
                if (previous.enabled && (!next.enabled || previous.inputs != next.inputs)) finishWithStockTarget()
                if (previous.diagnostics != next.diagnostics) status(state, mechanism)
            }
            reload()
            runCatching {
                app.contentResolver.registerContentObserver(SettingsProvider.CONTENT_URI, false,
                    object : ContentObserver(main) {
                        override fun onChange(selfChange: Boolean) = reload()
                    })
            }
        }

        fun bind(loader: ClassLoader) {
            fun type(name: String) = Class.forName(name, false, loader)
            val presenter = type("zl.c0")
            val ui = type("com.oplus.camera.feature.zoom.ui.j")
            val frame = type("xf.b")
            val float = Float::class.javaPrimitiveType!!
            val bool = Boolean::class.javaPrimitiveType!!
            submit = presenter.getDeclaredMethod("k3", float, float, bool)
            dispatch = presenter.getDeclaredMethod("V3", float, bool)
            checks = listOf("isVideoRecording", "isPreviewStarted", "isAlive",
                "isPaused", "isFrontCamera", "isVideoRecordStarting", "isVideoRecordStopping", "isSwitchingCamera")
                .map { presenter.getMethod(it) }
            boundaries = listOf("w2", "X3", "Y3", "a4", "Z3").map { presenter.getDeclaredMethod(it, float) }
            sat = presenter.getMethod("getSupportFunction", String::class.java)
            zoomState = presenter.getDeclaredField("i")
            currentRatio = type("yl.f").getDeclaredField("i")
            zoomUi = presenter.getDeclaredField("j")
            recordMin = ui.getDeclaredField("z")
            recordMax = ui.getDeclaredField("A")
            val stage = frame.getDeclaredField("a")
            val getResult = frame.getDeclaredMethod("a")
            val resultGet = type("jb.t").getDeclaredMethod("a", CaptureResult.Key::class.java, getResult.returnType)
            val members = listOf(zoomState, currentRatio, zoomUi, recordMin, recordMax, stage,
                getResult, resultGet, submit, dispatch, sat) + checks + boundaries
            members.forEach { it.isAccessible = true }
            val sources = mapOf(
                Input.PINCH to listOf(type("com.oplus.camera.feature.zoom.ui.j\$a")
                    .getDeclaredMethod("a", type("com.oplus.camera.feature.zoom.ui.d"))),
                Input.SLIDER to listOf(type("am.u1").getDeclaredMethod("l", float, float, bool, bool),
                    ui.getDeclaredMethod("Z", float, float, bool, bool, bool, bool)),
                Input.STEPS to listOf(ui.getDeclaredMethod("Y", float, bool),
                    type("com.oplus.camera.feature.zoom.ui.i").getDeclaredMethod("a", float, bool, bool)),
            )
            val frameCallback = presenter.getDeclaredMethod("processPreviewData", frame)
            val pausing = presenter.getDeclaredMethod("onPause", bool)
            val session = presenter.getDeclaredMethod("onSessionCallback",
                type("com.oplus.camera.protocal.event.message.deviceProtocol.deviceControlCallback.EventMessageSessionState"))
            val hooks = mutableListOf<XC_MethodHook.Unhook>()
            fun hook(member: Member, callback: XC_MethodHook) { hooks += XposedBridge.hookMethod(member, callback) }
            try {
                for ((kind, methods) in sources) for (method in methods) hook(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (Looper.myLooper() != Looper.getMainLooper()) return
                        param.setObjectExtra("cpiZoomSource", input.get())
                        if (input.get() == null) input.set(kind)
                    }
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (Looper.myLooper() == Looper.getMainLooper()) input.set(param.getObjectExtra("cpiZoomSource") as? Input)
                    }
                })
                hook(submit, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (Looper.myLooper() != Looper.getMainLooper() || failed) return
                        runCatching { beforeSubmit(param) }.onFailure { disable() }
                    }
                })
                hook(frameCallback, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val now = SystemClock.uptimeMillis()
                        if (failed || move == null && (!config.diagnostics || now - lastStatus < 500)) return
                        val data = param.args[0] ?: return
                        if (runCatching { stage.getInt(data) }.getOrNull() != 1) return
                        if (!frameQueued.compareAndSet(false, true)) return
                        val host = param.thisObject
                        if (config.diagnostics && now - lastStatus >= 350) {
                            reportedRatio = runCatching {
                                resultGet.invoke(null, CaptureResult.CONTROL_ZOOM_RATIO, getResult.invoke(data)) as? Float
                            }.getOrNull() ?: Float.NaN
                        }
                        main.post {
                            frameQueued.set(false)
                            runCatching { onFrame(host, now) }.onFailure { disable() }
                        }
                    }
                })
                val cancel = object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val host = param.thisObject
                        val work = Runnable { if (move?.presenter?.get() === host) clear() }
                        if (Looper.myLooper() == Looper.getMainLooper()) work.run() else main.post(work)
                    }
                }
                hook(pausing, cancel)
                hook(session, cancel)
            } catch (failure: Throwable) {
                hooks.forEach { runCatching { it.unhook() } }
                throw failure
            }
            status(Settings.STATE_READY)
        }

        private fun beforeSubmit(param: XC_MethodHook.MethodHookParam) {
            val host = param.thisObject
            val settings = config
            val source = input.get()
            val target = param.args[0] as Float
            if (settings.diagnostics && target.isFinite() && source != null && allowed(host)) {
                stockTarget = target
                if (move == null && SystemClock.uptimeMillis() - lastStatus >= 350) status(Settings.STATE_OBSERVING)
            }
            if (!settings.enabled || source !in settings.inputs || param.args[2] != true || !allowed(host)) {
                if (move?.presenter?.get() === host && param.args[2] == true) clear()
                return
            }
            val running = move?.takeIf { it.presenter.get() === host }
            val current = running?.lastCommand ?: currentRatio.getFloat(zoomState.get(host))
            val controls = zoomUi.get(host) ?: return
            val min = recordMin.getFloat(controls)
            val max = recordMax.getFloat(controls)
            if (!current.isFinite() || !target.isFinite() || min <= 0f || max < min ||
                current !in min..max || target !in min..max || abs(target - current) < .001f) {
                clear()
                return
            }
            val region = region(host, current)
            if (region(host, target) != region || region == 0 && sat.invoke(host, "func_sat_camera") == true) {
                clear()
                if (settings.diagnostics) status(Settings.STATE_OBSERVING, "Lens boundary · stock zoom")
                return
            }
            val now = SystemClock.uptimeMillis()
            val active = running ?: Move(WeakReference(host), ZoomSmoothing(current.toDouble()), region, current, min, max)
            active.engine.aim(target.toDouble(), now / 1000.0, source?.gesture == true)
            move = active
            stockTarget = target
            if (running == null) recentFrame = now
            main.removeCallbacks(timeout)
            main.postDelayed(timeout, 300)
            // Preserve the original UI and listener path. Future ratios use the same camera submitter.
            param.args[0] = current
            if (state != Settings.STATE_ACTIVE) status(Settings.STATE_ACTIVE)
        }

        private fun onFrame(host: Any, time: Long) {
            val active = move
            if (active?.presenter?.get() !== host) {
                if (config.diagnostics && time - lastStatus >= 500 && allowed(host)) status(Settings.STATE_OBSERVING)
                return
            }
            if (!allowed(host)) { clear(); return }
            if (!config.enabled) { finishWithStockTarget(); return }
            val movement = requireNotNull(active)
            recentFrame = time
            main.removeCallbacks(timeout)
            main.postDelayed(timeout, 300)
            val next = movement.engine.step(time / 1000.0, config.tuning).toFloat().coerceIn(movement.lower, movement.upper)
            if (region(host, next) != movement.region) {
                finishWithStockTarget()
                return
            }
            if (abs(next - movement.lastCommand) >= maxOf(.001f, next * .0005f) || !movement.engine.active) {
                dispatch.invoke(host, next, true)
                movement.lastCommand = next
            }
            if (!movement.engine.active) { clear(); status(Settings.STATE_READY) }
            else if (config.diagnostics && time - lastStatus >= 350) status(Settings.STATE_ACTIVE)
        }

        private fun allowed(host: Any): Boolean = checks.withIndex().all { (index, method) ->
            method.invoke(host) == (index < 3)
        }
        private fun region(host: Any, value: Float): Int = boundaries.withIndex().fold(0) { mask, (index, method) ->
            if (method.invoke(host, value) == true) mask or (1 shl index) else mask
        }
        private fun clear() { move = null; main.removeCallbacks(timeout) }
        private fun finishWithStockTarget() {
            val host = move?.presenter?.get()
            clear()
            runCatching { if (host != null && stockTarget.isFinite() && allowed(host)) dispatch.invoke(host, stockTarget, true) }
                .onFailure { disable() }
        }
        private fun disable() {
            failed = true
            clear()
            status(Settings.STATE_ERROR, "Zoom contract failed · stock zoom")
        }
        fun status(value: String, path: String = "Oplus ZoomPresenter · vendor and capture request") {
            state = value
            mechanism = path
            lastStatus = SystemClock.uptimeMillis()
            val details = Bundle().apply {
                putInt("build", BuildConfig.VERSION_CODE)
                putString("state", value)
                putString("mechanism", path)
                if (config.diagnostics) {
                    putFloat("target", stockTarget)
                    putFloat("applied", reportedRatio)
                }
            }
            runCatching { app.contentResolver.call(SettingsProvider.CONTENT_URI, Settings.REPORT, null, details) }
        }
    }
}
