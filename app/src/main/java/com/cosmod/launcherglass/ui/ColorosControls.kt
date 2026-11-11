package com.cosmod.launcherglass.ui

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.RuntimeShader
import android.os.Build
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.PathInterpolator
import android.widget.CompoundButton
import androidx.appcompat.widget.AppCompatSeekBar
import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

private val touchEase=PathInterpolator(.30f,0f,.10f,1f)

private class ControlMotion(private val owner:View,initial:Float) {
    var value=initial
        private set
    private var target=initial
    private val animator=ValueAnimator().apply {
        addUpdateListener {
            value=it.animatedValue as Float
            owner.invalidate()
        }
    }
    fun snap(next:Float) {
        animator.cancel()
        value=next
        target=next
        owner.invalidate()
    }
    fun move(next:Float,time:Long,animated:Boolean,curve:TimeInterpolator=touchEase) {
        if(!animated || !owner.isLaidOut || !ValueAnimator.areAnimatorsEnabled()){
            snap(next)
            return
        }
        if(next==target)return
        animator.cancel()
        target=next
        animator.setFloatValues(value,next)
        animator.duration=time
        animator.interpolator=curve
        animator.start()
    }
    fun settle()=snap(target)
}

internal fun View.springPress(pressedScale:Float=.975f,observeTouch:((MotionEvent)->Unit)?=null){
    val motion=OnextSpring(1f){scaleX=it;scaleY=it}
    setOnTouchListener { view,event ->
        observeTouch?.invoke(event)
        if(!view.isEnabled || !ValueAnimator.areAnimatorsEnabled())return@setOnTouchListener false
        when(event.actionMasked){
            MotionEvent.ACTION_DOWN->motion.to(pressedScale,631.655f,1f)
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->motion.to(1f,157.914f,.4f)
        }
        false
    }
    addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener{
        override fun onViewAttachedToWindow(view:View)=Unit
        override fun onViewDetachedFromWindow(view:View)=motion.snap(1f)
    })
}

