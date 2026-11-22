package com.cosmod.launcherglass.xposed

import android.graphics.Bitmap
import com.cosmod.launcherglass.util.*
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

/** Neutral dock source, produced on the existing wallpaper worker before icon color processing. */
internal object NeutralBackdrop {
    data class Images(val dock:Bitmap?=null,val indicator:Bitmap?=null,private val cached:Boolean=false) {
        fun discard() {
            if(cached)return
            dock?.recycle()
            if(indicator!==dock)indicator?.recycle()
        }
    }

    private data class SurfaceBlur(val mode:Int,val radius:Int,val opacity:Int)
    private data class StaticInput(val dock:SurfaceBlur,val indicator:SurfaceBlur,val height:Int,val gpu:Boolean)
    private data class StaticBlur(val source:WeakReference<Bitmap>,val generation:Int,val input:StaticInput,val images:Images)

    @Volatile private var staticBlur:StaticBlur?=null
    @Volatile private var images=Images()
    val image get()=images.dock
    val indicatorImage get()=images.indicator
    private var settings:List<Any>?=null
    private val main=android.os.Handler(android.os.Looper.getMainLooper())
    fun onSettingsChanged():Boolean{
        val indicator=PageIndicatorGlass.profile
        val current=listOf(PrefsHelper.enabled,PrefsHelper.dockStyle,PrefsHelper.dockBlur,indicator.mode,indicator.blur,
            KyantGlassEngine.optics(GlassSurface.DOCK).blur.opacity,indicator.style.engineOptics.blur.opacity)
        if(settings==current)return false
        settings=current
        staticBlur=null
        synchronized(WallpaperHelper){
            WallpaperHelper.invalidate()
            val currentImages=images
            publish(Images(currentImages.dock.takeIf {PrefsHelper.dockGlassEnabled},
                currentImages.indicator.takeIf {PrefsHelper.enabled && indicator.mode>0}))
        }
        return true
    }
    fun prepare(raw:Bitmap,heightLimit:Int,reuseStatic:Boolean):Images {
        if(!PrefsHelper.enabled)return Images()
        val indicator=PageIndicatorGlass.profile
        val input=StaticInput(
            dock=SurfaceBlur(if(PrefsHelper.dockGlassEnabled)PrefsHelper.dockStyle else 0,
                PrefsHelper.dockBlur,KyantGlassEngine.optics(GlassSurface.DOCK).blur.opacity),
            indicator=SurfaceBlur(indicator.mode,indicator.blur,indicator.style.engineOptics.blur.opacity),
            height=minOf(raw.height,PerformancePolicy.sourceHeight,heightLimit),
            gpu=PrefsHelper.gpuEffects,
        )
        val generation=raw.generationId
        if(reuseStatic)staticBlur?.takeIf {
            it.source.get()===raw && it.generation==generation && it.input==input
        }?.let {return it.images}
        fun render(surface:SurfaceBlur):Bitmap? {
            if(surface.mode==0)return null
            return runCatching {
                blur(raw,surface.radius,surface.mode==2,input.height,surface.opacity,input.gpu)
            }.onFailure {
                GlassLogger.limited("NeutralBackdrop","wallpaper-processing"){"Neutral glass backdrop unavailable: $it"}
            }.getOrNull()
        }
        val preparedIndicator=render(input.indicator)
        val dock=if(input.dock==input.indicator)preparedIndicator else render(input.dock)
        val complete=(input.dock.mode==0 || dock!=null) && (input.indicator.mode==0 || preparedIndicator!=null)
        val result=Images(dock,preparedIndicator,cached=reuseStatic && complete)
        if(reuseStatic && complete)staticBlur=StaticBlur(WeakReference(raw),generation,input,result)
        return result
    }

    fun publish(next:Images) {
        val previous=images
        images=next
        if(next.dock!==previous.dock)main.post {DockMaterial.onBackdropChanged()}
        if(next.indicator!==previous.indicator)main.post {PageIndicatorGlass.onBackdropChanged()}
    }

    private fun blur(raw:Bitmap,requested:Int,liquid:Boolean,height:Int,opacity:Int,gpu:Boolean):Bitmap? {
        val width=(raw.width.toFloat()*height/raw.height).roundToInt().coerceAtLeast(1)
        val radius=requested*(height/800f)*(if(liquid).28f else 1f)
        if(gpu){
            val result=runCatching {GpuWallpaperBlur.blurDock(raw,width,height,radius,opacity)}.getOrNull()
            if(result!=null)return result
        }
        val scaled=Bitmap.createScaledBitmap(raw,width,height,true)
        val readable=if(scaled.config==Bitmap.Config.HARDWARE)scaled.copy(Bitmap.Config.ARGB_8888,false)else scaled
        if(readable==null){
            if(scaled!==raw)scaled.recycle()
            return null
        }
        var result:Bitmap?=null
        try {
            result=GlassBlur.bitmap(readable,radius,opacity)
            return result
        } finally {
            if(readable!==raw && readable!==result)readable.recycle()
            if(scaled!==raw && scaled!==readable && scaled!==result)scaled.recycle()
        }
    }
}
