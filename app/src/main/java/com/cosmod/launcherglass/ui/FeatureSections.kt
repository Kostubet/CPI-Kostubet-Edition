package com.cosmod.launcherglass.ui

import android.os.Bundle
import android.content.SharedPreferences

/** Section switches mask runtime values; stored adjustments remain intact. */
internal object FeatureSections {
    val sections=setOf("ICONS","FOLDERS","PHOTO","DOCK","BANNERS","LIVE","VOLUME","SHADE","LOCKSCREEN","SIDEBAR","LIGHT","MOTION","PERFORMANCE")
    fun key(section:String)="feature_section_${section.lowercase(java.util.Locale.ROOT)}"
    val booleans=sections.associate {key(it) to true}
    fun enabled(prefs:SharedPreferences,section:String)=section !in sections || prefs.getBoolean(key(section),true)
    fun apply(bundle:Bundle){
        fun off(section:String)=!bundle.getBoolean(key(section),true)
        fun disable(vararg keys:String){keys.forEach {bundle.putBoolean(it,false)}}
        if(off("ICONS")){bundle.putInt("color_mode",0);bundle.putInt("launcher_icon_size",100);disable("design_enabled","icon_logo_tint")}
        if(off("FOLDERS"))disable("folder_blur_enabled","folder_icons_blur_enabled")
        if(off("PHOTO"))disable("photo_widget_blur","photo_widget_tint","widget_logo_tint")
        if(off("DOCK")){bundle.putInt("dock_style",0);bundle.putInt("indicator_glass_style",0);disable("indicator_keep_visible")}
        if(off("BANNERS"))disable("banner_glass_enabled")
        if(off("LIVE"))disable("island_glass_enabled")
        if(off("VOLUME"))disable("volume_glass_enabled")
        if(off("SHADE"))disable(ShadeGlassSettings.Panel.CONTROL.enabled,
            ShadeGlassSettings.Panel.NOTIFICATIONS.enabled,ShadeGlassSettings.CONTROL_BACKGROUND)
        SoftGlassSettings.Surface.entries.forEach { if(off(it.section)) disable(it.enabled) }
        if(off("MOTION"))disable(com.cosmod.launcherglass.util.Ba31kAnimationSettings.ENABLED)
        if(off("LOCKSCREEN")){
            LockscreenSettings.Surface.entries.forEach {disable(it.key("enabled"))}
            disable(LockscreenSettings.CLEAR_BOUNCER_BLUR,LockscreenSettings.UNLOCK_ENABLED,LockscreenSettings.AOD_BLUR_ENABLED)
        }
        if(off("LIGHT")){
            disable(WallpaperGlowSettings.ENABLED,"edge_light_enabled")
            for(surface in 0 until EdgeSettings.count)disable(EdgeSettings.key(surface,"enabled"),EdgeSettings.key(surface,"dark_enabled"))
        }
        if(off("PERFORMANCE")){
            bundle.putInt("performance_profile",0);bundle.putInt("performance_resolution",100);bundle.putInt("performance_alert_rate",0)
            disable("performance_high_refresh_pacing","performance_glass_120fps",
                "performance_light_drawer","performance_live_transition_glass")
        }
    }
}
