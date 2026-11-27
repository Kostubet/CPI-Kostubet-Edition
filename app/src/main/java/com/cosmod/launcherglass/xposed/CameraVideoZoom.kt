package com.cosmod.launcherglass.xposed

import android.app.Application
import android.content.Context
import android.hardware.camera2.CaptureResult
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.cosmod.launcherglass.BuildConfig
import com.cosmod.launcherglass.camera.CameraZoomSettings as Settings
import com.cosmod.launcherglass.camera.ZoomFrameWatchdog
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
    private const val VENDOR_PATH = "Oplus ZoomPresenter · vendor and capture request"
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
            var stockTarget: Float,
            var stockFinal: Float,
            val lower: Float,
            val upper: Float,
            val watchdog: ZoomFrameWatchdog,
        )

        private val main = Handler(Looper.getMainLooper())
        private val input = ThreadLocal<Input?>()
        private val frameQueued = AtomicBoolean(false)
        @Volatile private var config = Config()
        @Volatile private var move: Move? = null
        @Volatile private var stalledPresenter: WeakReference<Any>? = null
        private var reportedRatio = Float.NaN
        private var state = Settings.STATE_READY
        private var mechanism = VENDOR_PATH
        @Volatile private var lastStatus = Long.MIN_VALUE / 2
        private var stockTarget = Float.NaN
        private var stalledAt = 0L
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

        private val timeout = object : Runnable {
            override fun run() {
                val active = move ?: return
                val now = SystemClock.uptimeMillis()
                val remaining = active.watchdog.remaining(now)
                if (remaining > 0) main.postDelayed(this, remaining)
                else {
                    active.presenter.get()?.let { waitForPreview(it, now) }
                    finishWithStockTarget("Camera frames paused · stock zoom")
                }
            }
        }

        fun observeSettings() {
            val observer = HostSettingsReload(main, read = {
                val prefs = app.contentResolver.call(SettingsProvider.CONTENT_URI, Settings.READ, null, null)
                prefs?.let {
                    Config(it.getBoolean(Settings.ENABLED), it.getBoolean(Settings.DIAGNOSTICS),
                        Input.entries.filter { input -> it.getBoolean(input.key) }.toSet(),
                        Settings.tuning { key -> it.getInt(key, Settings.integers.getValue(key)) })
                }
            }, apply = { next ->
                val previous = config
                config = next
                if (previous.enabled && (!next.enabled || previous.inputs != next.inputs)) finishWithStockTarget()
                if (previous.diagnostics != next.diagnostics) {
                    stockTarget = Float.NaN
                    reportedRatio = Float.NaN
                    status(state, mechanism, force = true)
                }
            })
            runCatching {
                app.contentResolver.registerContentObserver(SettingsProvider.CONTENT_URI, false, observer)
            }
            observer.reloadImmediately()
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
                "isPaused", "isFrontCamera", "isVideoRecordStarting", "isVideoRecordStopping",
                "isVideoRecordSwitching", "isSwitchingCamera")
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
            val sessionState = type("com.oplus.camera.protocal.event.message.deviceProtocol.deviceControlCallback.EventMessageSessionState")
            val session = presenter.getDeclaredMethod("onSessionCallback", sessionState)
            val sessionStage = sessionState.getDeclaredField("b").apply { isAccessible = true }
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
                        val movement = move
                        val host = param.thisObject
                        val waiting = stalledPresenter?.get() === host
                        if (failed || movement == null && !waiting && (!config.diagnostics || now - lastStatus < 500)) return
                        if (movement != null && movement.presenter.get() !== host && !waiting) return
                        val data = param.args[0] ?: return
                        if (runCatching { stage.getInt(data) }.getOrNull() != 1) return
                        if (!frameQueued.compareAndSet(false, true)) return
                        val applied = if (config.diagnostics && now - lastStatus >= 350) {
                            runCatching {
                                resultGet.invoke(null, CaptureResult.CONTROL_ZOOM_RATIO, getResult.invoke(data)) as? Float
                            }.getOrNull() ?: Float.NaN
                        } else Float.NaN
                        main.post {
                            frameQueued.set(false)
                            if (stalledPresenter?.get() === host && now >= stalledAt) stalledPresenter = null
                            if (movement !== move || failed) return@post
                            if (config.diagnostics && applied.isFinite()) reportedRatio = applied
                            runCatching { onFrame(host, now) }.onFailure { disable() }
                        }
                    }
                })
                fun cancel(host: Any) {
                    val movement = move?.takeIf { it.presenter.get() === host } ?: return
                    val work = Runnable {
                        if (move === movement) {
                            clear()
                            status(Settings.STATE_READY)
                        }
                    }
                    if (Looper.myLooper() == Looper.getMainLooper()) work.run() else main.post(work)
                }
                hook(pausing, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) = cancel(param.thisObject)
                })
                hook(session, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val stage = runCatching { sessionStage.getInt(param.args[0]) }.getOrNull()
                        if (stage == 1 || stage == 5) cancel(param.thisObject)
                    }
                })
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
            val running = move?.takeIf { it.presenter.get() === host }
            val intercept = settings.enabled && source in settings.inputs && stalledPresenter?.get() !== host
            val eligible = param.args[2] == true && source != null && (intercept || settings.diagnostics) && allowed(host)
            if (settings.diagnostics && target.isFinite() && eligible) {
                stockTarget = target
                if (running == null && SystemClock.uptimeMillis() - lastStatus >= 350) status(Settings.STATE_OBSERVING)
            }
            if (!intercept || !eligible) {
                if (running != null && param.args[2] == true) {
                    clear()
                    status(Settings.STATE_READY)
                }
                return
            }
            val current = running?.lastCommand ?: currentRatio.getFloat(zoomState.get(host))
            val final = param.args[1] as Float
            val controls = zoomUi.get(host) ?: return
            val min = recordMin.getFloat(controls)
            val max = recordMax.getFloat(controls)
            if (!current.isFinite() || !target.isFinite() || !final.isFinite() ||
                !min.isFinite() || !max.isFinite() || min <= 0f || max < min ||
                current !in min..max || target !in min..max || abs(target - current) < .001f) {
                if (running != null) {
                    clear()
                    status(Settings.STATE_READY)
                }
                return
            }
            val region = region(host, current)
            if (region(host, target) != region || region == 0 && sat.invoke(host, "func_sat_camera") == true) {
                clear()
                status(if (settings.diagnostics) Settings.STATE_OBSERVING else Settings.STATE_READY, "Lens boundary · stock zoom")
                return
            }
            val now = SystemClock.uptimeMillis()
            if (running != null && running.watchdog.remaining(now) == 0L) {
                waitForPreview(host, now)
                clear()
                status(Settings.STATE_READY, "Camera frames paused · stock zoom")
                return
            }
            val active = running ?: Move(WeakReference(host), ZoomSmoothing(current.toDouble()), region,
                current, target, final, min, max, ZoomFrameWatchdog(now))
            active.engine.aim(target.toDouble(), now / 1000.0, source.gesture)
            active.stockTarget = target
            active.stockFinal = final
            move = active
            if (running == null) {
                main.removeCallbacks(timeout)
                main.postDelayed(timeout, ZoomFrameWatchdog.TIMEOUT_MS)
            }
            // Keep the stock UI and listeners; the preview callback alone submits the intermediate ratios.
            param.args[0] = current
            param.args[2] = false
            if (state != Settings.STATE_ACTIVE) status(Settings.STATE_ACTIVE)
        }

        private fun onFrame(host: Any, time: Long) {
            val active = move
            if (active?.presenter?.get() !== host) {
                if (config.diagnostics && time - lastStatus >= 500 && allowed(host)) status(Settings.STATE_OBSERVING)
                return
            }
            if (!allowed(host)) {
                clear()
                status(Settings.STATE_READY)
                return
            }
            if (!config.enabled) { finishWithStockTarget(); return }
            val movement = requireNotNull(active)
            if (!movement.watchdog.acceptFrame(time)) return
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
        private fun waitForPreview(host: Any, now: Long) {
            stalledAt = now
            stalledPresenter = WeakReference(host)
        }
        private fun clear() { move = null; main.removeCallbacks(timeout) }
        private fun finishWithStockTarget(path: String = VENDOR_PATH) {
            val active = move ?: return
            val host = active.presenter.get()
            clear()
            runCatching {
                if (host != null && allowed(host)) {
                    val previous = input.get()
                    input.remove()
                    try { submit.invoke(host, active.stockTarget, active.stockFinal, true) }
                    finally { input.set(previous) }
                }
            }
                .onSuccess { status(Settings.STATE_READY, path) }
                .onFailure { disable() }
        }
        private fun disable() {
            failed = true
            clear()
            status(Settings.STATE_ERROR, "Zoom contract failed · stock zoom")
        }
        fun status(value: String, path: String = VENDOR_PATH, force: Boolean = false) {
            val now = SystemClock.uptimeMillis()
            if (!force && value == state && path == mechanism && now - lastStatus < 350) return
            state = value
            mechanism = path
            lastStatus = now
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
