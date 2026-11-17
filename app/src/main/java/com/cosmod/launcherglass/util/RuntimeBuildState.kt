package com.cosmod.launcherglass.util

internal object RuntimeBuildState {
    fun loaded(name:String?,code:Int,currentName:String,currentCode:Int)=
        name==currentName && code==currentCode && code>0
}
