package com.cosmod.launcherglass.camera

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings.Global
import com.cosmod.launcherglass.BuildConfig
import com.cosmod.launcherglass.ui.SettingsDefaults
import com.cosmod.launcherglass.ui.SettingsProvider

internal object CameraZoomSettings {
    const val PACKAGE = "com.oplus.camera"
    const val READ = "camera_zoom_read"
    const val REPORT = "camera_zoom_report"
    const val ENABLED = "camera_zoom_enabled"
    const val DIAGNOSTICS = "camera_zoom_diagnostics"
    const val MODE = "camera_zoom_mode"
    const val CURVE = "camera_zoom_curve"
    const val IN = "camera_zoom_in"
    const val OUT = "camera_zoom_out"
    const val MAX_SPEED = "camera_zoom_max"
    const val SMOOTHING = "camera_zoom_smoothing"
    const val ACCELERATION = "camera_zoom_acceleration"
    const val BRAKING = "camera_zoom_braking"
    const val JERK = "camera_zoom_jerk"
    const val INERTIA = "camera_zoom_inertia"
    const val RESPONSE = "camera_zoom_response"
    const val SENSITIVITY = "camera_zoom_sensitivity"
    const val BEZIER_FIRST = "camera_zoom_bezier_first"
    const val BEZIER_SECOND = "camera_zoom_bezier_second"
    const val PINCH = "camera_zoom_pinch"
    const val SLIDER = "camera_zoom_slider"
    const val STEPS = "camera_zoom_steps"
    const val STATE_READY = "ready"
    const val STATE_OBSERVING = "observing"
    const val STATE_UNSUPPORTED = "unsupported"
    const val STATE_ACTIVE = "active"
    const val STATE_ERROR = "error"

    val booleans = listOf(ENABLED, DIAGNOSTICS, PINCH, SLIDER, STEPS).associateWith { false }
    data class Knob(val key: String, val default: Int, val range: IntRange)
    val controls = listOf(
        Knob(IN, 220, 15..350), Knob(OUT, 245, 15..350), Knob(MAX_SPEED, 280, 15..400),
        Knob(SMOOTHING, 100, 10..160), Knob(ACCELERATION, 105, 5..160),
        Knob(BRAKING, 150, 5..180), Knob(JERK, 80, 2..100),
        Knob(INERTIA, 45, 20..280), Knob(RESPONSE, 22, 5..250),
        Knob(SENSITIVITY, 100, 20..200), Knob(BEZIER_FIRST, 20, 0..100),
        Knob(BEZIER_SECOND, 85, 0..100),
    )
    val integers = controls.associate { it.key to it.default } + mapOf(MODE to 0, CURVE to ZoomCurve.S_CURVE.ordinal)
    val ranges = controls.associate { it.key to it.range } +
        mapOf(MODE to ZoomMode.entries.indices, CURVE to ZoomCurve.entries.indices)

    fun tuning(read: (String) -> Int): ZoomTuning {
        val mode = ZoomMode.entries.getOrElse(read(MODE)) { ZoomMode.IPHONE }
        val curve = ZoomCurve.entries.getOrElse(read(CURVE)) { ZoomCurve.S_CURVE }
        val values = controls.associate { it.key to read(it.key).coerceIn(it.range) }
        fun value(key: String, divisor: Double) = values.getValue(key) / divisor
        return ZoomTuning.forMode(mode, ZoomTuning(
            value(IN, 100.0), value(OUT, 100.0), value(MAX_SPEED, 100.0), value(SMOOTHING, 10.0),
            value(ACCELERATION, 10.0), value(BRAKING, 10.0), value(JERK, 1.0), value(INERTIA, 1000.0),
            value(RESPONSE, 1000.0), value(SENSITIVITY, 100.0), curve,
            value(BEZIER_FIRST, 100.0) to value(BEZIER_SECOND, 100.0),
        ))
    }

    fun read(prefs: SharedPreferences): Bundle = Bundle().apply {
        val master = prefs.getBoolean(SettingsProvider.KEY_ENABLED, SettingsDefaults.boolean(SettingsProvider.KEY_ENABLED))
        booleans.forEach { (key, default) -> putBoolean(key, master && prefs.getBoolean(key, default)) }
        integers.forEach { (key, default) -> putInt(key, prefs.getInt(key, default)) }
    }

    private fun reportPrefs(context: Context) = context.getSharedPreferences("camera_zoom_runtime", Context.MODE_PRIVATE)
    private fun boot(context: Context) = runCatching {
        Global.getInt(context.contentResolver, "boot_count", -1)
    }.getOrDefault(-1)

    @Synchronized fun storeStatus(context: Context, prefs: SharedPreferences, extras: Bundle?) {
        if (extras == null || extras.getInt("build") != BuildConfig.VERSION_CODE) return
        val state = extras.getString("state") ?: return
        if (state !in setOf(STATE_READY, STATE_OBSERVING, STATE_UNSUPPORTED, STATE_ACTIVE, STATE_ERROR)) return
        reportPrefs(context).edit().apply {
            putString("state", state)
            putLong("time", SystemClock.elapsedRealtime())
            putInt("boot", boot(context))
            putLong("bootEpoch", System.currentTimeMillis() - SystemClock.elapsedRealtime())
            putInt("build", BuildConfig.VERSION_CODE)
            putString("mechanism", extras.getString("mechanism")?.take(80).orEmpty())
            val collectRatios = prefs.getBoolean(DIAGNOSTICS, false) &&
                prefs.getBoolean(SettingsProvider.KEY_ENABLED, SettingsDefaults.boolean(SettingsProvider.KEY_ENABLED))
            for (key in listOf("target", "applied")) {
                val ratio = if (collectRatios) extras.getFloat(key, Float.NaN) else Float.NaN
                if (ratio.isFinite() && ratio > 0f && ratio <= 200f) putFloat(key, ratio) else remove(key)
            }
        }.apply()
    }

    @Synchronized fun clearRatios(context: Context) {
        reportPrefs(context).edit().remove("target").remove("applied").apply()
    }

    fun status(context: Context): SharedPreferences? = reportPrefs(context).takeIf {
        val uptime = SystemClock.elapsedRealtime()
        val elapsed = uptime - it.getLong("time", -1)
        val currentBoot = boot(context)
        val knownBoot = it.getInt("boot", -1)
        val sameBoot = if (currentBoot >= 0 && knownBoot >= 0) knownBoot == currentBoot else {
            val epoch = it.getLong("bootEpoch", 0L)
            kotlin.math.abs(epoch - (System.currentTimeMillis() - uptime)) <= 1000L
        }
        it.getInt("build", 0) == BuildConfig.VERSION_CODE && sameBoot && elapsed in 0..90_000
    }
}
