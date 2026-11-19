package com.cosmod.launcherglass.ui

import android.os.Build
import android.os.Bundle
import com.cosmod.launcherglass.ui.SettingsProvider.Companion as Keys

/** Overrides the effective material without rewriting the stored settings for each surface. */
internal object GlobalGlassSettings {
    const val ENABLED="global_glass_enabled"
    const val STYLE="global_glass_style"
    const val SOFT_WIDTH="global_glass_soft_width"
    const val BLUR="global_glass_blur"
    const val BLUR_OPACITY="global_glass_blur_opacity"
    const val OPACITY="global_glass_opacity"
    const val WIDTH="global_glass_width"
    const val LENS_HEIGHT="global_glass_lens_height"
    const val DISTORTION="global_glass_distortion"
    const val DISPERSION="global_glass_dispersion"
    val defaults=mapOf(STYLE to 1,SOFT_WIDTH to GlassEngineSettings.SOFT_WIDTH_DEFAULT,BLUR to 12,BLUR_OPACITY to 100,OPACITY to 30,WIDTH to 42,DISTORTION to 80,DISPERSION to 0,LENS_HEIGHT to GlassEngineSettings.Parameter.LENS_HEIGHT.default)
    val maximums=mapOf(STYLE to 2,SOFT_WIDTH to GlassEngineSettings.SOFT_WIDTH_MAX,BLUR to 32,DISTORTION to 300,LENS_HEIGHT to GlassEngineSettings.Parameter.LENS_HEIGHT.max)

    fun opacity(bundle:Bundle)=if(bundle.getBoolean(ENABLED))bundle.getInt(OPACITY,defaults.getValue(OPACITY)) else 0

    fun apply(bundle:Bundle){
        if(!bundle.getBoolean(ENABLED) || !bundle.getBoolean(Keys.KEY_ENABLED,true))return
        fun value(key:String)=bundle.getInt(key,defaults.getValue(key)).coerceIn(0,maximums[key] ?: 100)
        fun set(value:Int,vararg keys:String){for(key in keys)bundle.putInt(key,value)}
        val material=if(Build.VERSION.SDK_INT>=33)value(STYLE)else 0
        val mode=material.coerceAtMost(1)
        for(scope in GlassEngineSettings.materialScopes) {
            bundle.putBoolean(GlassEngineSettings.softKey(scope),material==2)
            if(material==2)set(value(SOFT_WIDTH),GlassEngineSettings.softWidthKey(scope))
        }
        val blur=value(BLUR)
        val opacity=value(OPACITY)
        val blurOpacity=value(BLUR_OPACITY)
        for(scope in GlassEngineSettings.materialScopes){
            val key=GlassEngineSettings.blurOpacityKey(scope)
            if(key in GlassEngineSettings.integers)bundle.putInt(key,blurOpacity)
        }
        val lensHeight=value(LENS_HEIGHT)
        val heightParameter=GlassEngineSettings.Parameter.LENS_HEIGHT
        for(scope in GlassEngineSettings.materialScopes + ShadeGlassSettings.Panel.entries.map {it.newEngine}){
            set(lensHeight,GlassEngineSettings.key(scope,heightParameter))
        }
        bundle.putInt(GlassEngineSettings.key(GlassEngineSettings.LARGE_FOLDERS,GlassEngineSettings.Parameter.BLUR_RADIUS),blur)
        val width=if(mode==1)value(WIDTH)else 0
        val distortion=if(mode==1)value(DISTORTION)else 0
        val dispersion=if(mode==1)value(DISPERSION)else 0
        fun iconMaterial(prefix:String){
            if(!bundle.getBoolean(prefix+Keys.KEY_BLUR_TONE_ENABLED,true)){
                bundle.putInt(prefix+Keys.KEY_BLUR_SATURATION,100)
                bundle.putInt(prefix+Keys.KEY_BLUR_VIBRANCY,0)
                bundle.putInt(prefix+Keys.KEY_BLUR_LIGHTENING,50)
            }
            bundle.putBoolean(prefix+Keys.KEY_BLUR_TONE_ENABLED,true)
            bundle.putBoolean(prefix+Keys.KEY_BLUR_RADIUS_ENABLED,true)
            set(mode,prefix+Keys.KEY_BLUR_STYLE)
            set(blur,prefix+Keys.KEY_BLUR_RADIUS)
            set(opacity,prefix+Keys.KEY_BG_OPACITY)
            set(width,prefix+Keys.KEY_LIQUID_WIDTH)
            set(distortion,prefix+Keys.KEY_LIQUID_DISTORTION)
        }
        iconMaterial("")
        iconMaterial("folder_")
        set(mode,Keys.KEY_PHOTO_WIDGET_MATERIAL,"volume_glass_style","large_folder_glass_style")
        for(key in listOf(Keys.KEY_DOCK_STYLE,"indicator_glass_style")){
            if(bundle.getInt(key)>0)set(mode+1,key)
        }
        set(blur,WidgetSettings.RADIUS,Keys.KEY_DOCK_BLUR,"indicator_glass_blur","volume_glass_blur",
            "banner_glass_blur","island_glass_blur")
        set(opacity,Keys.KEY_DOCK_OPACITY,"indicator_glass_opacity","volume_glass_opacity",
            "banner_glass_opacity","island_glass_opacity","large_folder_glass_opacity")
        set(width,Keys.KEY_PHOTO_WIDGET_LIQUID_WIDTH,Keys.KEY_DOCK_LIQUID_WIDTH,"indicator_glass_width",
            "volume_glass_width","banner_glass_width","island_glass_width","large_folder_glass_width")
        set(distortion,Keys.KEY_PHOTO_WIDGET_LIQUID_DISTORTION,Keys.KEY_DOCK_LIQUID_DISTORTION,
            "indicator_glass_distortion","volume_glass_distortion","banner_glass_distortion",
            "island_glass_distortion","large_folder_glass_strength")
        for(key in GlassDispersionSettings.defaults.keys)set(dispersion,key)
        set(dispersion,"indicator_glass_dispersion","volume_glass_dispersion")
        for(surface in LockscreenSettings.Surface.entries){
            set(mode,surface.key("style"))
            set(blur,surface.key("blur"))
            set(opacity,surface.key("opacity"))
            set(width,surface.key("width"))
            set(distortion,surface.key("distortion"))
            set(dispersion,surface.key("dispersion"))
        }
    }
}
