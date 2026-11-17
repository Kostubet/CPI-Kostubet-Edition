/*
 * Optics adapted from AndroidLiquidGlass's Shaders.kt.
 * Copyright 2025 Kyant. Licensed under the Apache License, Version 2.0.
 */
package com.cosmod.launcherglass.xposed

import android.os.Bundle
import com.cosmod.launcherglass.ui.GlassEngineSettings as S

internal object KyantGlassEngine {
    private val settings = mapOf(
        0 to S.ICONS, GlassSurface.FOLDERS to S.FOLDERS, GlassSurface.WIDGETS to S.WIDGETS,
        GlassSurface.DOCK to S.DOCK, GlassSurface.BANNERS to S.BANNERS, GlassSurface.LIVE to S.LIVE,
        GlassSurface.PAGE_INDICATOR to S.INDICATOR, GlassSurface.VOLUME to S.VOLUME,
        GlassSurface.LOCKSCREEN to S.LOCKSCREEN
    )
    @Volatile private var enabled = emptySet<Int>()
    @Volatile private var optics = emptyMap<String,S.Optics>()
    @Volatile var largeFolders=false
        private set

    fun update(bundle:Bundle):Boolean {
        val next=settings.filterValues { bundle.getBoolean(it,false) }.keys
        val nextOptics=(settings.values-S.LOCKSCREEN+S.LARGE_FOLDERS).associateWith {S.optics(bundle,it)}
        val large=bundle.getBoolean(S.LARGE_FOLDERS,false)
        if(enabled==next && optics==nextOptics && large==largeFolders)return false
        enabled=next
        optics=nextOptics
        largeFolders=large
        return true
    }

    fun enabled(scope:Int,bundle:Bundle?=null):Boolean =
        if(bundle==null)scope in enabled else settings[scope]?.let {bundle.getBoolean(it,false)}==true

    fun optics(scope:Int,bundle:Bundle?=null):S.Optics = settings[scope]?.let {
        if(bundle==null)optics[it] else S.optics(bundle,it)
    } ?: S.Optics()

    fun largeFolderOptics()=optics[S.LARGE_FOLDERS] ?: S.Optics()

    // Rounded distance field, gradient and circular profile used by Backdrop Lens/Highlight.
    const val AGSL = """
        float kyantSd(float2 p,float2 halfSize,float radius) {
            float2 q=abs(p)-(halfSize-radius);
            return length(max(q,0.0))+min(max(q.x,q.y),0.0)-radius;
        }
        float2 kyantNormal(float2 p,float2 halfSize,float radius) {
            float2 q=abs(p)-(halfSize-radius);
            float2 n=max(q,0.0);
            if(dot(n,n)>.00001)return sign(p)*normalize(n);
            float x=step(q.y,q.x);
            return sign(p)*float2(x,1.0-x);
        }
        float kyantCircularProfile(float x) {
            x=clamp(x,0.0,1.0);
            return 1.0-sqrt(max(0.0,1.0-x*x));
        }
        float kyantDirectionalHighlight(float2 normal,float angle,float falloff) {
            return pow(clamp(abs(dot(normal,float2(cos(angle),sin(angle)))),0.0,1.0),falloff);
        }
    """
}
