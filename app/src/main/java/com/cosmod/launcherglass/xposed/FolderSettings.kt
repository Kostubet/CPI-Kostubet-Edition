package com.cosmod.launcherglass.xposed

import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.os.Bundle
import android.os.Build
import com.cosmod.launcherglass.ui.*

internal object FolderSettings {
    data class Processing(val radius:Int,val quality:Int,val vibrancy:Int,val lightening:Int,val liquid:Boolean)
    @Volatile var own=false
        private set
    @Volatile private var selected:GlassStyle?=null
    @Volatile var processing=Processing(10,0,0,50,false)
        private set
    private var stamp:List<Any>?=null
    @Volatile private var dark=true
    private data class LargeStyle(val liquid:Boolean,val width:Int,val strength:Int,val opacity:Int)
    @Volatile private var large:LargeStyle?=null
    fun style(context:Context,expanded:Boolean=false):GlassStyle {
        val ownStyle=if(own)selected else null
        val plateColor=if(dark)0x181818 else 0xeeeeee
        val normal=ownStyle?.copy(plateColor=plateColor) ?: GlassStyle.icons().copy(
            folder=true,surfaceScope=GlassSurface.FOLDERS,plateColor=plateColor,volumeOpacity=0)
        val independent=if(expanded)large else null
        return independent?.let {normal.copy(liquid=it.liquid,width=it.width,distortion=it.strength,opacity=it.opacity)} ?: normal
    }
    fun update(bundle:Bundle):Boolean {
        val signature=ArrayList<Any>()
        signature.add(bundle.getBoolean(FolderSettingsKeys.OWN,false))
        signature.add(bundle.getBoolean(SettingsProvider.KEY_DEVELOPER_MODE,false))
        val darkIcons=bundle.getBoolean(SettingsProvider.KEY_NATIVE_ICON_DARK,true)
        signature.add(darkIcons)
        val dispersion=GlassDispersionSettings.strength(bundle,GlassDispersionSettings.FOLDERS)
        signature.add(dispersion)
        FolderSettingsKeys.integers.forEach {signature.add(bundle.getInt(FolderSettingsKeys.key(it),SettingsDefaults.integer(it)))}
        FolderSettingsKeys.booleans.forEach {signature.add(bundle.getBoolean(FolderSettingsKeys.key(it),SettingsDefaults.boolean(it)))}
        val largeStyle=if(bundle.getBoolean("large_folder_glass_own",false))LargeStyle(
            bundle.getInt("large_folder_glass_style",1)==1 && Build.VERSION.SDK_INT>=33,
            bundle.getInt("large_folder_glass_width",40).coerceIn(0,100),
            bundle.getInt("large_folder_glass_strength",100).coerceIn(0,300),
            bundle.getInt("large_folder_glass_opacity",15).coerceIn(0,100)
        ) else null
        signature.add(largeStyle ?: false)
        if(stamp==signature)return false
        large=largeStyle
        stamp=signature
        dark=darkIcons
        own=bundle.getBoolean(FolderSettingsKeys.OWN,false)
        fun number(key:String,max:Int)=bundle.getInt(FolderSettingsKeys.key(key),SettingsDefaults.integer(key)).coerceIn(0,max)
        fun bool(key:String)=bundle.getBoolean(FolderSettingsKeys.key(key),SettingsDefaults.boolean(key))
        val developer=bundle.getBoolean(SettingsProvider.KEY_DEVELOPER_MODE,false)
        val tone=bool(SettingsProvider.KEY_BLUR_TONE_ENABLED)
        val radius=if(bool(SettingsProvider.KEY_BLUR_RADIUS_ENABLED))number(SettingsProvider.KEY_BLUR_RADIUS,50)else 0
        val quality=if(developer)number(SettingsProvider.KEY_BLUR_QUALITY,100)else 0
        val liquid=number(SettingsProvider.KEY_BLUR_STYLE,1)==1 && Build.VERSION.SDK_INT>=33
        val vibrancy=if(tone)number(SettingsProvider.KEY_BLUR_VIBRANCY,200)else 0
        processing=Processing(radius,quality,vibrancy,if(tone)number(SettingsProvider.KEY_BLUR_LIGHTENING,100)else 50,liquid)
        val saturation=if(tone)number(SettingsProvider.KEY_BLUR_SATURATION,200)else 100
        val filter=if(saturation!=100)ColorMatrixColorFilter(ColorMatrix().apply {setSaturation(saturation/100f)})else null
        selected=GlassStyle(liquid,number(SettingsProvider.KEY_LIQUID_WIDTH,100),
            number(SettingsProvider.KEY_LIQUID_DISTORTION,300),radius,if(tone)number(SettingsProvider.KEY_BG_OPACITY,100)else 0,
            filter,folder=true,volumeOpacity=0,
            surfaceScope=GlassSurface.FOLDERS,dispersion=dispersion)
        return true
    }
}
