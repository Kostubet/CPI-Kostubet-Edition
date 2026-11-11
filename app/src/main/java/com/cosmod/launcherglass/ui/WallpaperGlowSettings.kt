package com.cosmod.launcherglass.ui

object WallpaperGlowSettings {
    const val ENABLED="wallpaper_glow_enabled"
    const val STRENGTH="wallpaper_glow_strength"
    const val RADIUS="wallpaper_glow_radius"
    const val THRESHOLD="wallpaper_glow_threshold"
    const val SATURATION="wallpaper_glow_saturation"
    const val SOFTNESS="wallpaper_glow_softness"
    const val DIFFUSION="wallpaper_glow_diffusion"
    const val ICONS="wallpaper_glow_icons"
    const val FOLDERS="wallpaper_glow_folders"
    const val WIDGETS="wallpaper_glow_widgets"
    const val DOCK="wallpaper_glow_dock"
    const val BANNERS="wallpaper_glow_banners"
    const val LIVE="wallpaper_glow_live"
    val defaults=linkedMapOf(STRENGTH to 30,RADIUS to 55,THRESHOLD to 35,SATURATION to 100,SOFTNESS to 0,DIFFUSION to 50,
        ICONS to 100,FOLDERS to 100,WIDGETS to 100,DOCK to 100,BANNERS to 100,LIVE to 100)
    fun maximum(key:String)=when(key){SATURATION->200;else->100}
}
