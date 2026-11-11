package com.cosmod.launcherglass.xposed

import android.graphics.Bitmap
import com.cosmod.launcherglass.util.*
import kotlin.math.roundToInt

/** Neutral dock source, produced on the existing wallpaper worker before icon color processing. */
internal object NeutralBackdrop {
    @Volatile var image:Bitmap?=null
        private set
    @Volatile var indicatorImage:Bitmap?=null
        private set
    private var settings:List<Any>?=null
    private var reported=false
    private val main=android.os.Handler(android.os.Looper.getMainLooper())
    fun onSettingsChanged(){
        val indicator=PageIndicatorGlass.profile
        val current=listOf(PrefsHelper.enabled,PrefsHelper.dockStyle,PrefsHelper.dockBlur,indicator.mode,indicator.blur)
        if(settings==current)return
        settings=current
        WallpaperHelper.invalidate()
    }
    fun process(raw:Bitmap){
        if(!PrefsHelper.enabled)return
        val indicator=PageIndicatorGlass.profile
        if(indicator.mode>0){
            indicatorImage=blur(raw,indicator.blur,indicator.mode==2,minOf(raw.height,PerformancePolicy.dockHeight))
            main.post {PageIndicatorGlass.onBackdropChanged()}
        }else indicatorImage=null
        if(PrefsHelper.blurEnabled && PrefsHelper.dockStyle!=0){
            val height=minOf(raw.height,PerformancePolicy.dockHeight)
            val radius=PrefsHelper.dockBlur*(height/800f)*(if(PrefsHelper.dockStyle==2).28f else 1f)
            blur(raw,PrefsHelper.dockBlur,PrefsHelper.dockStyle==2,height)?.let {publish(it,radius)}
        }
    }

    private fun blur(raw:Bitmap,requested:Int,liquid:Boolean,height:Int):Bitmap? {
        val width=(raw.width.toFloat()*height/raw.height).roundToInt().coerceAtLeast(1)
        val radius=requested*(height/800f)*(if(liquid).28f else 1f)
        if(PrefsHelper.gpuEffects && raw.config==Bitmap.Config.HARDWARE){
            val result=runCatching {GpuWallpaperBlur.blurDock(raw,width,height,radius)}.getOrNull()
            if(result!=null)return result
        }
        val scaled=Bitmap.createScaledBitmap(raw,width,height,true)
        val readable=if(scaled.config==Bitmap.Config.HARDWARE)scaled.copy(Bitmap.Config.ARGB_8888,false)else scaled
        if(readable==null){
            if(scaled!==raw)scaled.recycle()
            return null
        }
        val result=if(radius>=1f)FastBlur.blur(readable,radius.roundToInt())else readable.copy(Bitmap.Config.ARGB_8888,false)
        if(readable!==raw && readable!==result)readable.recycle()
        if(scaled!==raw && scaled!==readable && scaled!==result)scaled.recycle()
        return result
    }
    private fun publish(result:Bitmap,radius:Float){
        image=result
        main.post {DockMaterial.onSettingsChanged()}
        if(!reported){reported=true;GlassLogger.i("NeutralBackdrop","Neutral dock source ready: ${result.width}x${result.height}, radius=$radius")}
    }
}
