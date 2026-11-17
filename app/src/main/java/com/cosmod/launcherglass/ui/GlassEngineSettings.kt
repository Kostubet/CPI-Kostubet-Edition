package com.cosmod.launcherglass.ui

import android.os.Bundle

object GlassEngineSettings {
    const val ICONS = "glass_engine_icons"
    const val FOLDERS = "glass_engine_folders"
    const val LARGE_FOLDERS = "glass_engine_large_folders"
    const val WIDGETS = "glass_engine_widgets"
    const val DOCK = "glass_engine_dock"
    const val INDICATOR = "glass_engine_indicator"
    const val BANNERS = "glass_engine_banners"
    const val LIVE = "glass_engine_live"
    const val VOLUME = "glass_engine_volume"
    const val LOCKSCREEN = "glass_engine_lockscreen"
    enum class Parameter(val id:String,val default:Int,val max:Int) {
        POWER("power",125,250), EDGE_WIDTH("edge_width",34,100), EDGE_LIGHT("edge_light",62,100),
        LENS_HEIGHT("lens_height",100,200),
        BACKDROP_BRIGHTNESS("backdrop_brightness",100,200),
        BLUR_STRENGTH("blur_strength",100,200), BLUR_OPACITY("blur_opacity",100,100),
        BLUR_QUALITY("blur_quality",1,2), BLUR_ENABLED("blur_enabled",1,1),
        BLUR_LIVE("blur_live",1,1), BLUR_ECONOMY("blur_economy",0,1), BLUR_120("blur_120",0,1),
        BLUR_RADIUS("blur_radius",10,60)
    }

    data class Blur(val enabled:Boolean=true,val strength:Int=100,val opacity:Int=100,
        val quality:Int=2,val live:Boolean=true,val economy:Boolean=false,val highRefresh:Boolean=true) {
        fun radius(requested:Float)=if(enabled && opacity>0)requested*strength*.01f else 0f
        fun cadence(normal:Int,globalHighRefresh:Boolean=false):Int=when {
            !live->0
            economy->minOf(normal,24)
            highRefresh || globalHighRefresh->120
            else->normal
        }
        val taps get()=if(economy)0 else quality
    }

    data class Optics(val power:Int=125,val edgeWidth:Int=34,val edgeLight:Int=62,
        val blur:Blur=Blur(),val radius:Int=10,val backdropBrightness:Int=100,
        val lensHeight:Int=Parameter.LENS_HEIGHT.default) {
        val heightScale get()=lensHeight*.01f
    }

    private val lockKeys get()=LockscreenSettings.Surface.entries.map {it.key("new_engine")}
    val booleans get()=(listOf(ICONS,FOLDERS,LARGE_FOLDERS,WIDGETS,DOCK,INDICATOR,BANNERS,LIVE,VOLUME,LOCKSCREEN)+lockKeys)
        .associateWith {false}
    fun blurOpacityKey(scope:String)=key(scope,Parameter.BLUR_OPACITY)

    private val parameters by lazy {
        (booleans.keys - LOCKSCREEN + ShadeGlassSettings.Panel.entries.map {it.newEngine}).flatMap {scope->
            Parameter.entries.filter {parameter->
                when {
                    parameter==Parameter.BLUR_RADIUS->scope==LARGE_FOLDERS
                    parameter in setOf(Parameter.BLUR_STRENGTH,Parameter.BLUR_QUALITY,Parameter.BLUR_ENABLED,
                        Parameter.BLUR_LIVE,Parameter.BLUR_ECONOMY,Parameter.BLUR_120)->false
                    parameter==Parameter.BACKDROP_BRIGHTNESS && scope==VOLUME->false
                    parameter.ordinal>=Parameter.BACKDROP_BRIGHTNESS.ordinal->ShadeGlassSettings.Panel.entries.none {it.newEngine==scope}
                    else->true
                }
            }.map {key(scope,it) to it}
        }.toMap()
    }
    val integers get()=parameters.mapValues {it.value.default}
    fun maximum(key:String)=parameters[key]?.max
    fun key(scope:String,parameter:Parameter)="${scope}_${parameter.id}"