internal class ColorosSwitch(context:Context,private val active:Int,private val inactive:Int,
    private val dark:Boolean,private val animated:Boolean):CompoundButton(context) {
    private val density=resources.displayMetrics.density
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val position=OnextSpring(if(isChecked)1f else 0f){invalidate()}
    private val highlight=ControlMotion(this,0f)
    private val trackOutline=ColorosOutline(density,12f*density,dark,opacity=.72f)
    private val trackBounds=RectF()
    private val spotlight=if(animated)OnextSpotlight(this,dark)else null
    private val slop=ViewConfiguration.get(context).scaledTouchSlop
    private var ready=false
    private var downX=0f
    private var downY=0f
    private var downPosition=0f
    private var dragging=false
    private var dragTarget=0f
    var onPressChanged:((Boolean)->Unit)?=null

    init {
        buttonDrawable=null
        background=null
        minimumWidth=(48*density).roundToInt()
        minimumHeight=(48*density).roundToInt()
        isFocusable=true
        isClickable=true
        ready=true
    }

    override fun getAccessibilityClassName()=android.widget.Switch::class.java.name

    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){
        val left=w*.5f-22f*density
        val top=h*.5f-12f*density
        trackBounds.set(left,top,left+44f*density,top+24f*density)
        trackOutline.setBounds(trackBounds.left.roundToInt(),trackBounds.top.roundToInt(),
            trackBounds.right.roundToInt(),trackBounds.bottom.roundToInt())
    }

    override fun setChecked(checked:Boolean) {
        super.setChecked(checked)
        if(ready && !dragging)settleThumb()
    }

    private fun settleThumb(){
        val end=if(isChecked)1f else 0f
        if(animated && isLaidOut)position.to(end,246.74f,.8f)else position.snap(end)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        if(ready){
            onPressChanged?.invoke(isEnabled && isPressed)
            val lit=isEnabled && (isPressed || isHovered || isFocused)
            highlight.move(if(lit)1f else 0f,if(lit)100L else 250L,animated)
        }
    }

    override fun onDraw(canvas:Canvas) {
        val cx=width*.5f
        val cy=height*.5f
        val left=cx-22f*density
        val right=cx+22f*density
        val r=12f*density
        val fraction=position.value.coerceIn(0f,1f)
        val rtl=layoutDirection==LAYOUT_DIRECTION_RTL
        val x=left+r+20f*density*(if(rtl)1f-fraction else fraction)
        paint.color=ColorUtils.blendARGB(inactive,active,fraction)
        canvas.drawRoundRect(left,cy-r,right,cy+r,r,r,paint)
        paint.color=if(dark)Color.WHITE else Color.BLACK
        paint.alpha=(highlight.value*(if(dark)51 else 31)).roundToInt()
        canvas.drawRoundRect(left,cy-r,right,cy+r,r,r,paint)
        spotlight?.draw(canvas,trackBounds,r)
        trackOutline.draw(canvas)
        paint.color=0x24000000
        canvas.drawCircle(x,cy+density,9.2f*density,paint)
        paint.color=Color.WHITE
        canvas.drawCircle(x,cy,9f*density,paint)
    }

    override fun onTouchEvent(event:MotionEvent):Boolean {
        if(!isEnabled)return false
        when(event.actionMasked){
            MotionEvent.ACTION_DOWN->spotlight?.down(event.x,event.y)
            MotionEvent.ACTION_MOVE->spotlight?.move(event.x,event.y)
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL,MotionEvent.ACTION_POINTER_DOWN->spotlight?.release()
        }
        when(event.actionMasked){
            MotionEvent.ACTION_DOWN->{
                downX=event.x;downY=event.y;downPosition=position.value;dragTarget=downPosition;dragging=false
            }
            MotionEvent.ACTION_MOVE->{
                val dx=event.x-downX
                if(!dragging && abs(dx)>slop && abs(dx)>abs(event.y-downY)){
                    dragging=true
                    val cancel=MotionEvent.obtain(event)
                    cancel.action=MotionEvent.ACTION_CANCEL
                    super.onTouchEvent(cancel)
                    cancel.recycle()
                    parent.requestDisallowInterceptTouchEvent(true)
                    isPressed=true
                }
                if(dragging){
                    val direction=if(layoutDirection==LAYOUT_DIRECTION_RTL)-1 else 1
                    val target=(downPosition+dx*direction/(20f*density)).coerceIn(0f,1f)
                    if(animated)position.to(target,438.65f,1f)else position.snap(target)
                    dragTarget=target
                    return true
                }
            }
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->if(dragging){
                dragging=false
                isPressed=false
                parent.requestDisallowInterceptTouchEvent(false)
                if(event.actionMasked==MotionEvent.ACTION_UP){
                    isChecked=if(isChecked)dragTarget>.35f else dragTarget>=.65f
                }
                settleThumb()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        position.snap(if(isChecked)1f else 0f);highlight.snap(0f)
        spotlight?.cancel()
        super.onDetachedFromWindow()
    }
}

/** Android View port of ONextBox's ColorOs17SettingsSlider (GPL-3.0-only). */
internal class ColorosSlider(context:Context,private val active:Int,private val inactive:Int,
    private val animated:Boolean,private val dark:Boolean):AppCompatSeekBar(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val density=resources.displayMetrics.density
    private val shownProgress=OnextSpring(0f,.0005f){invalidate()}
    private val edgePull=OnextSpring(0f,.15f){invalidate()}
    private val light=OnextSpring(0f){invalidate()}
    private val lightX=OnextSpring(0f,.5f){invalidate()}
    private val lightY=OnextSpring(0f,.5f){invalidate()}
    private val slop=ViewConfiguration.get(context).scaledTouchSlop
    private val lightBounds=RectF()
    private var trackEdge:Shader?=null
    private val gradientMatrix=android.graphics.Matrix()
    private val thumbShadow=RadialGradient(0f,0f,1f,
        intArrayOf(if(dark)0x40000000 else 0x29000000,if(dark)0x40000000 else 0x29000000,Color.TRANSPARENT),
        floatArrayOf(0f,.6f,1f),Shader.TileMode.CLAMP)
    private val spotlight by lazy {
        if(Build.VERSION.SDK_INT>=33)runCatching {RuntimeShader(OnextSliderSpotlight)}.getOrNull()else null
    }
    private val fallbackLight by lazy {RadialGradient(0f,0f,1f,
        intArrayOf(0xeaffffff.toInt(),0x66ffffff,0x14ffffff,Color.TRANSPARENT),
        floatArrayOf(0f,.34f,.72f,1f),Shader.TileMode.CLAMP)}
    private var x=0f
    private var y=0f
    private var startValue=0
    private var dragging=false
    var adjusting=false
        private set
    var onTouchFinished:((Int)->Unit)?=null
    private var paletteGradient:Shader?=null
    var palette:IntArray?=null
        set(value){
            if(field.contentEquals(value))return
            field=value
            paletteGradient=value?.let {LinearGradient(0f,0f,1f,0f,it,null,Shader.TileMode.CLAMP)}
            invalidate()
        }

    init {
        background=null
        minimumHeight=(46*density).roundToInt()
        setPadding((18*density).roundToInt(),0,(18*density).roundToInt(),0)
    }

    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
        super.onMeasure(widthMeasureSpec,heightMeasureSpec)
        setMeasuredDimension(measuredWidthAndState,resolveSizeAndState(minimumHeight,heightMeasureSpec,0))
    }

    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){
        if(w>0 && h>0)trackEdge=LinearGradient(0f,0f,w.toFloat(),h.toFloat(),
            intArrayOf(if(dark)0x40ffffff else 0x99ffffff.toInt(),0x08ffffff,
                if(dark)0x20ffffff else 0x55ffffff),floatArrayOf(0f,.5f,1f),Shader.TileMode.CLAMP)
    }

    fun updateThumb(){
        val fraction=progress.toFloat()/max.coerceAtLeast(1)
        if(dragging || !animated || !isLaidOut)shownProgress.snap(fraction)
        else shownProgress.to(fraction,322f,.96f)
    }

    private fun thumbCenter():Float {
        val fraction=shownProgress.value.coerceIn(0f,1f)
        val left=paddingLeft.toFloat()
        val span=(width-paddingRight-paddingLeft).coerceAtLeast(1)
        return left+span*(if(layoutDirection==LAYOUT_DIRECTION_RTL)1f-fraction else fraction)
    }

    override fun onDraw(canvas:Canvas) {
        if(width<=paddingLeft+paddingRight)return
        val cy=height*.5f
        val halfTrack=10f*density
        val radius=halfTrack
        val rtl=layoutDirection==LAYOUT_DIRECTION_RTL
        val pull=edgePull.value.coerceIn(-7f*density,7f*density)
        val center=thumbCenter()+pull*.68f
        val left=paddingLeft+minOf(0f,pull)
        val right=width-paddingRight+maxOf(0f,pull)
        lightBounds.set(left-radius,cy-radius,right+radius,cy+radius)
        val palette=paletteGradient
        palette?.let {
            val span=(width-paddingLeft-paddingRight).toFloat()
            gradientMatrix.setScale(if(rtl)-span else span,1f)
            gradientMatrix.postTranslate(if(rtl)(width-paddingRight).toFloat() else paddingLeft.toFloat(),0f)
            it.setLocalMatrix(gradientMatrix)
        }
        paint.color=if(palette==null)inactive else Color.WHITE
        paint.shader=palette
        canvas.drawRoundRect(lightBounds,radius,radius,paint)
        paint.shader=null
        if(palette==null){
            paint.color=active
            canvas.drawRoundRect(if(rtl)center-halfTrack else left-halfTrack,cy-halfTrack,
                if(rtl)right+halfTrack else center+halfTrack,cy+halfTrack,halfTrack,halfTrack,paint)
        }
        paint.color=Color.WHITE
        paint.style=Paint.Style.STROKE
        paint.strokeWidth=density
        paint.shader=trackEdge
        val inset=density*.5f
        canvas.drawRoundRect(lightBounds.left+inset,lightBounds.top+inset,lightBounds.right-inset,
            lightBounds.bottom-inset,radius-inset,radius-inset,paint)
        paint.style=Paint.Style.FILL
        paint.shader=null
        val thumbRadius=6f*density
        val shadowRadius=thumbRadius+4f*density
        gradientMatrix.setScale(shadowRadius,shadowRadius)
        gradientMatrix.postTranslate(center,cy+2f*density)
        thumbShadow.setLocalMatrix(gradientMatrix)
        paint.color=Color.WHITE
        paint.shader=thumbShadow
        canvas.drawCircle(center,cy+2f*density,shadowRadius,paint)
        paint.shader=null
        canvas.drawCircle(center,cy,thumbRadius,paint)
        if(isEnabled && light.value>.003f)drawSpotlight(canvas,radius)
    }

    @android.annotation.SuppressLint("NewApi")
    private fun drawSpotlight(canvas:Canvas,radius:Float){
        val amount=light.value.coerceIn(0f,1f)
        val radius3d=260f+440f*amount+if(adjusting)0f else 180f*(1f-amount)
        val intensity=(if(dark).88f else .67f)*amount*amount
        paint.color=Color.WHITE
        paint.blendMode=BlendMode.OVERLAY
        val effect=if(Build.VERSION.SDK_INT>=33 && canvas.isHardwareAccelerated)spotlight else null
        if(effect!=null){
            effect.setFloatUniform("resolution",width.toFloat(),height.toFloat())
            effect.setFloatUniform("u_lightPosAndHeight",lightX.value,lightY.value,radius3d,intensity)
            effect.setFloatUniform("u_lightColorAndAmbient",1f,1f,1f,.4f)
            effect.setFloatUniform("u_lightingParams",.32f,200f,.08f,0f)
            paint.shader=effect
        }else if(radius3d>200f){
            val span=kotlin.math.sqrt(radius3d*radius3d-40000f)
            gradientMatrix.setScale(span,span)
            gradientMatrix.postTranslate(lightX.value,lightY.value)
            fallbackLight.setLocalMatrix(gradientMatrix)
            paint.shader=fallbackLight
            paint.alpha=(intensity*153).roundToInt()
        }else{
            paint.blendMode=null
            return
        }
        canvas.drawRoundRect(lightBounds,radius,radius,paint)
        paint.shader=null;paint.blendMode=null
    }

    private fun track(position:Float) {
        val visual=((position-paddingLeft)/(width-paddingLeft-paddingRight).coerceAtLeast(1)).coerceIn(0f,1f)
        val value=if(layoutDirection==LAYOUT_DIRECTION_RTL)1f-visual else visual
        progress=(value*max).roundToInt()
    }

    override fun onTouchEvent(event:MotionEvent):Boolean {
        if(!isEnabled)return false
        when(event.actionMasked){
            MotionEvent.ACTION_DOWN->{
                x=event.x;y=event.y;startValue=progress;adjusting=true;dragging=false
                if(animated){
                    lightX.snap(x);lightY.snap(height*.5f)
                    light.to(1f,1755f,1f)
                }
            }
            MotionEvent.ACTION_MOVE->if(adjusting){
                val dx=abs(event.x-x)
                if(animated){
                    lightX.to(event.x.coerceIn(0f,width.toFloat()),438.65f,.85f)
                }
                if(!dragging && dx>slop && dx>abs(event.y-y)){
                    dragging=true
                    parent.requestDisallowInterceptTouchEvent(true)
                }
                if(dragging){
                    track(event.x)
                    if(animated){
                        val excess=event.x-event.x.coerceIn(paddingLeft.toFloat(),(width-paddingRight).toFloat())
                        edgePull.to(excess*.27f/(1f+abs(excess)/(26f*density)),690f,.79f)
                    }
                }
            }
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL,MotionEvent.ACTION_POINTER_DOWN->if(adjusting){
                val commit=event.actionMasked==MotionEvent.ACTION_UP &&
                    (dragging || (abs(event.x-x)<=slop && abs(event.y-y)<=slop))
                if(commit){
                    track(event.x)
                    if(!dragging)performClick()
                }else progress=startValue
                adjusting=false
                dragging=false
                parent.requestDisallowInterceptTouchEvent(false)
                if(animated){
                    edgePull.to(0f,236f,.53f,edgePull.velocity)
                    light.to(0f,157.91f,1f)
                }
                if(commit)onTouchFinished?.invoke(progress)
            }
        }
        return true
    }

    override fun performClick():Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        shownProgress.snap(progress.toFloat()/max.coerceAtLeast(1));edgePull.snap(0f)
        light.snap(0f);lightX.snap(0f);lightY.snap(0f)
        super.onDetachedFromWindow()
    }

}

