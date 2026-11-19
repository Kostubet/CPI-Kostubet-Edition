package com.cosmod.launcherglass.ui

import android.os.Bundle
import com.cosmod.launcherglass.R

internal object SoftGlassSettings {
    const val SIDEBAR_PACKAGE = "com.coloros.smartsidebar"

    enum class Surface(val prefix: String, val section: String, val shaderRole: Float) {
        CONTROL("shade_glass_control_soft_", "SHADE", 1f),
        SHADE("shade_glass_notifications_soft_", "SHADE", 0f),
        LOCK("lock_notifications_soft_", "LOCKSCREEN", 3f),
        SIDEBAR("sidebar_soft_", "SIDEBAR", 5f);

        val enabled = prefix + "enabled"
        val white = prefix + "keep_white"
        val tilt = prefix + "tilt"
        fun key(parameter: Parameter) = prefix + parameter.id
    }

    enum class Parameter(val id: String, val title: Int, val range: IntRange = 0..300, val default: Int = 100) {
        STRENGTH("strength", R.string.soft_glass_strength, 0..100),
        BLUR("blur", R.string.soft_glass_blur),
        WIDTH("width", R.string.soft_glass_width),
        SHINE("shine", R.string.soft_glass_shine),
        REFRACTION("refraction", R.string.shade_glass_refraction),
        SATURATION("saturation", R.string.ui_saturation),
        EXPOSURE("exposure", R.string.soft_glass_exposure, -100..100, 0),
        DIMMING("dimming", R.string.soft_glass_dimming, -200..200, 0),
        WARMTH("warmth", R.string.soft_glass_warmth, -200..200, 0),
        CONTRAST("contrast", R.string.shade_glass_contrast, -150..150, 0),
        ANGLE("angle", R.string.soft_glass_direction, -180..180, 0),
        RESPONSE("response", R.string.soft_glass_response, 0..400),
    }

    private val keys = Surface.entries.flatMap { surface -> Parameter.entries.map { surface.key(it) to it } }.toMap()
    val booleans = Surface.entries.flatMap { listOf(it.enabled, it.white, it.tilt) }.associateWith { false }
    val integers = keys.mapValues { it.value.default }
    fun range(key: String) = keys[key]?.range

    data class Profile(val values: List<Int>, val white: Boolean, val tilt: Boolean) {
        fun value(parameter: Parameter) = values[parameter.ordinal]
        operator fun get(parameter: Parameter) = value(parameter) / 100f
    }

    fun read(bundle: Bundle, surface: Surface): Profile? {
        if (!bundle.getBoolean(SettingsProvider.KEY_ENABLED) || !bundle.getBoolean(surface.enabled) ||
            !bundle.getBoolean(FeatureSections.key(surface.section), true)) return null
        val values = Parameter.entries.map { bundle.getInt(surface.key(it), it.default).coerceIn(it.range) }
        if (values[Parameter.STRENGTH.ordinal] == 0) return null
        return Profile(values, bundle.getBoolean(surface.white), bundle.getBoolean(surface.tilt))
    }
}