    fun read(scope:String,read:(String,Int)->Int):Optics {
        fun value(parameter:Parameter):Int {
            val key=key(scope,parameter)
            return if(key in parameters)read(key,parameter.default).coerceIn(0,parameter.max) else parameter.default
        }
        return Optics(value(Parameter.POWER),value(Parameter.EDGE_WIDTH),value(Parameter.EDGE_LIGHT),
            Blur(opacity=value(Parameter.BLUR_OPACITY)),value(Parameter.BLUR_RADIUS),
            value(Parameter.BACKDROP_BRIGHTNESS),value(Parameter.LENS_HEIGHT))
    }

    fun optics(bundle:Bundle,scope:String)=read(scope,bundle::getInt)

    /** Keep the effective radius of configurations saved before the single blur slider. */
    internal fun legacyRadii(values:Map<String,Any?>):Map<String,Int> = buildMap {
        fun migrate(scope:String, radiusKey:String, max:Int, switchKey:String?=null) {
            val oldSwitch=values[key(scope,Parameter.BLUR_ENABLED)] as? Number
            val oldStrength=values[key(scope,Parameter.BLUR_STRENGTH)] as? Number
            val styleUsesOldEngine=values[scope]==true
            if(switchKey!=null && values[switchKey]==false || styleUsesOldEngine && oldSwitch?.toInt()==0){
                put(radiusKey,0)
            }else if(styleUsesOldEngine && oldStrength!=null && oldStrength.toInt()!=100){
                if(radiusKey !in SettingsDefaults.portable())return
                val base=values[radiusKey] as? Number ?: SettingsDefaults.integer(radiusKey)
                put(radiusKey,kotlin.math.round(base.toDouble()*oldStrength.toDouble()/100.0).toInt().coerceIn(0,max))
            }
        }
        migrate(ICONS,SettingsProvider.KEY_BLUR_RADIUS,50,SettingsProvider.KEY_BLUR_RADIUS_ENABLED)
        migrate(FOLDERS,FolderSettingsKeys.key(SettingsProvider.KEY_BLUR_RADIUS),50,FolderSettingsKeys.key(SettingsProvider.KEY_BLUR_RADIUS_ENABLED))
        migrate(LARGE_FOLDERS,key(LARGE_FOLDERS,Parameter.BLUR_RADIUS),60)
        migrate(WIDGETS,WidgetSettings.RADIUS,50)
        migrate(DOCK,SettingsProvider.KEY_DOCK_BLUR,40)
        migrate(INDICATOR,"indicator_glass_blur",50)
        migrate(BANNERS,"banner_glass_blur",50)
        migrate(LIVE,"island_glass_blur",50)
        migrate(VOLUME,"volume_glass_blur",32)
        for(surface in LockscreenSettings.Surface.entries)migrate(surface.key("new_engine"),surface.key("blur"),50)
        for(panel in ShadeGlassSettings.Panel.entries){
            val radius=panel.key(ShadeGlassSettings.Parameter.RADIUS)
            val strength=(values[panel.key(ShadeGlassSettings.Parameter.BLUR_STRENGTH)] as? Number)?.toInt()
            if((values[panel.key(ShadeGlassSettings.Parameter.BLUR)] as? Number)?.toInt()==0)put(radius,0)
            else if(strength!=null && strength!=100){
                val base=(values[radius] as? Number)?.toDouble() ?: ShadeGlassSettings.Parameter.RADIUS.default.toDouble()
                put(radius,kotlin.math.round(base*strength/100.0).toInt().coerceIn(0,60))
            }
        }
    }

    fun inheritLegacy(values:Map<String,Any?>):Map<String,Boolean> {
        return buildMap {
            if(values[LOCKSCREEN]==true)lockKeys.filterNot(values::containsKey).forEach {put(it,true)}
            if(values[FOLDERS]==true && LARGE_FOLDERS !in values)put(LARGE_FOLDERS,true)
        }
    }
}