/** ONextBox toolbar material fallback, using the diagonal surface and sweep edge. */
internal class ColorosGlass(private val base:Int,private val radius:Float,
    private val dark:Boolean,density:Float,material:Boolean,legacyEdge:Boolean=false):Drawable() {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect=RectF()
    private var surface:Shader?=null
    private val outline=if(legacyEdge)null else ColorosOutline(density,radius,dark,material)
    private var legacySheen:Shader?=null

    override fun onBoundsChange(bounds:Rect) {
        if(bounds.isEmpty){rect.setEmpty();return}
        rect.set(bounds)
        fun tint(color:Int,amount:Float)=ColorUtils.setAlphaComponent(
            ColorUtils.blendARGB(base,color,amount),Color.alpha(base))
        surface=LinearGradient(rect.left,rect.top,rect.right,rect.bottom,
            intArrayOf(tint(Color.WHITE,if(dark).075f else .12f),base,tint(Color.WHITE,if(dark).045f else 0f)),
            floatArrayOf(0f,.48f,1f),Shader.TileMode.CLAMP)
        if(outline!=null)outline.bounds=bounds
        else {
            val edge=ColorUtils.setAlphaComponent(Color.WHITE,if(dark)85 else 170)
            val soft=ColorUtils.setAlphaComponent(edge,(Color.alpha(edge)*.15f).roundToInt())
            legacySheen=android.graphics.SweepGradient(rect.centerX(),rect.centerY(),
                intArrayOf(soft,edge,Color.TRANSPARENT,soft,edge,soft),
                floatArrayOf(0f,.13f,.38f,.62f,.83f,1f))
        }
    }

    override fun draw(canvas:Canvas) {
        if(rect.isEmpty)return
        val r=radius.coerceAtMost(rect.height()*.5f)
        paint.style=Paint.Style.FILL
        paint.color=Color.WHITE
        paint.shader=surface
        canvas.drawRoundRect(rect,r,r,paint)
        if(outline!=null)outline.draw(canvas)
        else {
            paint.shader=legacySheen
            paint.style=Paint.Style.STROKE
            paint.strokeWidth=1.4f
            rect.inset(.8f,.8f)
            val insetRadius=(r-.8f).coerceAtLeast(0f)
            canvas.drawRoundRect(rect,insetRadius,insetRadius,paint)
            rect.inset(-.8f,-.8f)
        }
    }
    override fun setAlpha(alpha:Int)=Unit
    override fun setColorFilter(colorFilter:android.graphics.ColorFilter?)=Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity()=android.graphics.PixelFormat.TRANSLUCENT
}

