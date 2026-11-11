package com.cosmod.launcherglass.xposed

import android.content.Context
import android.content.res.Resources
import android.graphics.*
import android.hardware.*
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.View
import java.util.WeakHashMap
import android.util.LruCache
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import com.cosmod.launcherglass.ui.EdgeSettings
import com.cosmod.launcherglass.util.LiveBlurEngine
import com.cosmod.launcherglass.util.PerformancePolicy
import com.cosmod.launcherglass.util.WallpaperHelper
import kotlin.math.*

/** Retains the native contour; only a shader matrix changes when the phone tilts. */
internal object EdgeLight:SensorEventListener {
    private data class Profile(val enabled:Boolean=true,val motion:Boolean=true,val strength:Int=52,
        val width:Int=25,val softness:Int=65,val spread:Int=55,val direction:Int=315)
    private var profiles=List(EdgeSettings.count){Profile(motion=it<EdgeSettings.LOCK_START)}
    private data class DarkContour(val opacity:Int=0,val widthQuarters:Int=EdgeSettings.DEFAULT_DARK_QUARTERS,
        val softness:Int=0,val overlap:Boolean=true) {
        val widthDp get()=widthQuarters*.25f
    }
    private data class DarkMaskKey(val contour:Any,val quarterPixels:Int,val softness:Int)
    private var darkContours=List(EdgeSettings.count){DarkContour()}
    private val darkMasks=object:LruCache<DarkMaskKey,Bitmap>(2*1024*1024){
        override fun sizeOf(key:DarkMaskKey,value:Bitmap)=value.allocationByteCount
    }
    private val darkPaint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter=PorterDuffColorFilter(Color.BLACK,PorterDuff.Mode.SRC_IN)
    }
    private fun drawDark(canvas:Canvas,bounds:Rect,mask:Bitmap,contour:DarkContour,displayEdge:Float,density:Float){
        if(contour.opacity==0 || bounds.isEmpty)return
        val width=maskWidthQuarters(contour,mask,displayEdge,density)
        val key=DarkMaskKey(GlassGeometry.contourKey(mask),width,contour.softness)
        val ring=darkMasks[key] ?: makeDarkMask(mask,width*.25f,contour.softness).also {darkMasks.put(key,it)}
        darkPaint.alpha=(contour.opacity*255+50)/100
        canvas.drawBitmap(ring,null,bounds,darkPaint)
    }
    private fun maskWidthQuarters(contour:DarkContour,mask:Bitmap,displayEdge:Float,density:Float):Int {
        val scale=minOf(mask.width,mask.height)/displayEdge.coerceAtLeast(1f)
        return (contour.widthDp*density*scale*4f).roundToInt().coerceAtLeast(1)
    }
    private fun makeDarkMask(mask:Bitmap,width:Float,softness:Int):Bitmap{
        val featherWidth=.85f+width*softness*.0082f
        val field=GlassGeometry.lightField(mask)
        val ring=Bitmap.createBitmap(mask.width,mask.height,Bitmap.Config.ALPHA_8)
        val pixels=ByteArray(ring.byteCount)
        for(y in 0 until mask.height){
            val row=y*mask.width
            val output=y*ring.rowBytes
            for(x in 0 until mask.width){
                val coverage=field.alpha[row+x].toInt() and 255
                val distance=(field.depth[row+x]-.5f).coerceAtLeast(0f)
                val feather=((width-distance)/featherWidth).coerceIn(0f,1f)
                pixels[output+x]=(coverage*feather*feather*(3f-2f*feather)).roundToInt().toByte()
            }
        }
        ring.copyPixelsFromBuffer(ByteBuffer.wrap(pixels))
        return ring
    }
    private var sensitivity=50
    private var smoothing=75
    private var sensorTime=0L
    private var angle=315f;private var manager:SensorManager?=null;private var registered=false
    private var sensorPeriod=0
    private var lastFrame=0L
    private var presentedAngle=315f
    private class Target {
        var direct=false
        val rims=LinkedHashSet<Rim>()
    }
    private val views=WeakHashMap<View,Target>()
    private val redrawRoots=LinkedHashSet<View>()
    private var rotationAnchor=WeakReference<View>(null)
    private val visibleBounds=Rect()
    private data class Key(val contour:Any,val profile:Profile,val insetQuarters:Int)
    private class Optics(val image:Bitmap,val shape:BitmapShader,val paint:Paint,val profile:Profile){val bounds=Rect();var placed=false}
    private val cache=object:LruCache<Key,Optics>(6*1024*1024){override fun sizeOf(key:Key,value:Optics)=value.image.allocationByteCount}
    private data class RimKey(val optics:Optics,val width:Int,val height:Int)
    private class Rim(val key:RimKey){
        val node=RenderNode("CPI.iconRim").apply {setPosition(0,0,key.width,key.height)}
        val bounds=Rect(0,0,key.width,key.height)
        var angle=Float.NaN
    }
    private val rims=object:LruCache<RimKey,Rim>(3*1024*1024){
        override fun sizeOf(key:RimKey,value:Rim)=key.optics.image.allocationByteCount
    }
    private var rimRecords=0L
    private var rimDraws=0L
    private var rimRootFrames=0L
    private var idleTiltSkips=0L
    private class Light(val shader:SweepGradient) {
        var x=Float.NaN
        var y=Float.NaN
        var angle=Float.NaN
    }
    private val lights=HashMap<Int,Light>()
    private val lightMatrix=Matrix()
    private val contourBand=Path()
    private val vectorPaint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {style=Paint.Style.STROKE}

    private fun lighting(x:Float,y:Float,profile:Profile):SweepGradient {
        // A tight specular peak with softer shoulders and a muted opposite reflection.
        val light=lights.getOrPut(profile.spread){
            Light(SweepGradient(0f,0f,intArrayOf(0xf8ffffff.toInt(),0xb4eff8ff.toInt(),0x40d5ebff,0x00d5ebff,
                0x00edf6ff,0x27edf6ff,0x6fe6f2ff,0x29e6f2ff,0x00e6f2ff,
                0x00d5ebff,0x40d5ebff,0xb4eff8ff.toInt(),0xf8ffffff.toInt()),
                com.cosmod.launcherglass.util.OpticsMath.edgeLightStops(profile.spread)))
        }
        val a=profile.direction+(if(profile.motion)angle-315f else 0f)
        if(x!=light.x || y!=light.y || a!=light.angle){
            lightMatrix.setRotate(a);lightMatrix.postTranslate(x,y);light.shader.setLocalMatrix(lightMatrix)
            light.x=x;light.y=y;light.angle=a
        }
        return light.shader
    }
    @Synchronized fun update(bundle:Bundle):Boolean{
        fun int(key:String,default:Int)=bundle.getInt(key,default).coerceIn(0,100)
        val next=profiles.indices.map {surface->
            fun n(field:String)=int(EdgeSettings.key(surface,field),EdgeSettings.integerFields.getValue(field))
            fun flag(field:String):Boolean {
                val key=EdgeSettings.key(surface,field)
                return bundle.getBoolean(key,EdgeSettings.booleans.getValue(key))
            }
            val spread=bundle.getInt(EdgeSettings.key(surface,"spread"),EdgeSettings.integerFields.getValue("spread"))
                .coerceIn(0,EdgeSettings.MAX_SPREAD)
            Profile(flag("enabled"),flag("motion"),n("strength"),n("width"),n("softness"),spread,
                bundle.getInt(EdgeSettings.key(surface,"direction"),315).coerceIn(0,360))
        }
        val dark=darkContours.indices.map {surface->
            val opacity=if(bundle.getBoolean(EdgeSettings.key(surface,"dark_enabled"),false))
                int(EdgeSettings.key(surface,"dark_opacity"),EdgeSettings.DEFAULT_DARK_OPACITY) else 0
            val width=bundle.getInt(EdgeSettings.key(surface,"dark_width_qdp"),EdgeSettings.DEFAULT_DARK_QUARTERS)
                .coerceIn(1,EdgeSettings.MAX_DARK_QUARTERS)
            DarkContour(opacity,width,int(EdgeSettings.key(surface,"dark_softness"),0),
                bundle.getBoolean(EdgeSettings.key(surface,EdgeSettings.DARK_OVERLAP),true))
        }
        sensitivity=int("edge_light_sensitivity",50)
        smoothing=int("edge_light_smoothing",75)
        val changed=dark!=darkContours
        darkContours=dark
        if(next==profiles)return changed
        profiles=next
        if(next.none {it.enabled && it.motion && it.strength>0})stop()
        lights.clear()
        cache.evictAll()
        rims.evictAll()
        for((view,target) in views){target.rims.clear();target.direct=false;view.invalidate()}
        return true
    }
    @Synchronized fun updateCacheBudget(economy:Boolean){
        cache.resize((if(economy)3 else 6)*1024*1024)
        rims.resize((if(economy)1 else 3)*1024*1024)
        darkMasks.resize((if(economy)1 else 2)*1024*1024)
    }
    @Synchronized fun diagnostics()="rim draws=$rimDraws, recordings=$rimRecords, root redraws=$rimRootFrames, cache bytes=${rims.size()}, idle tilt skips=$idleTiltSkips"
    private fun stop(){manager?.unregisterListener(this);registered=false;sensorTime=0L}
    private fun watch(view:View,direct:Boolean=true):Target?{
        if(Looper.myLooper()!=Looper.getMainLooper())return null
        val target=views.getOrPut(view){Target()}
        if(direct)target.direct=true
        val period=com.cosmod.launcherglass.util.PerformancePolicy.sensorMicros
        if(registered && sensorPeriod==period)return target
        if(registered)stop()
        // Plugin views may expose no applicationContext; their own context is
        // still valid for services and the screen-off receiver.
        val context=view.context.applicationContext ?: view.context
        val m=context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return target
        val sensor=m.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: m.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return target
        manager=m;registered=m.registerListener(this,sensor,period);sensorPeriod=period
        if(!screenReceiver){
            androidx.core.content.ContextCompat.registerReceiver(context,object:android.content.BroadcastReceiver(){
                override fun onReceive(c:Context?,intent:android.content.Intent?){if(intent?.action==android.content.Intent.ACTION_SCREEN_OFF)stop()}
            },android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
            screenReceiver=true
        }
        return target
    }
    private var screenReceiver=false
    override fun onAccuracyChanged(sensor:Sensor?,accuracy:Int){}
    @Synchronized override fun onSensorChanged(e:SensorEvent){
        val launcher=WallpaperHelper.appContext!=null
        if(launcher && (!LiveBlurEngine.isResumed || LiveBlurEngine.isAppLaunchInProgress)){stop();return}
        if(launcher && LiveBlurEngine.isDrawerTransitioning){sensorTime=0L;return}
        val now=SystemClock.uptimeMillis()
        val fps=if(launcher)PerformancePolicy.fps(if(PerformancePolicy.economy)15 else 24)else PerformancePolicy.fps(60)
        if(now-lastFrame<1000L/fps)return
        val drawerOnly=launcher && LiveBlurEngine.drawerOccludesWorkspace
        fun visible(view:View)=(views[view]?.let {it.direct || it.rims.isNotEmpty()}==true) && view.isAttachedToWindow && view.isShown && view.windowVisibility==View.VISIBLE &&
            (!drawerOnly || IconGlassHook.isDrawerIcon(view))
        val anchor=rotationAnchor.get()?.takeIf(::visible)
            ?: views.keys.firstOrNull(::visible)?.also {rotationAnchor=WeakReference(it)}
        if(anchor==null){stop();return}
        var x=e.values[0]/9.81f;var y=e.values[1]/9.81f
        when(anchor.display?.rotation){
            1->{val old=x;x=y;y=-old}
            2->{x=-x;y=-y}
            3->{val old=x;x=-y;y=old}
        }
        val target=315f+(x-y)*(.3f+sensitivity/100f)*140f
        val quiet=launcher && PrefsHelper.ltpoIdle && !LiveBlurEngine.hasRecentMotion
        val threshold=if(quiet)1.30f else if(launcher).45f else .08f
        if(quiet && abs(target-presentedAngle)<threshold){
            angle=presentedAngle
            lastFrame=now
            sensorTime=0L
            idleTiltSkips++
            return
        }
        val elapsed=if(sensorTime==0L)1f/60 else ((e.timestamp-sensorTime)/1_000_000_000f).coerceIn(.001f,.1f)
        sensorTime=e.timestamp
        angle=com.cosmod.launcherglass.util.OpticsMath.followAngle(angle,target,elapsed,smoothing)
        lastFrame=now
        if(abs(angle-presentedAngle)>threshold){
            presentedAngle=angle
            for((view,target) in views){
                if(!visible(view) || !view.getGlobalVisibleRect(visibleBounds))continue
                if(target.direct)view.postInvalidateOnAnimation()
                if(target.rims.isEmpty())continue
                target.rims.forEach(::recordRim)
                redrawRoots.add(view.rootView)
            }
            for(root in redrawRoots){root.postInvalidateOnAnimation();rimRootFrames++}
            redrawRoots.clear()
        }
    }
    private fun optics(mask:Bitmap,profile:Profile,insetQuarters:Int):Optics {
        val key=Key(GlassGeometry.contourKey(mask),profile,insetQuarters);cache[key]?.let {return it}
        val inset=if(insetQuarters>0)insetQuarters*.25f+1f else 0f
        val width=profile.width
        val softness=profile.softness
        val f=GlassGeometry.lightField(mask);val w=mask.width;val h=mask.height
        val core=max(.78f,min(w,h)*(.0035f+.030f*width/100f))
        val halo=core*(1.2f+softness*.021f)
        val feather=.08f+softness*.0018f
        val curve=FloatArray((halo*32).roundToInt().coerceAtLeast(1)){step->
            val depth=step/8f
            val specular=exp(-.5f*(depth/(core*.68f)).pow(2))
            val inner=exp(-.5f*((depth-core*1.95f)/(core*.42f)).pow(2))
            (1f-feather)*specular+feather*exp(-.5f*(depth/halo).pow(2))+.14f*inner
        }
        val alpha=FloatArray(w*h){i->
            val coverage=(f.alpha[i].toInt() and 255)/255f
            if(f.depth[i]<inset)return@FloatArray 0f
            val sample=max(f.depth[i]-inset-.5f,0f)*8f
            val step=sample.toInt()
            if(step+1<curve.size)coverage*(curve[step]+(curve[step+1]-curve[step])*(sample-step))else 0f
        }
        // Smooth the sampled distance field in both axes, then re-apply the
        // native coverage. This avoids stair steps without bleeding outside.
        val horizontal=FloatArray(w*h)
        for(y in 0 until h){
            val row=y*w
            for(x in 0 until w){
                val i=row+x
                horizontal[i]=alpha[row+maxOf(0,x-1)]+2f*alpha[i]+alpha[row+minOf(w-1,x+1)]
            }
        }
        val pixels=IntArray(w*h)
        for(y in 0 until h){
            val row=y*w
            val above=maxOf(0,y-1)*w
            val below=minOf(h-1,y+1)*w
            for(x in 0 until w){
                val i=row+x
                val sum=horizontal[above+x]+2f*horizontal[i]+horizontal[below+x]
                val coverage=(f.alpha[i].toInt() and 255)/255f
                val inside=if(inset>0f)(f.depth[i]-inset).coerceIn(0f,1f) else 1f
                val coreAlpha=(alpha[i]*.68f+sum/16f*.32f)*inside
                pixels[i]=Color.argb((minOf(coverage,coreAlpha)*255f).roundToInt().coerceIn(0,255),255,255,255)
            }
        }
        val image=Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888)
        val shape=BitmapShader(image,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP).apply {
            if(android.os.Build.VERSION.SDK_INT>=33)setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
        }
        val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
            shader=ComposeShader(lighting(0f,0f,profile),shape,PorterDuff.Mode.DST_IN)
        }
        return Optics(image,shape,paint,profile).also {cache.put(key,it)}
    }
    @Synchronized fun drawPath(canvas:Canvas,path:Path,bounds:RectF,view:View,strengthScale:Float,surface:Int,highlight:Boolean=true){
        if(strengthScale<=0 || bounds.isEmpty)return
        val profile=profiles[surface]
        val contour=darkContours[surface]
        val density=view.resources.displayMetrics.density
        val offset=if(contour.opacity>0 && !contour.overlap)2f*contour.widthDp*density else 0f
        if(contour.overlap)drawDarkPath(canvas,path,contour,density)
        if(highlight && profile.enabled && profile.strength>0){
            if(profile.motion)watch(view)
            val line=density*(.34f+profile.width*.008f)
            vectorPaint.shader=lighting(bounds.centerX(),bounds.centerY(),profile)
            val alpha=(profile.strength*255/100f*strengthScale).toInt().coerceIn(0,255)
            val save=canvas.save()
            try {
                canvas.clipPath(path)
                if(offset>0f){
                    vectorPaint.strokeWidth=offset
                    contourBand.rewind()
                    if(vectorPaint.getFillPath(path,contourBand))canvas.clipOutPath(contourBand)
                }
                if(profile.softness>0){
                    vectorPaint.strokeWidth=offset+line*(2.6f+profile.softness*.051f)
                    vectorPaint.alpha=(alpha*(.12f+profile.softness*.001f)).toInt()
                    canvas.drawPath(path,vectorPaint)
                }
                vectorPaint.strokeWidth=offset+line*1.65f;vectorPaint.alpha=alpha
                canvas.drawPath(path,vectorPaint)
            }finally {canvas.restoreToCount(save)}
        }
        if(!contour.overlap)drawDarkPath(canvas,path,contour,density)
    }

    private fun drawDarkPath(canvas:Canvas,path:Path,contour:DarkContour,density:Float){
        if(contour.opacity==0)return
        val saved=canvas.save()
        try {
            canvas.clipPath(path)
            vectorPaint.shader=null
            vectorPaint.color=Color.BLACK
            val alpha=contour.opacity/100f
            val width=2f*contour.widthDp*density
            val feather=width*contour.softness*.0082f
            fun stroke(size:Float,opacity:Float){
                vectorPaint.strokeWidth=size
                vectorPaint.alpha=(255f*opacity).roundToInt().coerceIn(0,255)
                canvas.drawPath(path,vectorPaint)
            }
            if(feather>0f){
                val outer=alpha*.22f
                val middle=alpha*.55f
                stroke(width,outer)
                stroke(width-feather*.43f,(middle-outer)/(1f-outer))
                stroke(width-feather,(alpha-middle)/(1f-middle))
            }else stroke(width,alpha)
        }finally {canvas.restoreToCount(saved)}
    }

    @Synchronized fun draw(canvas:Canvas,bounds:Rect,mask:Bitmap,view:View?,liquid:Boolean,shareRim:Boolean=false,surface:Int=0,
        displayEdge:Float=minOf(bounds.width(),bounds.height()).toFloat(),density:Float=Resources.getSystem().displayMetrics.density){
        if(bounds.isEmpty)return
        val profile=profiles[surface]
        val contour=darkContours[surface]
        if(contour.overlap)drawDark(canvas,bounds,mask,contour,displayEdge,density)
        if(profile.enabled && liquid && profile.strength>0){
            val inset=if(contour.opacity>0 && !contour.overlap)maskWidthQuarters(contour,mask,displayEdge,density)else 0
            drawHighlight(canvas,bounds,optics(mask,profile,inset),view,shareRim)
        }
        if(!contour.overlap)drawDark(canvas,bounds,mask,contour,displayEdge,density)
    }

    private fun drawHighlight(canvas:Canvas,bounds:Rect,optics:Optics,view:View?,shareRim:Boolean){
        val motion=optics.profile.motion
        if(motion && shareRim && view!=null && canvas.isHardwareAccelerated &&
            bounds.left==0 && bounds.top==0 && bounds.width()<=512 && bounds.height()<=512){
            val target=watch(view,direct=false)
            if(target!=null){
                val key=RimKey(optics,bounds.width(),bounds.height())
                val rim=rims[key] ?: Rim(key).also {rims.put(key,it)}
                if(target.rims.size<16)target.rims.add(rim)
                else if(rim !in target.rims)target.direct=true
                recordRim(rim)
                canvas.drawRenderNode(rim.node)
                rimDraws++
                return
            }
        }
        if(motion)view?.let {watch(it)}
        drawOptics(canvas,bounds,optics)
    }

    private fun recordRim(rim:Rim){
        val current=rim.key.optics.profile.let {it.direction+if(it.motion)angle-315f else 0f}
        if(rim.angle==current && rim.node.hasDisplayList())return
        val recording=rim.node.beginRecording()
        try {drawOptics(recording,rim.bounds,rim.key.optics)}finally{rim.node.endRecording()}
        rim.angle=current
        rimRecords++
    }

    private fun drawOptics(canvas:Canvas,bounds:Rect,optics:Optics){
        if(!optics.placed || optics.bounds!=bounds){
            optics.shape.setLocalMatrix(Matrix().apply {
                setRectToRect(RectF(0f,0f,optics.image.width.toFloat(),optics.image.height.toFloat()),RectF(bounds),Matrix.ScaleToFit.FILL)
            })
            optics.bounds.set(bounds);optics.placed=true
        }
        lighting(bounds.exactCenterX(),bounds.exactCenterY(),optics.profile)
        optics.paint.alpha=optics.profile.strength*255/100
        canvas.drawRect(bounds,optics.paint)
    }
}
