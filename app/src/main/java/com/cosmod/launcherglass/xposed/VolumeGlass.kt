package com.cosmod.launcherglass.xposed

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.view.View
import com.cosmod.launcherglass.util.GlassLogger
import com.cosmod.launcherglass.util.PerformancePolicy
import com.cosmod.launcherglass.util.VolumeRim
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method
import java.util.WeakHashMap

/** Reuse the volume window compositor blur and add an optical finish to the inactive track. */
internal object VolumeGlass {
    private const val TYPE="com.oplus.systemui.volume.OplusVolumeSeekBar"
    private val targets=WeakHashMap<View,Plate>()
    private class Plate {
        val track=Rect()
        val progress=Rect()
        val wallpaperArea=Rect()
        val bounds=RectF()
        val tint=Paint(Paint.ANTI_ALIAS_FLAG)
        val rim=VolumeRim()
        var nativeBlur:Int?=null
        var appliedBlur:Int?=null
    }
    private val hooks=ArrayList<XC_MethodHook.Unhook>(2)
    private var setBlur:Method?=null
    private var radiusDp=14
    private var liquid=true
    private var opacity=18
    private var brightness=0
    private var rimStrength=100
    private var heightPercent=100
    private val heightIds=HashSet<Int>()
    private var optics=Bundle()

    fun isVisibleTrack(view:View)=liquid && hooks.isNotEmpty() && view.isShown &&
        targets[view]?.track?.isEmpty==false
    private var failed=false

    fun configure(active:Boolean,prefs:Bundle,loader:ClassLoader?){
        radiusDp=prefs.getInt("volume_glass_blur",14).coerceIn(0,32)
        brightness=prefs.getInt("volume_glass_brightness",0).coerceIn(-100,100)
        rimStrength=prefs.getInt("volume_glass_rim",100).coerceIn(0,200)
        heightPercent=prefs.getInt("volume_glass_height",100).coerceIn(60,160)
        liquid=prefs.getInt("volume_glass_style",1)==1 && android.os.Build.VERSION.SDK_INT>=33
        opacity=prefs.getInt("volume_glass_opacity",18).coerceIn(0,100)
        optics=Bundle().apply {
            putInt("island_glass_blur",radiusDp)
            putInt("island_glass_width",prefs.getInt("volume_glass_width",42).coerceIn(0,100))
            putInt("island_glass_distortion",prefs.getInt("volume_glass_distortion",90).coerceIn(0,300))
            putInt("island_glass_corner",50)
            putInt(com.cosmod.launcherglass.ui.GlassDispersionSettings.ALERTS,
                prefs.getInt("volume_glass_dispersion",0).coerceIn(0,100))
        }
        if(!active){
            hooks.forEach {it.unhook()}
            hooks.clear()
            targets.strongSnapshot().forEach { (view,plate)->
                AlertBackdrop.forgetVolume(view)
                if(view.isAttachedToWindow)runCatching {
                    val radius=plate.nativeBlur
                    if(radius!=null && plate.appliedBlur!=radius)setBlur?.invoke(view,radius)
                    view.invalidate()
                }
            }
            targets.clear()
            setBlur=null
            failed=false
            return
        }
        if(hooks.isNotEmpty() || failed || loader==null){
            targets.keys.weakSnapshot().forEach {if(!liquid)AlertBackdrop.forgetVolume(it);it.invalidate()}
            return
        }
        runCatching {
            val type=XposedHelpers.findClass(TYPE,loader)
            // The OEM derives the full portrait container and touch geometry from
            // this resource. Scale the layout input, never just stretch the paint.
            heightIds.clear()
            val resources=android.app.AndroidAppHelper.currentApplication()?.resources
            resources?.getIdentifier("volume_vertical_seek_bar_width","dimen","com.android.systemui")
                ?.takeIf {it!=0}?.let(heightIds::add)
            for(name in listOf("getDimension","getDimensionPixelSize","getDimensionPixelOffset")){
                val method=android.content.res.Resources::class.java.getDeclaredMethod(name,Int::class.javaPrimitiveType)
                hooks+=XposedBridge.hookMethod(method,object:XC_MethodHook(){
                    override fun afterHookedMethod(p:MethodHookParam){
                        if(p.hasThrowable() || heightPercent==100)return
                        val res=p.thisObject as android.content.res.Resources
                        if(heightIds.isEmpty()){
                            res.getIdentifier("volume_vertical_seek_bar_width","dimen","com.android.systemui")
                                .takeIf {it!=0}?.let(heightIds::add)
                        }
                        if(p.args[0] !in heightIds)return
                        if(runCatching {res.getResourcePackageName(p.args[0] as Int)}.getOrNull()!="com.android.systemui")return
                        val native=p.result as? Number ?: return
                        val scaled=native.toFloat()*heightPercent/100f
                        p.result=if(name=="getDimension")scaled else kotlin.math.round(scaled).toInt()
                    }
                })
            }
            val onDraw=type.getDeclaredMethod("onDraw",Canvas::class.java)
            val track=type.getDeclaredMethod("copyBackgroundRect",Rect::class.java).apply {isAccessible=true}
            val progress=type.getDeclaredMethod("copyProgressRect",Rect::class.java).apply {isAccessible=true}
            val apply=type.getDeclaredMethod("applyBarBlurRadiusPx",Int::class.javaPrimitiveType).apply {isAccessible=true}
            setBlur=apply
            hooks+=XposedBridge.hookMethod(apply,object:XC_MethodHook(){
                override fun beforeHookedMethod(param:MethodHookParam){
                    val view=param.thisObject as View
                    val plate=targets.getOrPut(view){Plate()}
                    val native=param.args[0] as Int
                    val desired=scaledRadius(view,native)
                    plate.nativeBlur=native
                    plate.appliedBlur=desired
                    param.args[0]=desired
                }
            })
            hooks+=XposedBridge.hookMethod(onDraw,object:XC_MethodHook(){
                override fun afterHookedMethod(param:MethodHookParam){
                    val view=param.thisObject as View
                    val canvas=param.args[0] as Canvas
                    if(param.hasThrowable() || !view.isShown || !canvas.isHardwareAccelerated)return
                    runCatching {
                        val plate=targets.getOrPut(view){Plate()}
                        track.invoke(view,plate.track)
                        if(plate.track.isEmpty)return
                        plate.nativeBlur?.let { native->
                            val desired=scaledRadius(view,native)
                            if(plate.appliedBlur!=desired){
                                XposedBridge.invokeOriginalMethod(apply,view,arrayOf(desired))
                                plate.appliedBlur=desired
                            }
                        }
                        progress.invoke(view,plate.progress)
                        drawFinish(canvas,view,plate)
                    }.onFailure {GlassLogger.limited("VolumeGlass","draw") {"Native volume retained: "+it.javaClass.simpleName}}
                }
            })
        }.onFailure {
            hooks.forEach {it.unhook()}
            hooks.clear()
            setBlur=null
            failed=true
            GlassLogger.limited("VolumeGlass","hook") {"Volume slider unavailable: "+it.javaClass.simpleName}
        }
    }

