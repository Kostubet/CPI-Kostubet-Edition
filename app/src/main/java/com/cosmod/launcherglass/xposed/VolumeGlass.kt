package com.cosmod.launcherglass.xposed

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Matrix
import android.graphics.Path
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
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.WeakHashMap

/** Reuse the volume window compositor blur and add an optical finish to the inactive track. */
internal object VolumeGlass {
    private const val TYPE="com.oplus.systemui.volume.OplusVolumeSeekBar"
    private const val LEGACY_TRACK="com.coui.appcompat.seekbar.COUIVerticalSeekBar"
    private val targets=WeakHashMap<View,Plate>()
    fun snapshotPreview(): com.cosmod.launcherglass.ui.NativePreviewScenes.Scene? {
        val candidates = targets.strongSnapshot().filter { (view, plate) ->
            view.width > 0 && view.height > 0 && !plate.track.isEmpty
        }
        val entry = candidates.entries.firstOrNull { it.key.isShown } ?: candidates.entries.firstOrNull() ?: return null
        val view = entry.key
        val track = RectF(entry.value.track)
        val transform = Matrix().apply {
            setRotate(view.rotation, track.centerX(), track.centerY())
        }
        val bounds = RectF(track).apply { transform.mapRect(this) }
        val radius = entry.value.corner.coerceIn(0f, minOf(track.width(), track.height()) / 2f)
        val shape = Path().apply {
            addRoundRect(track, radius, radius, Path.Direction.CW)
            transform(transform)
        }
        val kind = com.cosmod.launcherglass.util.SurfacePreviewKind.VOLUME
        return NativeMaterialPreview.captureWithoutMaterial(kind) {
            NativeMaterialPreview.capture(bounds, shape) { canvas ->
                canvas.concat(transform)
                // The active track is drawn by COUI. Window blur and the glass
                // finish belong to the separate background.
                XposedHelpers.callMethod(view, "onDraw", canvas)
            }
        }
    }
    private class Plate {
        val track=Rect()
        val progress=Rect()
        val wallpaperArea=Rect()
        val bounds=RectF()
        val tint=Paint(Paint.ANTI_ALIAS_FLAG)
        val rim=VolumeRim()
        var corner=0f
        var nativeBlur:Int?=null
        var appliedBlur:Int?=null
        var blurDrawable:WeakReference<Any>?=null
    }

    private class LegacyTrack(type:Class<*>) {
        private val owner=generateSequence(type.superclass as Class<*>?) {it.superclass}
            .first {it.name==LEGACY_TRACK}
        val onDraw:Method=owner.getDeclaredMethod("onDraw",Canvas::class.java)
        private val background=owner.getDeclaredField("mBackgroundRect").apply {isAccessible=true}
        private val progress=owner.getDeclaredField("mProgressRect").apply {isAccessible=true}
        private val corner=owner.getDeclaredField("mCurBackgroundRadius").apply {isAccessible=true}
        val blur=runCatching {LegacyBlur(type)}.getOrNull()

        fun read(view:View,plate:Plate) {
            plate.track.set(background.get(view) as Rect)
            plate.progress.set(progress.get(view) as Rect)
            plate.corner=corner.getFloat(view)
        }
    }

    private class LegacyBlur(type:Class<*>) {
        private val drawable=type.getDeclaredMethod("getBackgroundBlurDrawable").apply {isAccessible=true}
        private val radius=drawable.returnType.getDeclaredField("mBlurRadius").apply {isAccessible=true}
        private val setter=drawable.returnType.getMethod("setBlurRadius",Int::class.javaPrimitiveType)

        fun update(view:View,plate:Plate) {
            val target=drawable.invoke(view) ?: return
            if(plate.blurDrawable?.get()!==target){
                restore(plate)
                plate.blurDrawable=WeakReference(target)
                plate.nativeBlur=null
                plate.appliedBlur=null
            }
            val current=radius.getInt(target)
            if(plate.nativeBlur==null || current!=plate.appliedBlur)plate.nativeBlur=current
            val desired=scaledRadius(view,plate.nativeBlur ?: return)
            if(current!=desired)setter.invoke(target,desired)
            plate.appliedBlur=desired
        }

        fun restore(plate:Plate) {
            val target=plate.blurDrawable?.get() ?: return
            val native=plate.nativeBlur ?: return
            if(plate.appliedBlur!=native && radius.getInt(target)==plate.appliedBlur)setter.invoke(target,native)
        }
    }

