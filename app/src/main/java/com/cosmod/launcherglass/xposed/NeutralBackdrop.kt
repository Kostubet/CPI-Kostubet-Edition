package com.cosmod.launcherglass.xposed

import android.graphics.Bitmap
import com.cosmod.launcherglass.util.*
import kotlin.math.roundToInt

/** Neutral dock source, produced on the existing wallpaper worker before icon color processing. */
internal object NeutralBackdrop {
    data class Images(val dock:Bitmap?=null,val indicator:Bitmap?=null) {
        fun discard() {
            dock?.recycle()
            if(indicator!==dock)indicator?.recycle()
        }
    }

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
        synchronized(WallpaperHelper){
            WallpaperHelper.invalidate()
            val currentImages=images
            publish(Images(currentImages.dock.takeIf {PrefsHelper.dockGlassEnabled},
                currentImages.indicator.takeIf {PrefsHelper.enabled && indicator.mode>0}))
        }
        return true
    }
    fun prepare(raw:Bitmap,heightLimit:Int):Images {
        if(!PrefsHelper.enabled)return Images()
        val indicator=PageIndicatorGlass.profile
        val height=minOf(raw.height,PerformancePolicy.sourceHeight,heightLimit)
        val dockEnabled=PrefsHelper.dockGlassEnabled
        val dockStyle=PrefsHelper.dockStyle
        val dockBlur=PrefsHelper.dockBlur
        val dockOpacity=KyantGlassEngine.optics(GlassSurface.DOCK).blur.opacity
        val indicatorOpacity=indicator.style.engineOptics.blur.opacity
        fun render(radius:Int,liquid:Boolean,opacity:Int)=runCatching {
            blur(raw,radius,liquid,height,opacity)
        }.onFailure {
            GlassLogger.limited("NeutralBackdrop","wallpaper-processing"){"Neutral glass backdrop unavailable: $it"}
        }.getOrNull()
        val preparedIndicator=if(indicator.mode>0)
            render(indicator.blur,indicator.mode==2,indicatorOpacity) else null
        val shared=preparedIndicator?.takeIf {indicator.mode==dockStyle && indicator.blur==dockBlur && indicatorOpacity==dockOpacity}
        val dock=if(dockEnabled)shared ?: render(dockBlur,dockStyle==2,dockOpacity) else null
        return Images(dock,preparedIndicator)
    }

    fun publish(next:Images) {
        val previous=images
        images=next
        if(next.dock!==previous.dock)main.post {DockMaterial.onBackdropChanged()}
        if(next.indicator!==previous.indicator)main.post {PageIndicatorGlass.onBackdropChanged()}
    }

    private fun blur(raw:Bitmap,requested:Int,liquid:Boolean,height:Int,opacity:Int):Bitmap? {
        val width=(raw.width.toFloat()*height/raw.height).roundToInt().coerceAtLeast(1)
        val radius=requested*(height/800f)*(if(liquid).28f else 1f)
        if(PrefsHelper.gpuEffects){
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
