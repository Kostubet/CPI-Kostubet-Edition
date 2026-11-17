package com.cosmod.launcherglass.ui

internal object ShadeGlassSettings {
    const val CONTROL_BACKGROUND = "shade_glass_control_background_enabled"
    const val CONTROL_BACKGROUND_BLUR_OPACITY = "shade_glass_control_background_blur_opacity"
    const val CONTROL_BACKGROUND_RADIUS = "shade_glass_control_background_radius"
    const val CONTROL_BACKGROUND_DARKNESS = "shade_glass_control_background_darkness"
    const val CONTROL_BACKGROUND_BRIGHTNESS = "shade_glass_control_background_brightness"
    const val CONTROL_NATIVE_BLUR = "shade_glass_control_native_blur"
    enum class Panel { CONTROL, NOTIFICATIONS;
        val prefix = "shade_glass_${name.lowercase(java.util.Locale.ROOT)}_"
        val enabled = prefix + "enabled"
        val highRefresh = prefix + "120fps"
        val newEngine = prefix + "kyant_engine"
        val components get() = Component.entries.filter { it.panel == this }
        fun key(setting: Parameter) = prefix + setting.name.lowercase(java.util.Locale.ROOT)
    }

    enum class Component(val panel: Panel) {
        ROUND(Panel.CONTROL), LARGE(Panel.CONTROL), MEDIA(Panel.CONTROL),
        BRIGHTNESS(Panel.CONTROL), VOLUME(Panel.CONTROL), OTHER_TILE(Panel.CONTROL),
        COMPACT(Panel.NOTIFICATIONS), EXPANDED(Panel.NOTIFICATIONS), GROUP(Panel.NOTIFICATIONS),
        ACTION(Panel.NOTIFICATIONS), CLEAR(Panel.NOTIFICATIONS);

        private val prefix = panel.prefix + name.lowercase(java.util.Locale.ROOT) + "_"
        val mode = prefix + "mode"
        val radius = prefix + "radius"
        val opacity = prefix + "opacity"
        val blurOpacity = prefix + "blur_opacity"
    }

    enum class Parameter(val range: IntRange, val default: Int) {
        MATERIAL(0..1, 0), BLUR(0..1, 1), RADIUS(0..60, 22), BLUR_STRENGTH(0..200, 100),
        BLUR_OPACITY(0..100, 90), QUALITY(0..2, 1), LIVE(0..1, 1), ECONOMY(0..1, 0),
        REFRACTION(0..1, 1), STRENGTH(0..100, 38), LENS(0..100, 46), EDGE(0..100, 70),
        DISPERSION(0..1, 1), DISPERSION_STRENGTH(0..100, 24),
        HIGHLIGHT(0..1, 1), HIGHLIGHT_BRIGHTNESS(0..100, 72), HIGHLIGHT_OPACITY(0..100, 48),
        HIGHLIGHT_WIDTH(1..100, 28), HIGHLIGHT_COLOR(0..0xffffff, 0xffffff),
        RIM(0..100, 32), REFLECTION(0..100, 40), COLOR(0..0xffffff, 0x252938),
        OPACITY(0..100, 32), SATURATION(0..200, 110), CONTRAST(0..200, 100),
        BRIGHTNESS(0..200, 100), TINT(0..100, 32), AUTO_THEME(0..1, 1),
        CORNER(0..60, 28), INNER_SHADOW(0..100, 25), OUTER_SHADOW(0..100, 22),
        DEPTH(0..100, 30), HIGH_REFRESH(0..1, 0), NEW_ENGINE(0..1, 0),
    }

    private val retiredBlurParameters = setOf(Parameter.BLUR,Parameter.BLUR_STRENGTH,
        Parameter.QUALITY,Parameter.LIVE,Parameter.ECONOMY,Parameter.HIGH_REFRESH,Parameter.NEW_ENGINE)

    enum class Preset { IOS, CLEAR, DARK, FROSTED, CUSTOM;
        fun values(): Map<Parameter, Int> {
            val changes = when (this) {
                IOS -> emptyMap()
                CLEAR -> mapOf(Parameter.RADIUS to 7, Parameter.OPACITY to 14, Parameter.LENS to 34, Parameter.TINT to 10)
                DARK -> mapOf(Parameter.RADIUS to 32, Parameter.OPACITY to 62, Parameter.TINT to 45, Parameter.AUTO_THEME to 0)
                FROSTED -> mapOf(Parameter.MATERIAL to 1, Parameter.RADIUS to 40, Parameter.OPACITY to 46, Parameter.TINT to 12)
                CUSTOM -> return emptyMap()
            }
            return Parameter.entries.filterNot { it in retiredBlurParameters }
                .associateWith { changes[it] ?: it.default }
        }
    }

