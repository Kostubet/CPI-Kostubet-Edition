package com.cosmod.launcherglass.ui

import android.os.Bundle
import com.cosmod.launcherglass.R
import com.cosmod.launcherglass.util.IconPalette
import com.cosmod.launcherglass.ui.SettingsProvider.Companion as Keys

/** Applies a reversible profile to the current icon and widget material settings. */
internal object DesktopAppearance {
    const val MODE = "desktop_appearance_mode"
    const val THEME = "desktop_appearance_theme"
    const val HUE = "desktop_appearance_hue"
    const val SATURATION = "desktop_appearance_saturation"
    const val TINT_TRANSPARENCY = "desktop_appearance_tint_transparency"
    const val COLOR_SOURCE = "desktop_appearance_color_source"
    const val LARGE_ICONS = "desktop_appearance_large_icons"
    const val DIM_WALLPAPER = "desktop_appearance_dim_wallpaper"
    const val LARGE_ICON_PERCENT = 118
    val defaults = mapOf(MODE to 0, THEME to 0, HUE to -1, SATURATION to -1, TINT_TRANSPARENCY to -1, COLOR_SOURCE to 0, LARGE_ICONS to 0, DIM_WALLPAPER to 0)
    val ranges = mapOf(MODE to 0..4, THEME to 0..2, HUE to -1..360, SATURATION to -1..100, TINT_TRANSPARENCY to -1..100, COLOR_SOURCE to 0..2,
        LARGE_ICONS to 0..1, DIM_WALLPAPER to 0..1)

    enum class Mode(val label: Int) {
        MANUAL(R.string.desktop_appearance_manual),
        STOCK(R.string.desktop_appearance_stock),
        DARK(R.string.desktop_appearance_dark),
        CLEAR(R.string.desktop_appearance_clear),
        TINTED(R.string.desktop_appearance_tinted)
    }

    enum class Theme(val label: Int) {
        AUTO(R.string.desktop_appearance_auto),
        LIGHT(R.string.desktop_appearance_light),
        DARK(R.string.desktop_appearance_dark)
    }

    enum class ColorSource { MANUAL, MONOCHROME, WALLPAPER }

    data class Profile(
        val mode: Mode, val theme: Theme, val hue: Int, val saturation: Int,
        val colorSource: ColorSource = ColorSource.MANUAL,
        val tintTransparency: Int = -1,
    ) {
        val hasTintControls get() = mode == Mode.TINTED

        fun tintOpacity(previous: Int) = if (tintTransparency < 0) previous else 100 - tintTransparency

        fun widgetTintTransparency(monochrome: Boolean) = if (mode != Mode.TINTED) 0
            else if (tintTransparency >= 0) tintTransparency else if (monochrome) 0 else 48

        val themeChoices: List<Theme> get() = when (mode) {
            Mode.DARK -> listOf(Theme.DARK, Theme.AUTO)
            Mode.CLEAR, Mode.TINTED -> listOf(Theme.LIGHT, Theme.DARK, Theme.AUTO)
            else -> emptyList()
        }

        fun selectMode(next: Mode): Profile {
            val updated = copy(mode = next)
            val choices = updated.themeChoices
            return if (choices.isEmpty() || theme in choices) updated else updated.copy(theme = Theme.DARK)
        }

        fun dark(systemDark: Boolean) = when (theme) {
            Theme.AUTO -> systemDark
            Theme.LIGHT -> mode == Mode.DARK
            Theme.DARK -> true
        }

        fun brightness(systemDark: Boolean) = when (mode) {
            Mode.DARK -> if (dark(systemDark)) 0 else 12
            Mode.CLEAR -> if (dark(systemDark)) 4 else 12
            Mode.TINTED -> if (dark(systemDark)) 36 else 66
            else -> 0
        }

        fun iconDark(systemDark: Boolean) = dark(systemDark)

        fun usesLightGlass(systemDark: Boolean) = mode == Mode.CLEAR && !iconDark(systemDark)

        fun clearOpacity(opacity: Int, systemDark: Boolean) =
            if (usesLightGlass(systemDark)) 8 else opacity.coerceIn(32, 54)

        fun widgetContentOpacity(opacity: Int, systemDark: Boolean) =
            if (usesLightGlass(systemDark)) minOf(opacity, 32) else opacity
    }