    private fun scaledRadius(view:View,native:Int):Int {
        val dp=minOf(radiusDp,if(PerformancePolicy.economy)14 else 32)
        return (native*dp/14f).toInt().coerceIn(0,(48*view.resources.displayMetrics.density).toInt())
    }

    private fun drawFinish(canvas:Canvas,view:View,plate:Plate){
        val rect=plate.bounds
        rect.set(plate.track)
        val density=view.resources.displayMetrics.density
        val stroke=(density*.55f).coerceAtLeast(1f)
        val nativeRadius=(NativeIconAccess.call(view,"getCurBackgroundRadius") as? Float ?: rect.width()*.5f)
            .coerceIn(0f,minOf(rect.width(),rect.height())*.5f)
        if(nativeRadius<=stroke || rect.width()<stroke*2)return
        val saved=canvas.save()
        try {
            if(!plate.progress.isEmpty && !canvas.clipOutRect(plate.progress))return
            if(liquid){
                val frame=AlertBackdrop.volumeFrame(view)
                val drawn=if(frame!=null){
                    AlertGlassLens.render(canvas,view,optics,frame.bitmap,frame.area,rect,nativeRadius,255)
                }else if(!AlertBackdrop.canSample()){
                    val wallpaper=LockscreenBackdrop.frame?.images?.entries?.minByOrNull {it.key}?.value
                    if(wallpaper!=null){
                        val metrics=view.resources.displayMetrics
                        val scale=maxOf(metrics.widthPixels.toFloat()/wallpaper.width,metrics.heightPixels.toFloat()/wallpaper.height)
                        val width=(wallpaper.width*scale).toInt()
                        val height=(wallpaper.height*scale).toInt()
                        val left=(metrics.widthPixels-width)/2
                        val top=(metrics.heightPixels-height)/2
                        plate.wallpaperArea.set(left,top,left+width,top+height)
                        AlertGlassLens.render(canvas,view,optics,wallpaper,plate.wallpaperArea,rect,nativeRadius,255)
                    }else false
                }else false
                if(drawn){
                    plate.tint.color=Color.argb((opacity*2.55f).toInt(),20,20,24)
                    canvas.drawRoundRect(rect,nativeRadius,nativeRadius,plate.tint)
                }
            }
            if(brightness!=0){
                plate.tint.color=if(brightness>0)Color.WHITE else Color.BLACK
                plate.tint.alpha=kotlin.math.round(kotlin.math.abs(brightness)*2.55f).toInt().coerceIn(0,255)
                canvas.drawRoundRect(rect,nativeRadius,nativeRadius,plate.tint)
            }
            plate.rim.draw(canvas,rect,nativeRadius,stroke,rimStrength)
        }finally {canvas.restoreToCount(saved)}
    }
}