internal class ColorosAtmosphere(private val base:Int,private val dark:Boolean):Drawable() {
    private val paint=Paint()
    private var blue:Shader?=null
    private var violet:Shader?=null
    private val shift=android.graphics.Matrix()
    private val handler=android.os.Handler(android.os.Looper.getMainLooper())
    private var running=false
    var onMotion:(()->Unit)?=null
    private val tick=object:Runnable {
        override fun run(){
            if(!running)return
            val phase=(android.os.SystemClock.uptimeMillis()%14000L)*(2.0*Math.PI/14000)
            val x=(kotlin.math.sin(phase)*bounds.width()*.29).toFloat()
            val y=(kotlin.math.cos(phase)*bounds.height()*.15).toFloat()
            shift.setTranslate(x,y);blue?.setLocalMatrix(shift)
            shift.setTranslate(-x,-y);violet?.setLocalMatrix(shift)
            invalidateSelf()
            onMotion?.invoke()
            handler.postDelayed(this,42L)
        }
    }
    fun start(){
        if(running || !ValueAnimator.areAnimatorsEnabled())return
        running=true;handler.post(tick)
    }
    fun stop(){
        running=false;handler.removeCallbacks(tick)
    }
    override fun onBoundsChange(bounds:Rect) {
        val w=bounds.width().toFloat()
        val h=bounds.height().toFloat()
        if(w<=0f || h<=0f)return
        blue=RadialGradient(w*.07f,h*.19f,w*1.33f,
            if(dark)0xd113338b.toInt() else 0x858db5ff.toInt(),Color.TRANSPARENT,Shader.TileMode.CLAMP)
        violet=RadialGradient(w*.99f,h*.59f,w*1.25f,
            if(dark)0xb74b2b71.toInt() else 0x85ca9ef3.toInt(),Color.TRANSPARENT,Shader.TileMode.CLAMP)
    }
    override fun draw(canvas:Canvas) {
        paint.shader=null;paint.color=base
        canvas.drawRect(bounds,paint)
        paint.color=Color.WHITE;paint.shader=blue
        if(blue!=null)canvas.drawRect(bounds,paint)
        paint.shader=violet
        if(violet!=null)canvas.drawRect(bounds,paint)
    }
    override fun setAlpha(alpha:Int)=Unit
    override fun setColorFilter(colorFilter:android.graphics.ColorFilter?)=Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity()=android.graphics.PixelFormat.OPAQUE
}