    fun read(settings: Bundle, systemDark: Boolean): Profile {
        val mode = Mode.entries.getOrElse(settings.getInt(MODE)) { Mode.MANUAL }
        val theme = Theme.entries.getOrElse(settings.getInt(THEME)) { Theme.AUTO }
        val source = ColorSource.entries.getOrElse(settings.getInt(COLOR_SOURCE)) { ColorSource.MANUAL }
        val profile = Profile(mode, theme, 0, 0, source).selectMode(mode)
        val dark = profile.iconDark(systemDark)
        val keys = Keys.colorKeys(dark)
        fun color(custom: String, manual: String, max: Int): Int {
            val value = settings.getInt(custom, -1)
            return (if (value >= 0) value else settings.getInt(manual, SettingsDefaults.integer(manual))).coerceIn(0, max)
        }
        return profile.copy(hue = color(HUE, keys[0], 360), saturation = color(SATURATION, keys[1], 100),
            tintTransparency = settings.getInt(TINT_TRANSPARENCY, -1).coerceIn(-1, 100))
    }

    fun apply(settings: Bundle, profile: Profile, systemDark: Boolean) {
        if (profile.mode == Mode.MANUAL || !settings.getBoolean(Keys.KEY_ENABLED, true)) return
        val iconSection = FeatureSections.key("ICONS")
        val widgetSection = FeatureSections.key("PHOTO")
        if (profile.mode == Mode.STOCK) {
            settings.putBoolean(iconSection, false)
            settings.putInt(Keys.KEY_COLOR_MODE, 0)
            settings.putBoolean("icon_logo_tint", false)
            settings.putBoolean(Keys.KEY_PHOTO_WIDGET_BLUR, false)
            settings.putBoolean(Keys.KEY_PHOTO_WIDGET_TINT, false)
            settings.putBoolean("widget_logo_tint", false)
            settings.putBoolean("widget_elements_follow_background", false)
            return
        }

        val dark = profile.dark(systemDark)
        val tinted = profile.mode == Mode.TINTED
        val transparent = profile.mode == Mode.CLEAR
        val lightGlass = profile.usesLightGlass(systemDark)
        val brightness = profile.brightness(systemDark)
        val hue = if (tinted) profile.hue else 0
        val wallpaper = tinted && profile.colorSource == ColorSource.WALLPAPER
        val monochrome = tinted && profile.colorSource == ColorSource.MONOCHROME
        val saturation = when {
            wallpaper -> 50
            tinted && !monochrome -> profile.saturation
            else -> 0
        }
        if (settings.getBoolean(iconSection, true)) {
            settings.putBoolean(Keys.KEY_BLUR_ENABLED, true)
            settings.putBoolean(Keys.KEY_BLUR_TONE_ENABLED, true)
            settings.putInt(Keys.KEY_COLOR_MODE, 1)
            settings.putInt(Keys.KEY_ICON_APPEARANCE, if (transparent) 0 else 1)
            settings.putBoolean(IconPalette.FOLLOW_WALLPAPER, wallpaper)
            val keys = Keys.colorKeys(profile.iconDark(systemDark))
            settings.putInt(keys[0], hue)
            settings.putInt(keys[1], saturation)
            settings.putInt(keys[2], brightness)
            settings.putInt(Keys.KEY_CLEAR_BRIGHTNESS, brightness)
            if (lightGlass) {
                settings.putInt(Keys.KEY_BLUR_VIBRANCY, 0)
                settings.putInt(Keys.KEY_BLUR_LIGHTENING, 50)
            }
            settings.putBoolean("icon_logo_tint", !wallpaper)
            settings.putInt("icon_logo_hue", hue)
            settings.putInt("icon_logo_saturation", if (tinted) saturation / 3 else 0)
            settings.putInt("icon_logo_brightness", when {
                lightGlass -> 94
                profile.mode == Mode.DARK && dark -> 92
                else -> 100
            })
            when (profile.mode) {
                Mode.DARK -> {
                    settings.putInt(Keys.KEY_BG_OPACITY, 100)
                    settings.putInt(Keys.KEY_BLUR_VIBRANCY, 0)
                }
                Mode.CLEAR, Mode.TINTED -> {
                    settings.putInt(Keys.KEY_BLUR_STYLE, Keys.BLUR_STYLE_LIQUID)
                    if (tinted) settings.putInt(Keys.KEY_BLUR_SATURATION, 100)
                    val opacity = settings.getInt(Keys.KEY_BG_OPACITY, SettingsDefaults.integer(Keys.KEY_BG_OPACITY))
                    settings.putInt(Keys.KEY_BG_OPACITY, if (transparent) profile.clearOpacity(opacity, systemDark)
                        else profile.tintOpacity(opacity.coerceIn(16, if (dark) 62 else 45)))
                }
                else -> Unit
            }
        }

        if (settings.getBoolean(widgetSection, true)) {
            settings.putBoolean(Keys.KEY_PHOTO_WIDGET_BLUR, true)
            if (profile.mode != Mode.DARK) settings.putInt(Keys.KEY_PHOTO_WIDGET_MATERIAL, 1)
            settings.putBoolean(Keys.KEY_PHOTO_WIDGET_TINT, !transparent)
            settings.putBoolean(WidgetSettings.WALLPAPER_TINT, wallpaper)
            settings.putInt(WidgetSettings.HUE, hue)
            settings.putInt(WidgetSettings.TINT_SATURATION, saturation)
            settings.putInt(WidgetSettings.LIGHTNESS, 0)
            settings.putInt("photo_widget_tint_brightness", if (profile.mode == Mode.DARK) 12 + brightness else brightness)
            settings.putInt(WidgetSettings.TINT_TRANSPARENCY, profile.widgetTintTransparency(monochrome))
            if (transparent) {
                val shade = settings.getInt(Keys.KEY_PHOTO_WIDGET_SHADE, SettingsDefaults.integer(Keys.KEY_PHOTO_WIDGET_SHADE))
                settings.putInt(Keys.KEY_PHOTO_WIDGET_SHADE, if (dark) shade.coerceIn(12, 35) else 0)
            }
            if (lightGlass) {
                settings.putInt(WidgetSettings.SCOPE, WidgetSettings.ALL)
                val opacity = settings.getInt(Keys.KEY_PHOTO_WIDGET_OPACITY, SettingsDefaults.integer(Keys.KEY_PHOTO_WIDGET_OPACITY))
                settings.putInt(Keys.KEY_PHOTO_WIDGET_OPACITY, profile.widgetContentOpacity(opacity, systemDark))
            }
            // Tint through the native foreground path without flattening widget text styles.
            settings.putBoolean("widget_elements_follow_background", monochrome || wallpaper)
            settings.putBoolean("widget_logo_tint", false)
        }
    }

