package com.cosmod.launcherglass.xposed

import android.app.KeyguardManager
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.SurfaceControl
import android.view.WindowManager
import com.cosmod.launcherglass.util.FastBlur
import com.cosmod.launcherglass.util.GlassLogger
import com.cosmod.launcherglass.util.GpuWallpaperBlur
import com.cosmod.launcherglass.util.IconPalette
import com.cosmod.launcherglass.util.PerformancePolicy
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Reads wallpaper data only. A single bounded job serves all visible profiles. */
internal object LockscreenBackdrop {
    data class Frame(val images:Map<Int,Bitmap>,val color:Int,val source:String,
        val wallpaperTone:IconPalette.WallpaperTone?,val live:Boolean=false,val livePackage:String?=null)
    const val COLORFUL_ENGINE="com.heytap.colorfulengine"
    private val main=Handler(Looper.getMainLooper())
    private val workerDelegate=lazy {Executors.newSingleThreadExecutor()}
    private val worker by workerDelegate
    private var jobRunning=false
    private var revision=0
    private var pending:Request?=null
    private var desired:Request?=null
    private val refreshLive=Runnable {
        val request=desired ?: return@Runnable
        val last=frame?.takeIf {it.live} ?: return@Runnable
        val next=request.copy(revision=++revision,previousLive=last)
        desired=next
        pending=next
        launch()
    }
    var frame:Frame?=null
        private set
    private var frameHeight=0
    var cacheHits=0
        private set

    private data class Request(val context:Context,val controller:Any?,val animation:Any?,
        val radii:Set<Int>,val height:Int,val revision:Int,val previousLive:Frame?=null)

    fun prepare(context:Context,controller:Any?,animation:Any?,radii:Set<Int>,refresh:Boolean=false){
        if(radii.isEmpty()){stop();return}
        val height=minOf(PerformancePolicy.sourceHeight,if(PerformancePolicy.economy)720 else 1056)
        val old=desired
        val cached=frame
        if(!refresh && old==null && frameHeight==height && cached!=null && cached.source=="file" &&
            cached.images.keys==radii){
            cacheHits++
            return
        }
        if(!refresh && old!=null && old.controller===controller && old.animation===animation &&
            old.radii==radii && old.height==height)return
        main.removeCallbacks(refreshLive)
        val request=Request(context,controller,animation,radii,height,++revision)
        desired=request
        pending=request
        launch()
    }

    fun stop(keepStatic:Boolean=false,clockRadius:Int?=null):Boolean {
        if(desired==null && frame==null)return false
        revision++
        main.removeCallbacks(refreshLive)
        desired=null
        pending=null
        val last=frame
        if(workerDelegate.isInitialized() && (jobRunning || last?.live==true))worker.execute {GpuWallpaperBlur.closePhoto()}
        val budget=if(PerformancePolicy.economy)6L shl 20 else 16L shl 20
        val retained=keepStatic && last?.source=="file" && !last.live &&
            last.images.values.sumOf {it.width.toLong()*it.height*4}<=budget
        frame=when {
            retained->last
            keepStatic && clockRadius!=null && last?.source=="file" && !last.live->
                last.images[clockRadius]?.let {last.copy(images=mapOf(clockRadius to it))}
            else->null
        }
        if(frame==null)frameHeight=0
        return retained
    }

    private fun launch(){
        if(jobRunning)return
        val request=pending ?: return
        pending=null
        jobRunning=true
        worker.execute {
            val start=android.os.SystemClock.uptimeMillis()
            val result=runCatching {load(request)}.onFailure {
                GlassLogger.limited("LockscreenGlass","wallpaper") {"Wallpaper source unavailable"}
            }.getOrNull() ?: request.previousLive
            val elapsed=android.os.SystemClock.uptimeMillis()-start
            main.post {
                jobRunning=false
                if(revision==request.revision){
                    val changed=frame!==result
                    frame=result
                    frameHeight=request.height
                    if(changed)LockscreenGlass.wallpaperChanged()
                    if(result?.live==true){
                        val interval=if(PerformancePolicy.economy)84L else 42L
                        main.postDelayed(refreshLive,(interval-elapsed).coerceAtLeast(16L))
                    }
                }
                launch()
            }
        }
    }