/** Samples only the content beneath the app bar or the floating tabs. */
internal class ColorosHeaderBlur(context:Context,private val content:View,private val backdrop:Int,var topInset:Int,
    private val scene:Drawable?=null,private val capsule:View?=null,private val capsuleHost:View?=null):View(context) {
    var onBackdropChanged:(()->Unit)?=null
    private val node=RenderNode("CPI.settingsBackdrop")
    private val gaussian=if(capsule==null)RenderNode("CPI.headerGaussian") else null
    private val lightGaussian=if(capsule==null)RenderNode("CPI.headerLightGaussian") else null
    private val mediumGaussian=if(capsule==null &&
        context.getSystemService(android.app.ActivityManager::class.java)?.isLowRamDevice!=true)
        RenderNode("CPI.headerMediumGaussian") else null
    private val gaussianMask=Paint().apply {xfermode=PorterDuffXfermode(PorterDuff.Mode.DST_IN)}
    private val mediumMask=Paint().apply {xfermode=PorterDuffXfermode(PorterDuff.Mode.DST_IN)}
    private val composite=Paint()
    private val scrim=Paint().apply {color=(backdrop and 0x00ffffff) or (if(capsule==null)0x10000000 else 0x35000000)}
    private val fade=Paint().apply {xfermode=PorterDuffXfermode(PorterDuff.Mode.DST_IN)}
    private val density=resources.displayMetrics.density
    private val sample=if(capsule==null).85f else .58f
    private val margin=if(capsule==null)(34f*density).roundToInt() else (40f*density).roundToInt()
    private var position=0
    private var recorded=Int.MIN_VALUE
    private var recordedLeft=Int.MIN_VALUE
    private var recordedTop=Int.MIN_VALUE
    private val sourceLayoutListener=OnLayoutChangeListener { _,_,_,_,_,_,_,_,_ -> refreshScene() }

    init {
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        fun blur(radius:Float)=RenderEffect.createBlurEffect(radius*density*sample,radius*density*sample,Shader.TileMode.CLAMP)
        if(gaussian==null)node.setRenderEffect(blur(17f))
        else {
            gaussian.setRenderEffect(blur(25f))
            mediumGaussian?.setRenderEffect(blur(11f))
            lightGaussian?.setRenderEffect(blur(3.8f))
        }
    }
    override fun onAttachedToWindow(){
        super.onAttachedToWindow()
        recorded=Int.MIN_VALUE
        content.addOnLayoutChangeListener(sourceLayoutListener)
    }
    override fun onDetachedFromWindow(){
        content.removeOnLayoutChangeListener(sourceLayoutListener)
        gaussian?.discardDisplayList()
        lightGaussian?.discardDisplayList()
        mediumGaussian?.discardDisplayList()
        node.discardDisplayList()
        super.onDetachedFromWindow()
    }
    fun refreshScene(){
        recorded=Int.MIN_VALUE
        if(position>0 || capsule!=null)invalidate()
        onBackdropChanged?.invoke()
    }
    fun showScroll(y:Int){
        if(position==y)return
        position=y
        invalidate()
        onBackdropChanged?.invoke()
    }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){
        if(w<=0 || h<=0)return
        val rw=ceil((w+2*margin)*sample).toInt()
        val rh=ceil((h+2*margin)*sample).toInt()
        node.setPosition(0,0,rw,rh)
        if(capsule==null){
            val end=(margin+h)*sample
            gaussianMask.shader=LinearGradient(0f,margin*sample,0f,end,
                intArrayOf(Color.BLACK,Color.BLACK,0xda000000.toInt(),0x6b000000,0x18000000,Color.TRANSPARENT),
                floatArrayOf(0f,.23f,.35f,.49f,.64f,.77f),Shader.TileMode.CLAMP)
            mediumMask.shader=LinearGradient(0f,margin*sample,0f,end,
                intArrayOf(Color.BLACK,Color.BLACK,0xcf000000.toInt(),0x6f000000,0x1b000000,Color.TRANSPARENT),
                floatArrayOf(0f,.38f,.52f,.66f,.81f,1f),Shader.TileMode.CLAMP)
            val full=((topInset+12f*density)/h).coerceIn(.16f,.45f)
            fade.shader=LinearGradient(0f,0f,0f,h.toFloat(),
                intArrayOf(Color.BLACK,Color.BLACK,0xed000000.toInt(),0xb6000000.toInt(),0x52000000,0x12000000,Color.TRANSPARENT),
                floatArrayOf(0f,full,full+(1f-full)*.22f,full+(1f-full)*.44f,full+(1f-full)*.69f,.93f,1f),Shader.TileMode.CLAMP)
        }
        recorded=Int.MIN_VALUE
    }
    private fun ensurePass(pass:RenderNode){
        if(pass.hasDisplayList() && pass.width==node.width && pass.height==node.height)return
        pass.setPosition(0,0,node.width,node.height)
        val drawing=pass.beginRecording()
        try {drawing.drawRenderNode(node)}finally {pass.endRecording()}
    }

    override fun onDraw(canvas:Canvas)=drawBackdrop(canvas,clipToView=true)

    fun drawBackdrop(canvas:Canvas,clipToView:Boolean=false){
        if((position<=0 && capsule==null) || width==0 || height==0 || !canvas.isHardwareAccelerated)return
        val y=position
        val left=(capsule?.left ?: 0)+(capsuleHost?.x?.roundToInt() ?: 0)
        val top=(capsule?.top ?: 0)+(capsuleHost?.y?.roundToInt() ?: 0)
        if(y!=recorded || left!=recordedLeft || top!=recordedTop || !node.hasDisplayList()){
            val target=node.beginRecording()
            try {
                target.drawColor(backdrop)
                target.scale(sample,sample)
                target.translate((margin-left).toFloat(),(margin-top).toFloat())
                scene?.draw(target)
                target.translate(0f,topInset-y+content.translationY)
                content.draw(target)
            }finally{node.endRecording()}
            recorded=y;recordedLeft=left;recordedTop=top
        }
        val clip=canvas.save()
        if(clipToView)canvas.clipRect(0f,0f,width.toFloat(),height.toFloat())
        val target=capsule
        if(target==null){
            val t=(position/(18f*density)).coerceIn(0f,1f)
            composite.alpha=(255*t*t*(3f-2f*t)).roundToInt()
        }
        val layer=if(target==null)canvas.saveLayer(0f,0f,width.toFloat(),height.toFloat(),composite)else -1
        val transform=canvas.save()
        if(target!=null){
            // The cached band stays in window space while the glass outline moves and stretches.
            canvas.translate(target.pivotX,target.pivotY)
            canvas.scale(1f/target.scaleX.coerceAtLeast(.40f),1f/target.scaleY.coerceAtLeast(.40f))
            canvas.translate(-target.pivotX-target.translationX,-target.pivotY-target.translationY)
        }
        canvas.translate(-margin.toFloat(),-margin.toFloat())
        canvas.scale(1f/sample,1f/sample)
        fun drawPass(pass:RenderNode,mask:Paint){
            val w=node.width.toFloat()
            val h=node.height.toFloat()
            val blend=canvas.saveLayer(0f,0f,w,h,null)
            try {
                canvas.drawRenderNode(pass)
                canvas.drawRect(0f,0f,w,h,mask)
            }finally {canvas.restoreToCount(blend)}
        }
        if(gaussian!=null && lightGaussian!=null){
            ensurePass(lightGaussian)
            ensurePass(gaussian)
            canvas.drawRenderNode(lightGaussian)
            mediumGaussian?.let {ensurePass(it);drawPass(it,mediumMask)}
            drawPass(gaussian,gaussianMask)
        }else canvas.drawRenderNode(node)
        canvas.restoreToCount(transform)
        val pad=if(clipToView)0f else margin.toFloat()
        if(target!=null)canvas.drawRect(-pad,-pad,width+pad,height+pad,scrim)
        if(layer>=0){
            canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),fade)
            canvas.restoreToCount(layer)
        }
        canvas.restoreToCount(clip)
    }
}
