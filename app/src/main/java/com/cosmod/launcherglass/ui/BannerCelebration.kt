package com.cosmod.launcherglass.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.cosmod.launcherglass.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

internal class BannerCelebration(context:Context):View(context) {
    private data class Spark(val vx:Float,val vy:Float,val size:Float,val color:Int,val phase:Float)
    private data class Tap(val amplitude:Int,val durationMs:Long)
    private data class Burst(val time:Float,val x:Float,val y:Float,val color:Int,val tap:Tap,val sparks:Array<Spark>)

    private val density=resources.displayMetrics.density
    private val random=Random(136)
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {strokeCap=Paint.Cap.ROUND}
    private val glow=RadialGradient(0f,0f,1f,intArrayOf(Color.WHITE,0x70ffffff,Color.TRANSPARENT),
        floatArrayOf(0f,.29f,1f),Shader.TileMode.CLAMP)
    private val glowMatrix=Matrix()
    private val bursts=arrayOf(
        burst(.49f,.49f,.22f,0xffad8cff.toInt(),0xff83dcff.toInt(),Tap(200,48),48,116f),
        burst(.64f,.24f,.38f,0xffffce80.toInt(),0xffff9a7a.toInt(),Tap(235,72),36,83f),
        burst(.79f,.76f,.32f,0xfffc8ac5.toInt(),0xfff0d0ff.toInt(),Tap(255,112),40,95f)
    )
    private var player:MediaPlayer?=null
    private var vibrator:Vibrator?=null
    private var startedAt=0L
    private var nextBurst=0
    private var seconds=0f
    private var sourceX=0f
    private var sourceY=0f
    private val sourceLocation=IntArray(2)
    private val overlayLocation=IntArray(2)
    private val slop=ViewConfiguration.get(context).scaledTouchSlop
    private var heldBanner:View?=null
    private var holdX=0f
    private var holdY=0f
    private val hold=Runnable {finishHold()}
    private val update=object:Runnable {
        override fun run(){
            seconds=(SystemClock.uptimeMillis()-startedAt)/1000f
            while(nextBurst<bursts.size && seconds>=bursts[nextBurst].time){
                val burst=bursts[nextBurst++]
                if(seconds-burst.time<.09f)vibrate(burst.tap)
            }
            if(seconds<2.25f){
                invalidate()
                postOnAnimation(this)
            }else visibility=INVISIBLE
        }
    }

    init {
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility=INVISIBLE
    }

    private fun burst(time:Float,x:Float,y:Float,main:Int,accent:Int,tap:Tap,count:Int,radius:Float):Burst {
        val sparks=Array(count){index->
            val angle=(index+random.nextFloat()*.58f)*2*PI/count
            val velocity=radius*(if(index%3==0).48f+random.nextFloat()*.18f else .76f+random.nextFloat()*.24f)
            Spark(cos(angle).toFloat()*velocity,sin(angle).toFloat()*velocity,1f+random.nextFloat()*1.5f,
                if(index%4==0)accent else main,random.nextFloat()*7f)
        }
        return Burst(time,x,y,main,tap,sparks)
    }

    fun onBannerTouch(anchor:View,event:MotionEvent){
        when(event.actionMasked){
            MotionEvent.ACTION_DOWN->{
                cancelHold()
                if(!anchor.isEnabled)return
                heldBanner=anchor
                holdX=event.rawX;holdY=event.rawY
                postDelayed(hold,HOLD_MS)
            }
            MotionEvent.ACTION_MOVE->{
                val dx=event.rawX-holdX
                val dy=event.rawY-holdY
                if(dx*dx+dy*dy>slop*slop)cancelHold()
            }
            MotionEvent.ACTION_UP->{
                if(event.eventTime-event.downTime>=HOLD_MS)finishHold() else cancelHold()
            }
            MotionEvent.ACTION_CANCEL,MotionEvent.ACTION_POINTER_DOWN->cancelHold()
        }
    }

    private fun finishHold(){
        val anchor=heldBanner ?: return
        cancelHold()
        if(anchor.isAttachedToWindow && anchor.isShown && anchor.hasWindowFocus() && anchor.isEnabled)play(anchor)
    }

    private fun cancelHold(){
        removeCallbacks(hold)
        heldBanner=null
    }

