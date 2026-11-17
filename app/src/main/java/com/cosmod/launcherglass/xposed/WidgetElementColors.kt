package com.cosmod.launcherglass.xposed

import android.graphics.*
import android.os.Bundle

/** One profile for internal text and glyphs; legacy preference keys remain compatible. */
internal object WidgetElementColors {
    var paint:Paint?=null
        private set
    val enabled get()=paint!=null
    var followsBackground=false
        private set
    private data class Profile(val enabled:Boolean,val hue:Int,val saturation:Int,val brightness:Int,
        val transparency:Int,val followsBackground:Boolean)
    private var profile:Profile?=null
    fun update(prefs:Bundle):Boolean {
        val next=Profile(prefs.getBoolean("widget_logo_tint"),prefs.getInt("widget_logo_hue",30),
            prefs.getInt("widget_logo_saturation",0),prefs.getInt("widget_logo_brightness",90),
            prefs.getInt("widget_logo_transparency",0),prefs.getBoolean("widget_elements_follow_background"))
        if(profile==next)return false
        profile=next
        followsBackground=next.followsBackground
        paint=next.takeIf {it.enabled && !it.followsBackground}?.let {value->Paint().apply {
            colorFilter=ColorMatrixColorFilter(com.cosmod.launcherglass.util.WidgetElementTint.matrix(
                SurfaceTints.color(value.hue,value.saturation,value.brightness,0),value.transparency))
        }}
        return true
    }
}
