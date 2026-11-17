package com.cosmod.launcherglass.util

/** Search controls are foreground, rather than part of the recolored widget plate. */
internal object SearchWidgetPolicy {
    fun googleSearch(packageName:String?,providerName:String?):Boolean =
        packageName=="com.google.android.googlequicksearchbox" &&
            providerName?.endsWith("SearchWidgetProvider")==true

    fun background(resourceName:String):Boolean =
        resourceName.startsWith("googleapp_search_widget_background") &&
            !resourceName.contains("protection")

    fun control(resourceName:String,width:Int,height:Int,contentWidth:Int,contentHeight:Int):Boolean =
        !background(resourceName) && !resourceName.contains("protection") &&
            (WidgetTintPolicy.logo(width,height,contentWidth,contentHeight,resourceName) ||
                // Google's icon can fill the capsule's entire height.
                resourceName.isNotBlank() && width>0 && height>0 && contentWidth>0 && contentHeight>0 &&
                    width<contentWidth*.7f && height<=contentHeight && width.toFloat()/height in .3f..3f)
}
