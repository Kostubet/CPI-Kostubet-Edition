package com.cosmod.launcherglass.ui
/** Background-only color parameters. Foreground text is never part of this material. */
internal object SurfaceTintSettings {
    val surfaces=(1..13).toList()
    fun key(surface:Int,field:String)="surface_${surface}_tint_$field"
    val booleans=surfaces.flatMap {s->listOf(key(s,"enabled") to false,key(s,"wallpaper") to false)}.toMap() +
        mapOf("icon_logo_tint" to false,"widget_logo_tint" to false,"widget_elements_follow_background" to false)
    val integers=surfaces.flatMap {s->mapOf("hue" to 30,"saturation" to 45,"brightness" to 75,
        "lightness" to 0,"opacity" to 35).map {(k,v)->key(s,k) to v}}.toMap() +
        listOf("icon","widget").flatMap {s->mapOf("hue" to 30,"saturation" to 0,"brightness" to 90).map {(k,v)->"${s}_logo_$k" to v}}.toMap() +
        mapOf("photo_widget_tint_brightness" to 100,"widget_logo_transparency" to 0)
}