    private fun load(request:Request):Frame {
        val manager=WallpaperManager.getInstance(request.context)
        val previous=request.previousLive
        val flag=if(previous==null)wallpaperFlag(manager) else WallpaperManager.FLAG_LOCK
        val colors=if(previous==null)runCatching {manager.getWallpaperColors(flag)}.getOrNull() else null
        val color=previous?.color ?: colors?.primaryColor?.toArgb() ?: Color.rgb(75,79,101)
        val tone=previous?.wallpaperTone ?: IconPalette.wallpaperTone(colors)
        val livePackage=previous?.livePackage ?: activeLivePackage(manager,flag)
        var sourceName="file"
        // System UI's wallpaper-only capture never includes the notification
        // stack, clocks or the bouncer itself.
        val liveHeight=minOf(request.height,if(PerformancePolicy.economy)480 else 720)
        val live=livePackage?.let {
            captureWallpaperLayer(request,height=liveHeight,livePackage=it) ?: readVisibleWallpaper(request.context)
        }
        if(live==null && previous!=null)return previous
        val source=if(live!=null){
            sourceName="live wallpaper layer"
            live
        }else readWallpaper(manager,flag,request.height) ?: run {
            sourceName="visible wallpaper layer"
            readVisibleWallpaper(request.context)
        } ?: run {
            sourceName="ColorOS wallpaper layer"
            captureWallpaperLayer(request,height=request.height,livePackage=livePackage)
        } ?: run {
            sourceName="ColorOS wallpaper bitmap"
            request.controller?.let {controller->
                runCatching {XposedHelpers.callMethod(controller,"copyWallpaperBitmap") as? Bitmap}.getOrNull()
            }
        }
        if(source==null || source.isRecycled)return Frame(emptyMap(),color,"unavailable",tone)
        val height=minOf(source.height,if(live!=null)liveHeight else request.height)
        val width=(source.width.toFloat()*height/source.height).roundToInt().coerceAtLeast(1)
        val scaled=Bitmap.createScaledBitmap(source,width,height,true)
        val images=LinkedHashMap<Int,Bitmap>()
        try {
            for(radius in request.radii){
                val amount=radius*height/800f
                val output=if(amount<1f)scaled else runCatching {
                    GpuWallpaperBlur.blurPhoto(scaled,width,height,amount)
                }.getOrElse {
                    val readable=if(scaled.config==Bitmap.Config.HARDWARE)scaled.copy(Bitmap.Config.ARGB_8888,false) else scaled
                    try {FastBlur.blur(readable,amount.roundToInt())}
                    finally {if(readable!==scaled)readable.recycle()}
                }
                images[radius]=output
            }
        }finally {
            if(live==null)GpuWallpaperBlur.closePhoto()
            if(scaled!==source && images.values.none {it===scaled})scaled.recycle()
            if(images.values.none {it===source})source.recycle()
        }
        return Frame(images,color,sourceName,tone,live!=null,livePackage)
    }

    fun activeLivePackage(context:Context):String? {
        val manager=WallpaperManager.getInstance(context)
        return activeLivePackage(manager,wallpaperFlag(manager))
    }

    private fun wallpaperFlag(manager:WallpaperManager)=
        if(runCatching {manager.getWallpaperId(WallpaperManager.FLAG_LOCK)<0}.getOrDefault(false))
            WallpaperManager.FLAG_SYSTEM else WallpaperManager.FLAG_LOCK