    fun affectsManualSettings(key: String): Boolean =
        key in setOf(Keys.KEY_BLUR_ENABLED, Keys.KEY_BLUR_STYLE, Keys.KEY_COLOR_MODE, Keys.KEY_ICON_APPEARANCE,
            Keys.KEY_BG_OPACITY, Keys.KEY_BLUR_SATURATION, Keys.KEY_BLUR_TONE_ENABLED,
            Keys.KEY_CLEAR_BRIGHTNESS, Keys.KEY_PHOTO_WIDGET_BLUR, "widget_elements_follow_background",
            Keys.KEY_PHOTO_WIDGET_MATERIAL, Keys.KEY_PHOTO_WIDGET_TINT, Keys.KEY_PHOTO_WIDGET_OPACITY,
            WidgetSettings.SCOPE, IconPalette.FOLLOW_WALLPAPER) ||
            key in Keys.colorKeys(true) || key in Keys.colorKeys(false) ||
            key.startsWith("icon_logo_") || key.startsWith("widget_logo_") || key.startsWith("photo_widget_tint_") ||
            key == WidgetSettings.WALLPAPER_TINT || key == Keys.KEY_PHOTO_WIDGET_SHADE

    fun copyForManualEdit(settings: Bundle, profile: Profile, systemDark: Boolean, nativeDark: Boolean): Bundle {
        val manual = Bundle(settings)
        if (profile.mode == Mode.MANUAL || profile.mode == Mode.STOCK) return manual
        apply(manual, profile, systemDark)
        val effectiveKeys = Keys.colorKeys(profile.iconDark(systemDark))
        Keys.colorKeys(nativeDark).zip(effectiveKeys).forEach { (native, effective) ->
            manual.putInt(native, manual.getInt(effective))
        }
        return manual
    }
}