    class Profile(private val values: Map<Parameter, Int>, val enabled: Boolean,
        val engineOptics:GlassEngineSettings.Optics=GlassEngineSettings.Optics()) {
        operator fun get(parameter: Parameter) = values.getValue(parameter)
        fun on(parameter: Parameter) = get(parameter) != 0
        fun fraction(parameter: Parameter) = get(parameter) / 100f
    }

    val booleans = Panel.entries.flatMap { listOf(it.enabled,it.highRefresh,it.newEngine) }.associateWith { false } +
        mapOf(CONTROL_BACKGROUND to false, CONTROL_NATIVE_BLUR to true)
    private val parameterKeys = Panel.entries.flatMap { panel ->
        Parameter.entries.filterNot { it in retiredBlurParameters }
            .map { panel.key(it) to it }
    }.toMap()
    private val modes = Component.entries.map { it.mode }.toSet()
    private val radii = Component.entries.map { it.radius }.toSet()
    private val opacities = Component.entries.flatMap { listOf(it.opacity,it.blurOpacity) }.toSet()
    val integers = parameterKeys.mapValues { it.value.default } + modes.associateWith { 0 } +
        (radii + opacities).associateWith { -1 } +
        mapOf(CONTROL_BACKGROUND_BLUR_OPACITY to 100, CONTROL_BACKGROUND_RADIUS to 28, CONTROL_BACKGROUND_DARKNESS to 22, CONTROL_BACKGROUND_BRIGHTNESS to 100)

    fun range(key: String): IntRange? = parameterKeys[key]?.range ?: when (key) {
        in modes -> 0..2
        in radii -> -1..60
        in opacities -> -1..100
        CONTROL_BACKGROUND_RADIUS -> 0..60
        CONTROL_BACKGROUND_BLUR_OPACITY, CONTROL_BACKGROUND_DARKNESS -> 0..100
        CONTROL_BACKGROUND_BRIGHTNESS -> 0..200
        else -> null
    }

    fun controlBackground(enabled: Boolean, highRefresh: Boolean = false, read: (String) -> Int): Profile {
        val values = Parameter.entries.associateWith { it.default }.toMutableMap()
        values[Parameter.QUALITY] = 2
        values[Parameter.RADIUS] = read(CONTROL_BACKGROUND_RADIUS).coerceIn(Parameter.RADIUS.range)
        values[Parameter.OPACITY] = read(CONTROL_BACKGROUND_DARKNESS).coerceIn(Parameter.OPACITY.range)
        values[Parameter.BRIGHTNESS] = read(CONTROL_BACKGROUND_BRIGHTNESS).coerceIn(Parameter.BRIGHTNESS.range)
        values[Parameter.BLUR_OPACITY] = read(CONTROL_BACKGROUND_BLUR_OPACITY).coerceIn(Parameter.BLUR_OPACITY.range)
        values[Parameter.REFRACTION] = 0
        values[Parameter.HIGH_REFRESH] = 1
        return Profile(values,enabled)
    }

    fun profile(panel: Panel, component: Component?, enabled: Boolean, highRefresh: Boolean = false,
        newEngine: Boolean = false, engineOptics:GlassEngineSettings.Optics=GlassEngineSettings.Optics(),
        read: (String) -> Int): Profile {
        require(component == null || component.panel == panel)
        val values = Parameter.entries.associateWith { parameter ->
            when (parameter) {
                Parameter.HIGH_REFRESH, Parameter.BLUR, Parameter.LIVE -> 1
                Parameter.BLUR_STRENGTH -> 100
                Parameter.QUALITY -> 2
                Parameter.ECONOMY -> 0
                Parameter.NEW_ENGINE -> if (newEngine) 1 else 0
                else -> read(panel.key(parameter)).coerceIn(parameter.range)
            }
        }.toMutableMap()
        if (component != null && read(component.mode) == 1) {
            val radius = read(component.radius)
            val opacity = read(component.opacity)
            val blurOpacity = read(component.blurOpacity)
            if (blurOpacity >= 0) values[Parameter.BLUR_OPACITY] = blurOpacity.coerceIn(Parameter.BLUR_OPACITY.range)
            if (radius >= 0) values[Parameter.RADIUS] = radius.coerceIn(Parameter.RADIUS.range)
            if (opacity >= 0) values[Parameter.OPACITY] = opacity.coerceIn(Parameter.OPACITY.range)
        }
        return Profile(values, enabled && (component == null || read(component.mode) != 2),engineOptics)
    }

    fun panelKeys(panel: Panel) = (booleans.keys + integers.keys + GlassEngineSettings.integers.keys)
        .filter { it.startsWith(panel.prefix) }
}
