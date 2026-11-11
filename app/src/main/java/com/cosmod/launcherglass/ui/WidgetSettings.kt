package com.cosmod.launcherglass.ui

import android.content.SharedPreferences

internal object WidgetSettings {
    const val SCOPE="widget_glass_scope"
    const val PHOTO_ONLY=0
    const val ALL=1
    const val LEGACY_ALL="widget_glass_all"
    const val LEGACY_OPACITY="widget_glass_content"
    const val RADIUS="photo_widget_blur_radius"
    const val FADE_HEIGHT="photo_widget_fade_height"
    const val FADE_SMOOTHNESS="photo_widget_fade_smoothness"
    const val HUE="photo_widget_tint_hue"
    const val LIGHTNESS="photo_widget_tint_lightness"
    const val WALLPAPER_TINT="photo_widget_wallpaper_tint"
    const val TINT_SATURATION="photo_widget_tint_saturation"
    const val TINT_TRANSPARENCY="photo_widget_tint_transparency"

    fun migrate(prefs:SharedPreferences){
        if(prefs.getBoolean("unified_widget_glass",false))return
        val enabledKey=SettingsProvider.KEY_PHOTO_WIDGET_BLUR
        val photos=prefs.getBoolean(enabledKey,false)
        val all=prefs.getBoolean(LEGACY_ALL,false)
        prefs.edit().apply {
            if(!prefs.contains(SCOPE)){
                if(photos && !all)putInt(SCOPE,PHOTO_ONLY)
                if(all){
                    putBoolean(enabledKey,true)
                    if(!photos)putInt(SettingsProvider.KEY_PHOTO_WIDGET_OPACITY,prefs.getInt(LEGACY_OPACITY,78))
                }
            }
            remove(LEGACY_ALL).remove(LEGACY_OPACITY)
            putBoolean("unified_widget_glass",true)
        }.apply()
    }
}
