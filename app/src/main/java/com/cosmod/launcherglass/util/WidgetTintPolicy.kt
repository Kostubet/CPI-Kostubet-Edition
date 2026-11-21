package com.cosmod.launcherglass.util
internal object WidgetTintPolicy {
    private val artworkNames = listOf("background", "backdrop", "wallpaper", "snapshot", "photo", "picture",
        "cover", "avatar", "headshot", "portrait", "artwork", "thumbnail", "profile_image", "player_head", "user_head")
    fun logo(width:Int,height:Int,hostWidth:Int,hostHeight:Int,resourceName:String="",
             preserveUnidentifiedMedia:Boolean=false):Boolean {
        if(width<=0 || height<=0 || hostWidth<=0 || hostHeight<=0)return false
        val name=resourceName.lowercase(java.util.Locale.ROOT)
        if(artworkNames.any(name::contains) ||
            name=="bg" || name.startsWith("bg_") || name.endsWith("_bg") || name.contains("_bg_"))return false
        val relativeWidth = width.toFloat() / hostWidth
        val relativeHeight = height.toFloat() / hostHeight
        // Some ColorOS 16 card engines create anonymous bitmap views for avatars.
        if (preserveUnidentifiedMedia && name.isEmpty() && relativeWidth >= .14f && relativeHeight >= .14f) return false
        // A full-width strip can be a background even when it is very short.
        return relativeWidth < .70f && relativeHeight < .70f
    }
}