    private fun activeLivePackage(manager:WallpaperManager,flag:Int):String?=runCatching {
        if(Build.VERSION.SDK_INT>=34){
            manager.getWallpaperInfo(flag)?.packageName ?: manager.wallpaperInfo?.packageName?.takeIf {
                flag==WallpaperManager.FLAG_SYSTEM ||
                    manager.getWallpaperId(WallpaperManager.FLAG_LOCK)==manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM)
            }
        }else manager.wallpaperInfo?.packageName?.takeIf {flag==WallpaperManager.FLAG_SYSTEM}
    }.getOrNull()

    private class VisibleWallpaperApi {
        val service=Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)
        val snapshot=Class.forName("android.view.IWindowManager").getMethod("screenshotWallpaper")
    }
    private val visibleWallpaperApi by lazy {runCatching {VisibleWallpaperApi()}}

    private fun readVisibleWallpaper(context:Context):Bitmap?=runCatching {
        val power=context.getSystemService(PowerManager::class.java)
        val keyguard=context.getSystemService(KeyguardManager::class.java)
        if(power?.isInteractive!=true || keyguard?.isKeyguardLocked!=true)return@runCatching null
        val api=visibleWallpaperApi.getOrThrow()
        // WindowManager selects a wallpaper WindowState and samples that layer alone.
        api.snapshot.invoke(api.service) as? Bitmap
    }.onSuccess {
        if(it==null)GlassLogger.limited("LockscreenGlass","visible-wallpaper-empty") {"Visible wallpaper layer was empty"}
    }.onFailure {
        GlassLogger.limited("LockscreenGlass","visible-wallpaper-failed") {
            "Visible wallpaper layer unavailable: "+(it.cause ?: it).javaClass.simpleName
        }
    }.getOrNull()

    private class LayerApi {
        val capture=try {Class.forName("android.window.ScreenCaptureInternal")}
            catch(_:ClassNotFoundException){Class.forName("android.window.ScreenCapture")}
        val layers=capture.getMethod("captureLayers",SurfaceControl::class.java,Rect::class.java,Float::class.javaPrimitiveType)
        val buffer=layers.returnType.getMethod("getHardwareBuffer")
        val bitmap=layers.returnType.getMethod("asBitmap")
    }
    private val layerApi by lazy {runCatching {LayerApi()}.getOrNull()}

    private fun captureWallpaperLayer(request:Request,height:Int,livePackage:String?):Bitmap?=runCatching {
        val api=layerApi ?: return@runCatching null
        val surface=wallpaperSurface(request.context,request.animation,livePackage) ?: return@runCatching null
        val screen=request.context.resources.displayMetrics
        val scale=(height.toFloat()/screen.heightPixels.coerceAtLeast(1)).coerceIn(.15f,1f)
        val crop=Rect(0,0,screen.widthPixels,screen.heightPixels)
        val result=api.layers.invoke(null,surface,crop,scale) ?: return@runCatching null
        val buffer=api.buffer.invoke(result) as? HardwareBuffer ?: return@runCatching null
        buffer.use {api.bitmap.invoke(result) as? Bitmap}
    }.onFailure {
        GlassLogger.limited("LockscreenGlass","wallpaper-layer") {"Wallpaper layer unavailable: "+(it.cause ?: it).javaClass.simpleName}
    }.getOrNull()

    fun wallpaperSurface(context:Context,wallpaperAnimation:Any?,livePackage:String?):SurfaceControl? {
        fun layer(info:Any?):SurfaceControl? {
            if(info==null)return null
            val type=NativeIconAccess.field(info,"type") as? Int
            val attrs=NativeIconAccess.field(info,"windowAttributes") as? WindowManager.LayoutParams
            val sourcePackage=NativeIconAccess.field(info,"packageName") as? String
            // This is the vendor wallpaper-window API. Validate the window before sampling its layer.
            if(type!=WindowManager.LayoutParams.TYPE_WALLPAPER && attrs?.type!=WindowManager.LayoutParams.TYPE_WALLPAPER &&
                (livePackage==null || sourcePackage!=livePackage))return null
            if(livePackage!=null && sourcePackage!=null && sourcePackage!=livePackage &&
                attrs?.packageName!=livePackage)return null
            return (NativeIconAccess.field(info,"mSurfaceControl") as? SurfaceControl)?.takeIf {it.isValid}
        }

        wallpaperAnimation?.let {animation->
            val info=runCatching {XposedHelpers.callMethod(animation,"getCurrentLockScreenWallpaperWindow",false)}.getOrNull()
            layer(info)?.let {return it}
        }
        val loader=context.classLoader
        val type=XposedHelpers.findClassIfExists("com.oplus.systemui.keyguard.anim.OplusWallpaperAnimControllerImpl",loader)
            ?: return null
        val companion=XposedHelpers.getStaticObjectField(type,"Companion") ?: return null
        val manager=XposedHelpers.newInstance(XposedHelpers.findClass("android.view.OplusWindowManager",loader))
        val windows=XposedHelpers.callMethod(companion,"getWallpaperWindowInfoList",manager) as? List<*> ?: return null
        if(livePackage!=null)return windows.firstNotNullOfOrNull(::layer)
        // ColorOS places the lock wallpaper first when two wallpaper windows are present.
        // With one window it can be the shared wallpaper or the lock wallpaper by itself.
        return layer(windows.firstOrNull())
    }

    // Runs inside System UI. A denied wallpaper read falls back to the captured layer.
    @android.annotation.SuppressLint("MissingPermission")
    private fun readWallpaper(manager:WallpaperManager,flag:Int,target:Int):Bitmap?=runCatching {
        val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
        val descriptor=manager.getWallpaperFile(flag) ?: return@runCatching null
        descriptor.use {BitmapFactory.decodeFileDescriptor(it.fileDescriptor,null,bounds)}
        var scale=1
        while(maxOf(bounds.outWidth,bounds.outHeight)/scale>target*2)scale*=2
        manager.getWallpaperFile(flag)?.use {
            BitmapFactory.decodeFileDescriptor(it.fileDescriptor,null,BitmapFactory.Options().apply {inSampleSize=scale})
        }
    }.getOrNull()
}
