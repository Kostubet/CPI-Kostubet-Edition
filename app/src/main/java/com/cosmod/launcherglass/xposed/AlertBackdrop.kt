package com.cosmod.launcherglass.xposed

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.*
import android.view.*
import com.cosmod.launcherglass.util.GlassLogger
import com.cosmod.launcherglass.util.PerformancePolicy
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/** One capture in flight. Draw the newest buffer in the next available UI traversal. */
internal object AlertBackdrop {
    data class Frame(val bitmap:Bitmap,val area:Rect,val time:Long,val generation:Long)
    private data class Captured(val bitmap:Bitmap,val area:Rect,val time:Long,val duration:Long,val epoch:Int,val volume:Boolean)
    private enum class Reader { LIVE_ALERT, BANNER, VOLUME }
    private val readers=WeakHashMap<View,Reader>()
    private val worker=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    private var power:PowerManager?=null;private var keyguard:KeyguardManager?=null
    private var lastError=""
    @Volatile private var enabled=false
    @Volatile private var epoch=0
    private var busy=false
    private var scheduled=false;private var failed=false
    private val pacing=com.cosmod.launcherglass.util.FramePacer()
    private var generation=0L;private var latest:Frame?=null
    private var latestIsVolume=false
    private var completed=0;private var captureMs=0L
    private var skippedVsyncs=0L
    private var adaptiveUntil=0L
    private var limitedRate=0
    private var captureWidth=0;private var captureHeight=0
    private var frameReadyAt=0L
    private val intervals=LongArray(180);private var intervalCount=0;private var lastPresent=0L
    private val screenPosition=IntArray(2)
    fun diagnostics():String {
        val samples=intervals.take(minOf(intervalCount,intervals.size)).sorted()
        fun percentile(p:Float)=if(samples.isEmpty())0L else samples[((samples.size-1)*p).toInt()]/1_000_000
        return "source enabled=$enabled, failed=$failed, frames=$completed, capture=${captureWidth}x$captureHeight, mean=${if(completed>0)captureMs/completed else 0}ms, delivery p50=${percentile(.5f)}ms p95=${percentile(.95f)}ms, skipped vsyncs=$skippedVsyncs, adaptive=$limitedRate, readers=${readers.size}, relays=$relayCount, requests=$requestCount, error=$lastError"
    }
    fun configure(active:Boolean,context:Context?=null){
        if(context!=null){power=context.getSystemService(Context.POWER_SERVICE) as? PowerManager;keyguard=context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager}
        if(active!=enabled){epoch++;if(active)failed=false}
        enabled=active
        if(!active){latest=null;lastPresent=0L;pacing.reset()}
    }
    fun frame(view:View):Frame? {
        if(view.javaClass.name!=LiveAlertTargets.CARD)return null
        return frameFor(view,Reader.LIVE_ALERT)
    }
    fun bannerFrame(view: View): Frame? {
        if (!readers.containsKey(view) && !canSample()) return null
        return frameFor(view,Reader.BANNER)
    }
    fun volumeFrame(view:View):Frame? {
        if(!canSample())return null
        return frameFor(view,Reader.VOLUME)
    }
    fun forgetVolume(view:View){
        if(readers[view]==Reader.VOLUME){
            readers.remove(view)
            AlertGlassLens.forget(view)
        }
    }
    fun forgetBanner(view: View) {
        if (readers[view] == Reader.BANNER) {
            readers.remove(view)
            AlertGlassLens.forget(view)
        }
    }
    fun canSample() = enabled && !failed && power?.isInteractive == true && keyguard?.isKeyguardLocked == false

