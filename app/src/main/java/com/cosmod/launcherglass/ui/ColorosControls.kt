package com.cosmod.launcherglass.ui

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
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import kotlin.math.ceil
import kotlin.math.roundToInt

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
    private val sourceDrawListener=android.view.ViewTreeObserver.OnPreDrawListener {
        if(position>0 && capsule==null && content.isDirty){recorded=Int.MIN_VALUE;invalidate()}
        true
    }

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
        if(capsule==null)content.viewTreeObserver.addOnPreDrawListener(sourceDrawListener)
    }
    override fun onDetachedFromWindow(){
        content.removeOnLayoutChangeListener(sourceLayoutListener)
        if(content.viewTreeObserver.isAlive)content.viewTreeObserver.removeOnPreDrawListener(sourceDrawListener)
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
