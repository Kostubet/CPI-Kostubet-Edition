package com.cosmod.launcherglass.util
internal object WidgetTintPolicy {
    fun logo(width:Int,height:Int,hostWidth:Int,hostHeight:Int,resourceName:String=""):Boolean {
        if(width<=0 || height<=0 || hostWidth<=0 || hostHeight<=0)return false
        val name=resourceName.lowercase(java.util.Locale.ROOT)
        if(listOf("background","backdrop","wallpaper","snapshot","photo","picture","cover").any(name::contains) ||
            name=="bg" || name.startsWith("bg_") || name.endsWith("_bg") || name.contains("_bg_"))return false
        // A full-width strip can be a background even when it is very short.
        return width.toFloat()/hostWidth < .70f && height.toFloat()/hostHeight < .70f
    }
}
