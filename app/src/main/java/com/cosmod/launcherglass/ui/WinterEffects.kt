package com.cosmod.launcherglass.ui

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import java.util.WeakHashMap

/** App decoration only. Never installed in a hooked launcher or System UI process. */
class CpiApplication : Application(), Application.ActivityLifecycleCallbacks {
    private val layers = WeakHashMap<Activity, WinterSnowfall>()

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
        getSharedPreferences(SettingsProvider.PREFS_NAME, MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(preferencesChanged)
    }

    private val preferencesChanged = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == WinterEffects.SNOW) layers.values.forEach { it.refreshEnabled() }
    }

    override fun onActivityResumed(activity: Activity) {
        val decor = activity.window.decorView as? ViewGroup ?: return
        layers.getOrPut(activity) { WinterEffects.attach(decor) }.resume()
    }
    override fun onActivityPaused(activity: Activity) { layers[activity]?.pause() }
    override fun onActivityDestroyed(activity: Activity) { layers.remove(activity)?.dispose() }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
}

internal object WinterEffects {
    const val SNOW = "ui_winter_snow"
    const val MELODY = "ui_winter_melody"
    internal data class Surface(val radiusDp: Float, val priority: Int)
    internal val surfaces = WeakHashMap<View, Surface>()

    fun mark(view: View, radiusDp: Float = 20f, priority: Int = 0) {
        surfaces[view] = Surface(radiusDp, priority)
    }

    fun snowEnabled(context: Context) = ValueAnimator.areAnimatorsEnabled() &&
        context.getSharedPreferences(SettingsProvider.PREFS_NAME, Context.MODE_PRIVATE).getBoolean(SNOW, true)

    fun melodyEnabled(context: Context) =
        context.getSharedPreferences(SettingsProvider.PREFS_NAME, Context.MODE_PRIVATE).getBoolean(MELODY, true)

    fun attach(decor: ViewGroup) = WinterSnowfall(decor).also { decor.overlay.add(it) }
}