    private fun frameFor(view: View, reader:Reader): Frame? {
        if (!enabled || failed) return null
        readers[view] = reader
        if (!scheduled) nextFrame()
        return latest?.takeIf {latestIsVolume==(reader==Reader.VOLUME) && SystemClock.uptimeMillis()-frameReadyAt<=180L}
    }
    private fun nextFrame(){scheduled=true;Choreographer.getInstance().postFrameCallback(tick)}
    private val tick=Choreographer.FrameCallback {time->
        try {tickFrame(time)}catch(t:Throwable){captureFailed("UI setup failed",t)}
    }
    private fun captureFailed(message:String,error:Throwable){
        failed=true
        epoch++
        latest=null
        lastError=error.toString()
        readers.keys.toList().forEach {it.invalidate()}
        GlassLogger.e("AlertBackdrop","$message; native background retained",error)
    }
    private fun tickFrame(frameTime:Long){
        scheduled=false
        val now=SystemClock.uptimeMillis()
        if(!PerformancePolicy.adaptiveAlerts || now>=adaptiveUntil)limitedRate=0
        val ready=enabled && !failed && readers.isNotEmpty()
        val fps=minOf(PerformancePolicy.alertFps,if(limitedRate==0)120 else limitedRate)
        val captureDue=ready && !busy && pacing.ready(frameTime,fps,PerformancePolicy.captureRefreshHz)
        // Skip visibility scans and lock-state IPC between capture slots.
        if(ready && !captureDue){
            skippedVsyncs++
            nextFrame()
            return
        }
        val visible=readers.keys.weakSnapshot().filter {
            if(!it.isAttachedToWindow){
                readers.remove(it)
                AlertGlassLens.forget(it)
                return@filter false
            }
            it.isShown && it.windowVisibility==View.VISIBLE &&
                NativeAlertMaterial.visibleAlpha(it)>0f &&
                when(readers[it]){
                    Reader.LIVE_ALERT->true
                    Reader.BANNER->NotificationBannerGlass.isBanner(it)
                    Reader.VOLUME->VolumeGlass.isVisibleTrack(it)
                    null->false
                }
        }
        if(!ready || visible.isEmpty() || !canSample()){
            // A worker finishing after close must not seed the next app's card.
            if(latest!=null || busy)epoch++
            if (latest != null) visible.forEach { it.invalidate() }
            readers.keys.weakSnapshot().forEach(AlertGlassLens::forget)
            readers.clear()
            latest=null;lastPresent=0L;frameReadyAt=0L;pacing.reset()
            return
        }
        nextFrame()
        val metrics=visible.first().resources.displayMetrics
        val area=Rect(0,0,metrics.widthPixels,metrics.heightPixels)
        if(area.height()>area.width()){
            // Include the card plus a refraction/blur margin. Round the crop
            // to display bands so small animations reuse the same capture.
            val bucket=(area.height()/8).coerceAtLeast(1)
            val padding=(32f*metrics.density).toInt()
            var bottom=bucket*2
            for(view in visible){
                view.getLocationOnScreen(screenPosition)
                val height=(view.height*maxOf(1f,view.scaleY)).toInt()
                bottom=maxOf(bottom,screenPosition[1]+height+maxOf(padding*2,height/2))
            }
            area.bottom=minOf(area.bottom,((bottom+bucket-1)/bucket)*bucket)
        }
        val volume=visible.firstOrNull {readers[it]==Reader.VOLUME}
        val surfaces=if(volume!=null){
            // Capture the composed display through the volume window's
            // footprint. Exclude that window alone so the notification
            // shade and other windows under it remain in the sample.
            volume.getLocationOnScreen(screenPosition)
            val pad=(32f*metrics.density).toInt()
            val left=(screenPosition[0]-pad).coerceIn(0,metrics.widthPixels)
            val top=(screenPosition[1]-pad).coerceIn(0,metrics.heightPixels)
            val right=(screenPosition[0]+volume.width+pad).coerceIn(0,metrics.widthPixels)
            val bottom=(screenPosition[1]+volume.height+pad).coerceIn(0,metrics.heightPixels)
            area.set(left,top,right,bottom)
            volumeWindow(volume)
        }else excludedSurfaces()
        if(surfaces.isEmpty() || area.isEmpty)return
        busy=true
        val width=if(limitedRate!=0)minOf(384,PerformancePolicy.alertWidth)else PerformancePolicy.alertWidth
        val token=epoch;val scale=minOf(1f,width.toFloat()/area.width())
        worker.execute {
            val start=SystemClock.uptimeMillis()
            try {
                val bitmap=capture(area,scale,surfaces)
                val now=SystemClock.uptimeMillis()
                val captured=Captured(bitmap,area,now,now-start,token,volume!=null)
                // A frame completed between vsync and traversal can still be
                // used for that traversal. Waiting for the next vsync adds lag.
                main.post {
                    busy=false
                    publish(captured)
                }
            }catch(t:Throwable){main.post {
                busy=false
                if(epoch==token)captureFailed("Capture unavailable",t)
            }}
        }
    }
    private fun publish(captured:Captured){
        if(!enabled || captured.epoch!=epoch || readers.isEmpty()){
            captured.bitmap.recycle()
            return
        }
        val now=SystemClock.uptimeMillis()
        if(PerformancePolicy.adaptiveAlerts && captured.duration>=26L){
            limitedRate=if(captured.duration>=40L)30 else 45
            adaptiveUntil=now+1200L
        }
        latestIsVolume=captured.volume
        latest=Frame(captured.bitmap,captured.area,captured.time,++generation)
        frameReadyAt=now
        captureWidth=captured.bitmap.width
        captureHeight=captured.bitmap.height
        completed++
        captureMs+=captured.duration
        val delivered=System.nanoTime()
        if(lastPresent!=0L){intervals[intervalCount%intervals.size]=delivered-lastPresent;intervalCount++}
        lastPresent=delivered
        readers.keys.weakSnapshot().forEach {
            if(it.isAttachedToWindow && it.isShown && (readers[it]==Reader.VOLUME)==captured.volume)it.invalidate()
        }
        if(completed==1 || completed==120)GlassLogger.i("AlertBackdrop",
            "App source: "+captureWidth+"x"+captureHeight+", captures="+completed+", mean="+captureMs/completed+"ms; "+if(captured.volume)"volume window excluded" else "System UI excluded")
    }
    // Invoked only inside the injected System UI host. Verified on Android 17;
    // Vector supplies the hidden-API access needed by its own native roots.
    @android.annotation.SuppressLint("BlockedPrivateApi")
    private class Roots {
        val type=Class.forName("android.view.WindowManagerGlobal")
        val global=type.getDeclaredMethod("getInstance").invoke(null)
        val names=type.getDeclaredMethod("getViewRootNames")
        val root=type.getDeclaredMethod("getRootView",String::class.java)
        val impl=View::class.java.getDeclaredMethod("getViewRootImpl")
        val surface=Class.forName("android.view.ViewRootImpl").getDeclaredMethod("getSurfaceControl")
        var previous=emptyArray<SurfaceControl>()
        var volume=emptyArray<SurfaceControl>()
    }
    private val roots by lazy {Roots()}
    private fun volumeWindow(view:View):Array<SurfaceControl> {
        val r=roots
        val impl=r.impl.invoke(view) ?: return emptyArray()
        val layer=r.surface.invoke(impl) as? SurfaceControl ?: return emptyArray()
        if(!layer.isValid)return emptyArray()
        if(r.volume.firstOrNull()!==layer)r.volume=arrayOf(layer)
        return r.volume
    }

