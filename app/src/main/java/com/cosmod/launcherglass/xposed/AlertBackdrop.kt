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
    data class Frame(val bitmap:Bitmap,val area:Rect,val time:Long,val generation:Long,val rotation:Int)
    private data class Captured(val bitmap:Bitmap,val area:Rect,val time:Long,val duration:Long,val epoch:Int,
        val volume:IBinder?,val rotation:Int)
    private enum class Reader(val scope:Int) {
        LIVE_ALERT(GlassSurface.LIVE), BANNER(GlassSurface.BANNERS), VOLUME(GlassSurface.VOLUME)
    }
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
    private var generation=0L
    private var alertFrame:Frame?=null
    private var volumeFrame:Frame?=null
    private var volumeOwner:IBinder?=null
    private var lastCaptureWasVolume=false
    private var alertRetryAt=0L
    private var volumeRetryAt=0L
    private val retryCapture=Runnable {nextFrame()}
    private var completed=0;private var captureMs=0L
    private var skippedVsyncs=0L
    private var captureWidth=0;private var captureHeight=0
    private val intervals=LongArray(180);private var intervalCount=0;private var lastPresent=0L
    private val screenPosition=IntArray(2)
    fun diagnostics():String {
        val samples=intervals.take(minOf(intervalCount,intervals.size)).sorted()
        fun percentile(p:Float)=if(samples.isEmpty())0L else samples[((samples.size-1)*p).toInt()]/1_000_000
        return "source enabled=$enabled, failed=$failed, frames=$completed, capture=${captureWidth}x$captureHeight, mean=${if(completed>0)captureMs/completed else 0}ms, delivery p50=${percentile(.5f)}ms p95=${percentile(.95f)}ms, skipped vsyncs=$skippedVsyncs, readers=${readers.size}, relays=$relayCount, requests=$requestCount, error=$lastError"
    }
    fun configure(active:Boolean,context:Context?=null){
        if(context!=null){power=context.getSystemService(Context.POWER_SERVICE) as? PowerManager;keyguard=context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager}
        if(active!=enabled){epoch++;if(active)failed=false}
        enabled=active
        if(!active)clearReaders()
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
            if(readers.values.none {it==Reader.VOLUME}){volumeFrame=null;volumeOwner=null}
        }
    }
    fun forgetBanner(view: View) {
        if (readers[view] == Reader.BANNER) {
            readers.remove(view)
            AlertGlassLens.forget(view)
        }
    }
    fun canSample() = enabled && !failed && power?.isInteractive == true && keyguard?.isKeyguardLocked == false
    fun isOnLockscreen() = keyguard?.isKeyguardLocked == true

    private fun frameFor(view: View, reader:Reader): Frame? {
        if (!enabled || failed || view.display?.displayId!=Display.DEFAULT_DISPLAY) return null
        readers[view] = reader
        nextFrame()
        val frame=if(reader==Reader.VOLUME)volumeFrame.takeIf {volumeOwner==view.windowToken} else alertFrame
        return frame?.takeIf {it.rotation==view.display?.rotation && SystemClock.uptimeMillis()-it.time<=180L}
    }
    private fun nextFrame(){
        if(scheduled || busy || !enabled || failed || readers.isEmpty())return
        scheduled=true
        Choreographer.getInstance().postFrameCallback(tick)
    }
    private val tick=Choreographer.FrameCallback {time->
        try {tickFrame(time)}catch(t:Throwable){captureFailed("UI setup failed",t)}
    }
    private fun captureFailed(message:String,error:Throwable){
        failed=true
        epoch++
        lastError=error.toString()
        readers.keys.weakSnapshot().forEach {it.invalidate()}
        clearReaders()
        GlassLogger.e("AlertBackdrop","$message; native background retained",error)
    }

    private fun clearReaders(){
        main.removeCallbacks(retryCapture)
        readers.keys.weakSnapshot().forEach(AlertGlassLens::forget)
        readers.clear()
        volumeFrame=null;volumeOwner=null;alertFrame=null
        alertRetryAt=0L;volumeRetryAt=0L;lastCaptureWasVolume=false
        lastPresent=0L;pacing.reset()
    }

    private fun streamUnavailable(volume:Boolean,error:Throwable?=null){
        val retry=SystemClock.uptimeMillis()+500L
        if(volume){volumeFrame=null;volumeRetryAt=retry}
        else {alertFrame=null;alertRetryAt=retry}
        if(error!=null){
            lastError=error.toString()
            GlassLogger.limited("AlertBackdrop",if(volume)"volume-capture" else "alert-capture"){
                "App backdrop capture unavailable: "+(error.cause ?: error).javaClass.simpleName
            }
            readers.keys.weakSnapshot().forEach {
                if((readers[it]==Reader.VOLUME)==volume)it.invalidate()
            }
        }
        nextFrame()
    }

    private fun tickFrame(frameTime:Long){
        scheduled=false
        if(!enabled || failed || busy || readers.isEmpty())return
        val now=SystemClock.uptimeMillis()
        val fps=PerformancePolicy.captureFps()
        // Skip visibility scans and lock-state IPC between capture slots.
        if(!pacing.ready(frameTime,fps,PerformancePolicy.capturePacingHz)){
            skippedVsyncs++
            nextFrame()
            return
        }
        val alertViews=ArrayList<View>()
        val volumeViews=ArrayList<View>()
        var volume:View?=null
        var volumeWindowToken:IBinder?=null
        val iterator=readers.entries.iterator()
        while(iterator.hasNext()){
            val (view,reader)=iterator.next()
            if(!view.isAttachedToWindow){
                iterator.remove()
                AlertGlassLens.forget(view)
                continue
            }
            if(!view.isShown || view.windowVisibility!=View.VISIBLE || !(NativeAlertMaterial.visibleAlpha(view)>0f))continue
            when(reader){
                Reader.LIVE_ALERT->alertViews.add(view)
                Reader.BANNER->if(NotificationBannerGlass.isBanner(view))alertViews.add(view)
                Reader.VOLUME->if(VolumeGlass.isVisibleTrack(view)){
                    volumeViews.add(view)
                    if(volume==null){
                        val token=view.windowToken
                        if(token!=null){volume=view;volumeWindowToken=token}
                    }
                }
            }
        }
        if((alertViews.isEmpty() && volumeViews.isEmpty()) || !canSample()){
            // A worker finishing after close must not seed the next app's card.
            epoch++
            if(alertFrame!=null || volumeFrame!=null){
                alertViews.forEach {it.invalidate()}
                volumeViews.forEach {it.invalidate()}
            }
            clearReaders()
            return
        }
        if(volumeOwner!=volumeWindowToken){volumeFrame=null;volumeOwner=volumeWindowToken;volumeRetryAt=0L}
        if(alertViews.isEmpty()){alertFrame=null;alertRetryAt=0L}
        val volumeReady=volume!=null && now>=volumeRetryAt
        val alertsReady=alertViews.isNotEmpty() && now>=alertRetryAt
        if(!volumeReady && !alertsReady){
            if(volume==null && alertViews.isEmpty()){clearReaders();return}
            val retry=minOf(if(volume!=null)volumeRetryAt else Long.MAX_VALUE,
                if(alertViews.isNotEmpty())alertRetryAt else Long.MAX_VALUE)
            main.removeCallbacks(retryCapture)
            main.postAtTime(retryCapture,retry)
            return
        }
        // Share the capture budget while the volume window overlaps a banner.
        val captureVolume=volumeReady && (!alertsReady || !lastCaptureWasVolume)
        val volumeView=volume.takeIf {captureVolume}
        val owner=volumeWindowToken.takeIf {captureVolume}
        lastCaptureWasVolume=captureVolume
        val host=volumeView ?: alertViews.first()
        val rotation=host.display?.rotation ?: 0
        val metrics=host.resources.displayMetrics
        val area=Rect(0,0,metrics.widthPixels,metrics.heightPixels)
        if(!captureVolume && area.height()>area.width()){
            // Include the card plus a refraction/blur margin. Round the crop
            // to display bands so small animations reuse the same capture.
            val bucket=(area.height()/8).coerceAtLeast(1)
            val padding=(32f*metrics.density).toInt()
            var bottom=bucket*2
            for(view in alertViews){
                view.getLocationOnScreen(screenPosition)
                val height=(view.height*maxOf(1f,view.scaleY)).toInt()
                bottom=maxOf(bottom,screenPosition[1]+height+maxOf(padding*2,height/2))
            }
            area.bottom=minOf(area.bottom,((bottom+bucket-1)/bucket)*bucket)
        }
        val surfaces=if(volumeView!=null){
            // Capture the composed display through the volume window's
            // footprint. Exclude that window alone so the notification
            // shade and other windows under it remain in the sample.
            val pad=(32f*metrics.density).toInt()
            area.setEmpty()
            for(view in volumeViews){
                if(view.windowToken!=owner)continue
                view.getLocationOnScreen(screenPosition)
                val left=(screenPosition[0]-pad).coerceIn(0,metrics.widthPixels)
                val top=(screenPosition[1]-pad).coerceIn(0,metrics.heightPixels)
                val right=(screenPosition[0]+view.width+pad).coerceIn(0,metrics.widthPixels)
                val bottom=(screenPosition[1]+view.height+pad).coerceIn(0,metrics.heightPixels)
                area.union(left,top,right,bottom)
            }
            volumeWindow(volumeView)
        }else excludedSurfaces()
        if(surfaces.isEmpty() || area.isEmpty){streamUnavailable(captureVolume);return}
        busy=true
        val width=PerformancePolicy.alertWidth
        val token=epoch;val scale=minOf(1f,width.toFloat()/area.width())
        worker.execute {
            val start=SystemClock.uptimeMillis()
            try {
                val bitmap=capture(area,scale,surfaces)
                val now=SystemClock.uptimeMillis()
                val captured=Captured(bitmap,area,now,now-start,token,owner,rotation)
                // A frame completed between vsync and traversal can still be
                // used for that traversal. Waiting for the next vsync adds lag.
                main.post {
                    busy=false
                    publish(captured)
                    nextFrame()
                }
            }catch(t:Throwable){main.post {
                busy=false
                if(epoch!=token)nextFrame()
                else if(t is ClassNotFoundException || t is NoSuchMethodException || t is LinkageError)
                    captureFailed("Capture contract unavailable",t)
                else streamUnavailable(captureVolume,t)
            }}
        }
    }
    private fun publish(captured:Captured){
        if(!enabled || captured.epoch!=epoch){
            captured.bitmap.recycle()
            return
        }
        val volumeCapture=captured.volume!=null
        val visible=ArrayList<View>()
        for((view,reader) in readers){
            if(volumeCapture){
                if(reader!=Reader.VOLUME || view.windowToken!=captured.volume)continue
            }else if(reader==Reader.VOLUME)continue
            if(view.isAttachedToWindow && view.isShown && view.display?.rotation==captured.rotation)visible.add(view)
        }
        if(visible.isEmpty()){
            captured.bitmap.recycle()
            return
        }
        val frame=Frame(captured.bitmap,captured.area,captured.time,++generation,captured.rotation)
        if(volumeCapture){volumeFrame=frame;volumeOwner=captured.volume}
        else alertFrame=frame
        captureWidth=captured.bitmap.width
        captureHeight=captured.bitmap.height
        completed++
        captureMs+=captured.duration
        val delivered=System.nanoTime()
        if(lastPresent!=0L){intervals[intervalCount%intervals.size]=delivered-lastPresent;intervalCount++}
        lastPresent=delivered
        visible.forEach {it.invalidate()}
        if(completed==1 || completed==120)GlassLogger.i("AlertBackdrop",
            "App source: "+captureWidth+"x"+captureHeight+", captures="+completed+", mean="+captureMs/completed+"ms; "+if(volumeCapture)"volume window excluded" else "System UI excluded")
    }
    // Invoked only inside the injected System UI host. Verified on Android 17;
    // The injected framework supplies hidden-API access for native roots.
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

    internal fun excludedSurfaces():Array<SurfaceControl> {
        val r=roots
        val names=r.names.invoke(r.global) as Array<*>
        val surfaces=ArrayList<SurfaceControl>(names.size)
        for(name in names){
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

