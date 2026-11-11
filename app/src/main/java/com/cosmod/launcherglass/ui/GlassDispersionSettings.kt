package com.cosmod.launcherglass.ui

import android.content.SharedPreferences
import android.os.Bundle

internal object GlassDispersionSettings {
    const val ICONS="icon_glass_dispersion"
    const val FOLDERS="folder_glass_dispersion"
    const val PHOTOS="photo_widget_glass_dispersion"
    const val DOCK="dock_glass_dispersion"
    const val ALERTS="island_glass_dispersion"
    const val BANNERS="banner_glass_dispersion"
    val defaults=listOf(ICONS,FOLDERS,PHOTOS,DOCK,ALERTS,BANNERS).associateWith {0}

    fun strength(bundle:Bundle,key:String)=bundle.getInt(key,0).coerceIn(0,100)

    fun migrate(prefs:SharedPreferences){
        val old=defaults.keys.map {it.replace("_dispersion","_hue")}+
            listOf("glass_optics_v2","glass_color_shift","glass_color_dispersion","glass_color_saturation")
        if(old.none(prefs::contains))return
        prefs.edit().apply {old.forEach {remove(it)}}.apply()
    }
}