    private fun excludedSurfaces():Array<SurfaceControl> {
        val r=roots;val surfaces=ArrayList<SurfaceControl>()
        for(name in r.names.invoke(r.global) as Array<*>){
            val view=r.root.invoke(r.global,name) as? View ?: continue
            val impl=r.impl.invoke(view) ?: continue
            val surface=r.surface.invoke(impl) as? SurfaceControl ?: continue
            if(surface.isValid)surfaces.add(surface)
        }
        if(surfaces.size!=r.previous.size || surfaces.indices.any {surfaces[it]!==r.previous[it]})r.previous=surfaces.toTypedArray()
        return r.previous
    }
    private class CaptureApi {
        val type=try {Class.forName("android.window.ScreenCaptureInternal")}catch(_:ClassNotFoundException){Class.forName("android.window.ScreenCapture")}
        val builder=Class.forName(type.name+"\$CaptureArgs\$Builder")
        val create=builder.getConstructor();val crop=builder.getMethod("setSourceCrop",Rect::class.java)
        val scale=builder.getMethod("setFrameScale",Float::class.javaPrimitiveType)
        val exclude=builder.getMethod("setExcludeLayers",Array<SurfaceControl>::class.java)
        val build=builder.getMethod("build")
        val listenerType=Class.forName(type.name+"\$ScreenCaptureListener")
        val createListener=type.getMethod("createSyncCaptureListener")
        val getBuffer=createListener.returnType.getMethod("getBuffer")
        val listenerCreator=listenerType.getField("CREATOR").get(null) as Parcelable.Creator<*>
        val wm=Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)
        val capture=Class.forName("android.view.IWindowManager").getMethod("captureDisplay",Int::class.javaPrimitiveType,build.returnType,listenerType)
        val image=getBuffer.returnType
        val hardware=image.getMethod("getHardwareBuffer");val bitmap=image.getMethod("asBitmap")
        var previousArea=Rect();var previousScale=0f;var previousLayers:Any?=null;var request:Any?=null
        fun args(area:Rect,frameScale:Float,layers:Any):Any {
            if(request==null || previousArea!=area || previousScale!=frameScale || previousLayers!==layers){
                val b=create.newInstance();crop.invoke(b,area);scale.invoke(b,frameScale);exclude.invoke(b,layers)
                request=build.invoke(b);previousArea.set(area);previousScale=frameScale;previousLayers=layers
            }
            return request!!
        }
    }
    /** The platform callback is single-use. Export one stable Binder and
     * forward each result to a fresh local listener, so system_server never
     * receives a new callback identity for every frame. The opaque capture
     * result remains in the platform's native parcel format. */
    private class CaptureRelay(creator:Parcelable.Creator<*>,descriptor:String) : Binder() {
        val target=AtomicReference<IBinder?>()
        val listener:Any
        init {
            attachInterface(null,descriptor)
            val parcel=Parcel.obtain()
            try {
                // ScreenCaptureListener's parcel is a presence flag and a Binder.
                parcel.writeBoolean(true)
                parcel.writeStrongBinder(this)
                parcel.setDataPosition(0)
                listener=checkNotNull(creator.createFromParcel(parcel))
            }finally {parcel.recycle()}
        }
        override fun onTransact(code:Int,data:Parcel,reply:Parcel?,flags:Int):Boolean {
            if(code !in IBinder.FIRST_CALL_TRANSACTION..IBinder.LAST_CALL_TRANSACTION)
                return super.onTransact(code,data,reply,flags)
            val current=target.getAndSet(null) ?: return true
            return current.transact(code,data,reply,flags)
        }
    }
    private fun callbackBinder(listener:Parcelable):IBinder {
        val parcel=Parcel.obtain()
        try {
            listener.writeToParcel(parcel,0)
            parcel.setDataPosition(0)
            check(parcel.readBoolean()){"Capture listener has no native callback"}
            return checkNotNull(parcel.readStrongBinder())
        }finally {parcel.recycle()}
    }
    @Volatile private var relayCount=0
    @Volatile private var requestCount=0L
    private var relay:CaptureRelay?=null
    private val api by lazy {CaptureApi()}
    @Synchronized fun capture(area:Rect,scale:Float,exclude:Any):Bitmap {
        val a=api
        val frame=a.createListener.invoke(null) as Parcelable
        val target=callbackBinder(frame)
        val stream=relay ?: CaptureRelay(a.listenerCreator,checkNotNull(target.interfaceDescriptor)).also {
            relay=it
            relayCount++
        }
        stream.target.set(target)
        try {
            requestCount++
            a.capture.invoke(a.wm,0,a.args(area,scale,exclude),stream.listener)
            val image=a.getBuffer.invoke(frame) ?: error("No app backdrop buffer")
            val hardware=a.hardware.invoke(image) as HardwareBuffer
            return try {a.bitmap.invoke(image) as? Bitmap ?: error("No GPU bitmap")}finally {hardware.close()}
        }catch(t:Throwable){
            // A delayed result from this relay must never reach a later request.
            relay=null
            throw t
        }finally {stream.target.set(null)}
    }
}