    private fun play(anchor:View){
        if(visibility==VISIBLE || player!=null)return
        player=runCatching {MediaPlayer.create(context,R.raw.cpi_fireworks)}.getOrNull()
        player?.apply {
            setOnCompletionListener {releaseAudio()}
            setOnErrorListener {_,_,_->releaseAudio();true}
            if(runCatching {start()}.isFailure)releaseAudio()
        }
        if(!ValueAnimator.areAnimatorsEnabled())return
        anchor.getLocationInWindow(sourceLocation)
        getLocationInWindow(overlayLocation)
        sourceX=sourceLocation[0]-overlayLocation[0]+anchor.width*.5f
        sourceY=sourceLocation[1]-overlayLocation[1]+anchor.height*.6f
        vibrator=if(Settings.System.getInt(context.contentResolver,Settings.System.HAPTIC_FEEDBACK_ENABLED,1)==1)
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator?.takeIf {it.hasVibrator()}
            else null
        seconds=0f
        nextBurst=0
        startedAt=SystemClock.uptimeMillis()
        visibility=VISIBLE
        postOnAnimation(update)
    }

    @Suppress("DEPRECATION")
    private fun vibrate(tap:Tap){
        val motor=vibrator ?: return
        val amplitude=if(motor.hasAmplitudeControl())tap.amplitude else VibrationEffect.DEFAULT_AMPLITUDE
        val pulse=VibrationEffect.createOneShot(tap.durationMs,amplitude)
        if(Build.VERSION.SDK_INT>=33)motor.vibrate(pulse,VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
        else motor.vibrate(pulse,AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build())
    }

    override fun onDraw(canvas:Canvas){
        for(burst in bursts){
            val time=seconds-burst.time
            val centerX=width*burst.x
            val centerY=height*burst.y
            if(time<0f){
                if(time>=-.38f)drawRocket(canvas,burst,centerX,centerY,(1f+time/.38f).coerceIn(0f,1f))
                continue
            }
            if(time>1.32f)continue
            val progress=time/1.32f
            val fade=(1f-progress)*(1f-progress)
            if(time<.27f){
                val flare=1f-time/.27f
                val radius=(19f+time*170f)*density
                glowMatrix.setScale(radius,radius)
                glowMatrix.postTranslate(centerX,centerY)
                glow.setLocalMatrix(glowMatrix)
                paint.shader=glow
                paint.alpha=(154*flare*flare).toInt()
                canvas.drawCircle(centerX,centerY,radius,paint)
                paint.shader=null
            }
            val distance=travel(progress)*density
            val previous=(progress-.066f).coerceAtLeast(0f)
            val tail=travel(previous)*density
            val drop=48f*progress*progress*density
            val tailDrop=48f*previous*previous*density
            for(spark in burst.sparks){
                val x=centerX+spark.vx*distance
                val y=centerY+spark.vy*distance+drop
                val shimmer=if(progress<.38f)1f else .76f+.24f*cos(spark.phase+time*29f)
                paint.color=spark.color
                paint.alpha=(218f*fade*shimmer).toInt()
                paint.strokeWidth=spark.size*density*(.55f+.45f*fade)
                canvas.drawLine(centerX+spark.vx*tail,centerY+spark.vy*tail+tailDrop,x,y,paint)
                paint.color=Color.WHITE
                paint.alpha=(239f*fade*shimmer).toInt()
                canvas.drawCircle(x,y,spark.size*density*.52f,paint)
            }
        }
    }

    private fun travel(t:Float):Float {
        val remaining=1f-t
        return 1f-remaining*remaining*remaining
    }

    private fun drawRocket(canvas:Canvas,burst:Burst,x:Float,y:Float,t:Float){
        val progress=t*t*(3f-2f*t)
        val headX=sourceX+(x-sourceX)*progress
        val headY=sourceY+(y-sourceY)*progress
        val trail=(progress-.14f).coerceAtLeast(0f)
        paint.color=burst.color
        paint.alpha=(180f*t).toInt()
        paint.strokeWidth=1.9f*density
        canvas.drawLine(sourceX+(x-sourceX)*trail,sourceY+(y-sourceY)*trail,headX,headY,paint)
        paint.color=Color.WHITE
        paint.alpha=(235f*t).toInt()
        canvas.drawCircle(headX,headY,2.1f*density,paint)
    }

    private fun releaseAudio(){
        player?.release()
        player=null
    }
    fun stop(){
        cancelHold()
        removeCallbacks(update)
        releaseAudio()
        vibrator?.cancel()
        vibrator=null
        visibility=INVISIBLE
    }
    override fun onDetachedFromWindow(){stop();super.onDetachedFromWindow()}

    private companion object {const val HOLD_MS=500L}
}
