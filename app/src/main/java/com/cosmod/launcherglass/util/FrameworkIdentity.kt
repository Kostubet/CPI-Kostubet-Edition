package com.cosmod.launcherglass.util

internal object FrameworkIdentity {
    fun isVector(bridgeTag:String?,vectorStartupPresent:Boolean)=
        vectorStartupPresent || bridgeTag?.contains("vector",ignoreCase=true)==true
}
