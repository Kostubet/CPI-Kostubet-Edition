package com.cosmod.launcherglass.ui

import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import com.cosmod.launcherglass.R

internal object LockscreenSettings {
    const val CLEAR_BOUNCER_BLUR="lock_clear_bouncer_blur"
    const val UNLOCK_ENABLED="lock_unlock_enabled"
    const val AOD_BLUR_ENABLED="lock_aod_blur_enabled"
    const val AOD_BLUR_RADIUS="lock_aod_blur_radius"
    const val UNLOCK_SPEED="lock_unlock_speed"
    const val UNLOCK_SMOOTHNESS="lock_unlock_smoothness"
    const val UNLOCK_TRAVEL="lock_unlock_travel"
    const val UNLOCK_IMPULSE="lock_unlock_impulse"
    const val UNLOCK_BLUR="lock_unlock_blur"
    const val MAX_UNLOCK_BLUR=20
    const val MIN_UNLOCK_SPEED=70
    const val MAX_UNLOCK_SPEED=130
    enum class Surface(val id:String,val title:Int,val defaultOpacity:Int,val defaultBlur:Int){
        PIN("pin",R.string.lock_glass_pin,24,15),
        CLOCK("clock",R.string.lock_glass_clock,40,5),
        NOTIFICATIONS("notifications",R.string.lock_glass_notifications,43,16),
        SHORTCUTS("left",R.string.lock_glass_shortcuts,26,14),
        WIDGETS("widgets",R.string.lock_glass_widgets,34,14),
        CAPSULE("capsule",R.string.lock_glass_capsule,34,14);

        val edgeSurface get()=EdgeSettings.LOCK_START+ordinal
        val adjustableCorner get()=this==NOTIFICATIONS || this==WIDGETS
        val adjustableSaturation get()=this==NOTIFICATIONS || this==CAPSULE
        fun key(field:String)="lock_"+id+"_"+field
    }

    val WIDGET_WALLPAPER_TINT=Surface.WIDGETS.key("wallpaper_tint")
    val WIDGET_TINT_SATURATION=Surface.WIDGETS.key("tint_saturation")
    val WIDGET_TINT_TRANSPARENCY=Surface.WIDGETS.key("tint_transparency")
    data class WidgetTint(val saturation:Int,val transparency:Int)
    data class Profile(val enabled:Boolean,val liquid:Boolean,val blur:Int,val opacity:Int,val corner:Int,
        val width:Int,val distortion:Int,val dispersion:Int,val widgetTint:WidgetTint?,val saturation:Int=100)

    private fun defaults(surface:Surface)=mapOf("style" to 1,"blur" to surface.defaultBlur,
        "opacity" to surface.defaultOpacity,"corner" to when(surface){
            Surface.NOTIFICATIONS->-1
            Surface.CAPSULE->50
            else->32
        },"width" to 42,"distortion" to 75,"dispersion" to 0)
    val NOTIFICATION_CORNER=Surface.NOTIFICATIONS.key("corner")
    private val limits=mapOf("style" to 1,"blur" to 50,"corner" to 50,"distortion" to 300)
    val booleans=Surface.entries.associate {it.key("enabled") to false} +
        mapOf(CLEAR_BOUNCER_BLUR to false,WIDGET_WALLPAPER_TINT to false,UNLOCK_ENABLED to false,AOD_BLUR_ENABLED to false)
    val integers=Surface.entries.flatMap {surface->defaults(surface).map {surface.key(it.key) to it.value}}.toMap() +
        Surface.entries.filter {it.adjustableSaturation}.associate {it.key("saturation") to 100} +
        mapOf(WIDGET_TINT_SATURATION to 50,WIDGET_TINT_TRANSPARENCY to 50,UNLOCK_SPEED to 100,
            UNLOCK_SMOOTHNESS to 0,UNLOCK_TRAVEL to 100,UNLOCK_IMPULSE to 0,UNLOCK_BLUR to 0,AOD_BLUR_RADIUS to 16)
    val maximums=Surface.entries.flatMap {surface->limits.map {surface.key(it.key) to it.value}}.toMap() + mapOf(UNLOCK_SPEED to MAX_UNLOCK_SPEED,UNLOCK_TRAVEL to 150,UNLOCK_BLUR to MAX_UNLOCK_BLUR,AOD_BLUR_RADIUS to 36) +
        Surface.entries.filter {it.adjustableSaturation}.associate {it.key("saturation") to 150}

    private val shortcutAliases by lazy {
        (booleans.keys+integers.keys+EdgeSettings.booleans.keys+EdgeSettings.integers.keys)
            .filter {"lock_left_" in it}.associateWith {it.replace("lock_left_","lock_right_")}
    }

    fun sharedShortcutValues(values:Map<String,Any?>):Map<String,Any> {
        val preferRight=values["lock_right_enabled"]==true && values["lock_left_enabled"]!=true
        return shortcutAliases.mapNotNull { (key,old) ->
            val value=values[old] ?: return@mapNotNull null
            if(preferRight || key !in values)key to value else null
        }.toMap()
    }

    fun migrateShortcuts(prefs:SharedPreferences){
        val values=prefs.all
        if(shortcutAliases.values.none(values::containsKey))return
        val editor=prefs.edit()
        sharedShortcutValues(values).forEach { (key,value) ->
            when(value){
                is Boolean->editor.putBoolean(key,value)
                is Int->editor.putInt(key,value)
            }
        }
        shortcutAliases.values.forEach {editor.remove(it)}
        editor.apply()
    }

    fun requested(prefs:Bundle)=booleans.keys.any {prefs.getBoolean(it,false)}

    fun read(prefs:Bundle,surface:Surface):Profile {
        fun value(field:String):Int {
            val key=surface.key(field)
            return prefs.getInt(key,integers.getValue(key)).coerceIn(if(key==NOTIFICATION_CORNER)-1 else 0,maximums[key] ?: 100)
        }
        val tint=if(surface==Surface.WIDGETS && prefs.getBoolean(WIDGET_WALLPAPER_TINT)){
            WidgetTint(value("tint_saturation"),value("tint_transparency"))
        }else null
        return Profile(prefs.getBoolean(surface.key("enabled"),false),value("style")==1 && Build.VERSION.SDK_INT>=33,
            value("blur"),value("opacity"),value("corner"),value("width"),value("distortion"),value("dispersion"),tint,if(surface.adjustableSaturation)value("saturation") else 100)
    }
}
