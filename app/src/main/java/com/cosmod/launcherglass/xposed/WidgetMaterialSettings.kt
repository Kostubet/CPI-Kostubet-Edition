package com.cosmod.launcherglass.xposed

import android.os.Bundle
import com.cosmod.launcherglass.ui.GlassDispersionSettings
import com.cosmod.launcherglass.ui.SettingsDefaults
import com.cosmod.launcherglass.ui.SettingsProvider.Companion as Keys
import com.cosmod.launcherglass.ui.WidgetSettings
import com.cosmod.launcherglass.util.IconPalette
import com.cosmod.launcherglass.util.widgetTintFilter

internal data class WidgetMaterialSettings(
    val enabled:Boolean,val allWidgets:Boolean,val liquid:Boolean,val radius:Int,val customFrame:Boolean,val corner:Int,
    val shade:Int,val rim:Int,val opacity:Int,val fade:Int,val fadeHeight:Int,val fadeSmoothness:Int,
    val tint:Boolean,val hue:Int,val saturation:Int,val lightness:Int,val tintTransparency:Int,
    val width:Int,val distortion:Int,val dispersion:Int
){
    val hasFade get()=fade>0 && fadeHeight>0
    val tintFilter by lazy(LazyThreadSafetyMode.NONE) {
        if(tint)widgetTintFilter(hue,saturation,lightness,tintTransparency) else null
    }

    fun backdropChanged(previous:WidgetMaterialSettings)=
        enabled!=previous.enabled || enabled && (liquid!=previous.liquid || radius!=previous.radius)

    companion object {
        fun followsWallpaper(bundle:Bundle)=bundle.getBoolean(Keys.KEY_PHOTO_WIDGET_BLUR) &&
            bundle.getBoolean(Keys.KEY_PHOTO_WIDGET_TINT) && bundle.getBoolean(WidgetSettings.WALLPAPER_TINT)

        fun read(bundle:Bundle,wallpaperTone:IconPalette.WallpaperTone?=null):WidgetMaterialSettings {
            val tone=wallpaperTone?.takeIf {followsWallpaper(bundle)}
            fun flag(key:String)=bundle.getBoolean(key,false)
            fun number(key:String,max:Int=100,min:Int=0)=bundle.getInt(key,SettingsDefaults.integer(key)).coerceIn(min,max)
            return WidgetMaterialSettings(
                enabled=flag(Keys.KEY_PHOTO_WIDGET_BLUR),
                allWidgets=number(WidgetSettings.SCOPE,1)==WidgetSettings.ALL,
                liquid=number(Keys.KEY_PHOTO_WIDGET_MATERIAL,1)==1,
                radius=number(WidgetSettings.RADIUS,50),
                customFrame=number(Keys.KEY_PHOTO_WIDGET_STYLE,1)==1,
                corner=number(Keys.KEY_PHOTO_WIDGET_CORNER,50),
                shade=number(Keys.KEY_PHOTO_WIDGET_SHADE),
                rim=number(Keys.KEY_PHOTO_WIDGET_RIM),
                opacity=number(Keys.KEY_PHOTO_WIDGET_OPACITY),
                fade=number(Keys.KEY_PHOTO_WIDGET_GLOW),
                fadeHeight=number(WidgetSettings.FADE_HEIGHT),
                fadeSmoothness=number(WidgetSettings.FADE_SMOOTHNESS),
                tint=flag(Keys.KEY_PHOTO_WIDGET_TINT),
                hue=tone?.hue ?: number(WidgetSettings.HUE,360),
                saturation=number(WidgetSettings.TINT_SATURATION).let {tone?.saturationAt(it) ?: it},
                lightness=number(WidgetSettings.LIGHTNESS,100,-100),
                tintTransparency=number(WidgetSettings.TINT_TRANSPARENCY),
                width=number(Keys.KEY_PHOTO_WIDGET_LIQUID_WIDTH),
                distortion=number(Keys.KEY_PHOTO_WIDGET_LIQUID_DISTORTION,300),
                dispersion=number(GlassDispersionSettings.PHOTOS)
            )
        }
    }
}