    private val hooks=ArrayList<XC_MethodHook.Unhook>(5)
    private var setBlur:Method?=null
    private var legacyBlur:LegacyBlur?=null
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
            putInt("surface_tint_scope",13)
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
                runCatching {
                    legacyBlur?.restore(plate)
                    if(view.isAttachedToWindow){
                        val radius=plate.nativeBlur
                        if(radius!=null && plate.appliedBlur!=radius)setBlur?.invoke(view,radius)
                        view.invalidate()
                    }
                }
            }
            targets.clear()
            setBlur=null
            legacyBlur=null
            failed=false
            return
        }
        if(hooks.isNotEmpty() || failed || loader==null){
            targets.keys.weakSnapshot().forEach {if(!liquid)AlertBackdrop.forgetVolume(it);it.invalidate()}
            return
        }
        runCatching {
            val type=XposedHelpers.findClass(TYPE,loader)
            val modernDraw=runCatching {type.getDeclaredMethod("onDraw",Canvas::class.java)}.getOrNull()
            val legacy=if(modernDraw==null)LegacyTrack(type) else null
            val onDraw=modernDraw ?: checkNotNull(legacy).onDraw
            val track=modernDraw?.let {type.getDeclaredMethod("copyBackgroundRect",Rect::class.java).apply {isAccessible=true}}
            val progress=modernDraw?.let {type.getDeclaredMethod("copyProgressRect",Rect::class.java).apply {isAccessible=true}}
            val apply=modernDraw?.let {
                type.getDeclaredMethod("applyBarBlurRadiusPx",Int::class.javaPrimitiveType).apply {isAccessible=true}
            }
            setBlur=apply
            legacyBlur=legacy?.blur
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
            if(apply!=null)hooks+=XposedBridge.hookMethod(apply,object:XC_MethodHook(){
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
                    if(legacy!=null && !type.isInstance(view))return
                    val canvas=param.args[0] as Canvas
                    if (NativeMaterialPreview.active(com.cosmod.launcherglass.util.SurfacePreviewKind.VOLUME)) return
                    if(param.hasThrowable() || !view.isShown || !canvas.isHardwareAccelerated)return
                    runCatching {
                        val plate=targets.getOrPut(view){Plate()}
                        if(legacy!=null){
                            legacy.read(view,plate)
                            if(plate.track.isEmpty)return
                            legacy.blur?.update(view,plate)
                        }else{
                            track?.invoke(view,plate.track)
                            if(plate.track.isEmpty)return
                            progress?.invoke(view,plate.progress)
                            plate.corner=(NativeIconAccess.call(view,"getCurBackgroundRadius") as? Float)
                                ?: minOf(plate.track.width(),plate.track.height())*.5f
                            val native=plate.nativeBlur
                            if(apply!=null && native!=null){
                                val desired=scaledRadius(view,native)
                                if(plate.appliedBlur!=desired){
                                    XposedBridge.invokeOriginalMethod(apply,view,arrayOf(desired))
                                    plate.appliedBlur=desired
                                }
                            }
                        }
                        drawFinish(canvas,view,plate)
                    }.onFailure {GlassLogger.limited("VolumeGlass","draw") {"Native volume retained: "+it.javaClass.simpleName}}
                }
            })
            GlassLogger.limited("VolumeGlass","binding") {
                if(legacy!=null)"Using COUI volume track (compositor blur: ${legacy.blur!=null})" else "Using Oplus volume track"
            }
        }.onFailure {
            hooks.forEach {it.unhook()}
            hooks.clear()
            setBlur=null
            legacyBlur=null
            failed=true
            GlassLogger.limited("VolumeGlass","hook") {"Volume slider unavailable: "+it.javaClass.simpleName+" "+it.message}
        }
    }

    private fun scaledRadius(view:View,native:Int):Int {
        val blur=KyantGlassEngine.optics(GlassSurface.VOLUME).blur
        val dp=(blur.radius(radiusDp.toFloat())*blur.opacity*.01f).toInt().coerceIn(0,32)
        return (native*dp/14f).toInt().coerceIn(0,(48*view.resources.displayMetrics.density).toInt())
    }

    private fun drawFinish(canvas:Canvas,view:View,plate:Plate){
        val rect=plate.bounds
        rect.set(plate.track)
        val density=view.resources.displayMetrics.density
        val stroke=(density*.55f).coerceAtLeast(1f)
        val nativeRadius=plate.corner.coerceIn(0f,minOf(rect.width(),rect.height())*.5f)
        if(nativeRadius<=stroke || rect.width()<stroke*2)return
        val saved=canvas.save()
        try {
            if(!plate.progress.isEmpty && !canvas.clipOutRect(plate.progress))return
            if(liquid){
                val frame=AlertBackdrop.volumeFrame(view)
                val drawn=if(frame!=null){
                    AlertGlassLens.render(canvas,view,optics,frame.bitmap,frame.area,rect,nativeRadius,255)
                }else if(AlertBackdrop.isOnLockscreen()){
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
            if(!liquid)SurfaceTints.draw(canvas,rect,nativeRadius,13)
            if(brightness!=0){
                plate.tint.color=if(brightness>0)Color.WHITE else Color.BLACK
                plate.tint.alpha=kotlin.math.round(kotlin.math.abs(brightness)*2.55f).toInt().coerceIn(0,255)
                canvas.drawRoundRect(rect,nativeRadius,nativeRadius,plate.tint)
            }
            plate.rim.draw(canvas,rect,nativeRadius,stroke,rimStrength)
        }finally {canvas.restoreToCount(saved)}
    }
}
