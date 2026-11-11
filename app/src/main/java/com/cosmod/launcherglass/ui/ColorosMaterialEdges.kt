package com.cosmod.launcherglass.ui

import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import androidx.core.graphics.ColorUtils

/** ONextBox's material contour, with its inexpensive diagonal fallback for ordinary controls. */
internal class ColorosOutline(private val density:Float,private val radius:Float,private val dark:Boolean,
    private val material:Boolean=false,private val opacity:Float=1f):Drawable() {
    private val area=RectF()
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private var gradient:Shader?=null
    private var shadedMaterial:Shader?=null
    private val native by lazy {
        if(material && Build.VERSION.SDK_INT>=33)runCatching {RuntimeShader(ColorOs17ShadowEdgeShader)}.getOrNull()
        else null
    }

    override fun onBoundsChange(bounds:Rect){
        area.set(bounds)
        if(bounds.isEmpty)return
        fun white(alpha:Int)=ColorUtils.setAlphaComponent(Color.WHITE,(alpha*opacity).toInt())
        val vertical=LinearGradient(0f,area.top,0f,area.bottom,
            intArrayOf(white(if(dark)61 else 158),white(if(dark)25 else 68),white(if(dark)24 else 61),white(if(dark)77 else 126)),
            floatArrayOf(0f,.20f,.80f,1f),Shader.TileMode.CLAMP)
        val diameter=minOf(area.width(),area.height())
        val round=radius>=diameter*.47f && maxOf(area.width(),area.height())<=diameter*1.12f
        gradient=ComposeShader(vertical,sideSheenFade(area.left,area.right,round),PorterDuff.Mode.DST_IN)
        if(Build.VERSION.SDK_INT>=33)native?.let {shader->
            val w=area.width()
            val h=area.height()
            val r=minOf(radius,h*.5f,w*.5f)
            shader.setFloatUniform("u_commonArray",floatArrayOf(w,h,0f,0f,w,h,r,r,r,r,2.5f,1f))
            val scale=maxOf(1f,density)*.34f
            val normalizedWidth=w/scale
            val perimeter=2f*(w+h)/scale
            val lineScale=(if(dark)2f else 4f)*density/10f
            val start=maxOf(2f*lineScale,2f)
            val alpha=(if(dark).4f else .9f)*opacity
            shader.setFloatUniform("u_edgeArray",floatArrayOf(1f,1f,1f,start,start+maxOf(8f*lineScale,2f),
                start,start+maxOf(2f*lineScale,2f),.7f*alpha,.6f*alpha,
                (normalizedWidth-312f)/perimeter,(normalizedWidth+248f)/perimeter,
                (normalizedWidth-200f)/perimeter,(normalizedWidth+200f)/perimeter,0f,4f,.8f))
            shader.setFloatUniform("u_shadowArray",floatArrayOf(1f,1f,1f,0f,0f,0f,86f*scale,0f,10f*scale,
                1f+60f*scale/w,1f+42f*scale/h,4f,.7f,scale,1f,0f))
            shader.setFloatUniform("u_edge",1f)
            shader.setFloatUniform("u_shadow",0f)
            shadedMaterial=ComposeShader(shader,sideSheenFade(0f,w,round),PorterDuff.Mode.DST_IN)
        }
    }

    override fun draw(canvas:Canvas){
        if(area.isEmpty)return
        val shader=if(canvas.isHardwareAccelerated)shadedMaterial else null
        val r=minOf(radius,area.width()*.5f,area.height()*.5f)
        if(Build.VERSION.SDK_INT>=33 && shader!=null){
            val state=canvas.save()
            canvas.translate(area.left,area.top)
            val w=area.width()
            val h=area.height()
            val band=maxOf(7f*density,r*.42f+4f*density)
            if(w>2*band && h>2*band)canvas.clipOutRect(band,band,w-band,h-band)
            paint.style=Paint.Style.FILL
            paint.shader=shader
            canvas.drawRect(0f,0f,w,h,paint)
            canvas.restoreToCount(state)
        }else{
            paint.style=Paint.Style.STROKE
            paint.strokeWidth=density*.88f
            paint.shader=gradient
            val inset=paint.strokeWidth*.5f
            canvas.drawRoundRect(area.left+inset,area.top+inset,area.right-inset,area.bottom-inset,
                (r-inset).coerceAtLeast(0f),(r-inset).coerceAtLeast(0f),paint)
        }
        paint.shader=null
    }
    override fun setAlpha(alpha:Int){paint.alpha=alpha;invalidateSelf()}
    override fun setColorFilter(colorFilter:ColorFilter?){paint.colorFilter=colorFilter;invalidateSelf()}
    @Deprecated("Deprecated in Java")
    override fun getOpacity()=PixelFormat.TRANSLUCENT
}

internal fun sideSheenFade(left:Float,right:Float,round:Boolean=false):LinearGradient {
    val edge=if(round)0x10000000 else 0x28000000
    val shoulder=if(round)0x68000000 else 0x89000000.toInt()
    val stops=if(round)floatArrayOf(0f,.14f,.32f,.68f,.86f,1f) else floatArrayOf(0f,.10f,.29f,.71f,.90f,1f)
    return LinearGradient(left,0f,right,0f,intArrayOf(edge,shoulder,Color.BLACK,Color.BLACK,shoulder,edge),
        stops,Shader.TileMode.CLAMP)
}

/** One spring-driven light can track a whole material surface, including a moving menu. */
internal class OnextSpotlight(private val owner:View,private val dark:Boolean,private val wide:Boolean=false,private val strength:Float=1f){
    private val density=owner.resources.displayMetrics.density
    private val x=OnextSpring(0f,.5f){owner.invalidate()}
    private val y=OnextSpring(0f,.5f){owner.invalidate()}
    private val light=OnextSpring(0f,.002f){owner.invalidate()}
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val matrix=Matrix()
    private val gradient=RadialGradient(0f,0f,1f,intArrayOf(Color.WHITE,0x8fffffff.toInt(),0x26ffffff,0x00ffffff),
        floatArrayOf(0f,.32f,.74f,1f),Shader.TileMode.CLAMP)
    private val shader by lazy {
        if(Build.VERSION.SDK_INT>=33)runCatching {RuntimeShader(OnextSliderSpotlight)}.getOrNull()
        else null
    }
    private var previousWidth=0
    private var previousHeight=0
    private var held=false

    fun down(px:Float,py:Float){
        held=true
        x.snap(px);y.snap(py)
        light.to(1f,1080f,.88f,0f)
    }
    fun move(px:Float,py:Float){
        if(!held)return
        x.to(px,631.65f,.93f);y.to(py,631.65f,.93f)
    }
    fun release(){
        if(!held)return
        held=false
        light.to(0f,206f,1f,0f)
    }
    fun cancel(){held=false;light.snap(0f);x.snap(0f);y.snap(0f)}

    fun draw(canvas:Canvas,bounds:RectF,corner:Float,intensityScale:Float=1f){
        val progress=light.value.coerceIn(0f,1f)
        if(progress<=.003f || bounds.isEmpty)return
        val radius=density*(43f+(if(wide)220f else 130f)*progress)
        val intensity=strength*intensityScale*progress*(if(dark)if(wide).30f else .33f else .70f)
        val runtime=if(canvas.isHardwareAccelerated)shader else null
        paint.color=Color.WHITE
        if(Build.VERSION.SDK_INT>=33 && runtime!=null){
            if(owner.width!=previousWidth || owner.height!=previousHeight){
                previousWidth=owner.width;previousHeight=owner.height
                runtime.setFloatUniform("resolution",owner.width.toFloat(),owner.height.toFloat())
            }
            runtime.setFloatUniform("u_lightPosAndHeight",x.value,y.value,radius,intensity)
            runtime.setFloatUniform("u_lightColorAndAmbient",1f,1f,1f,.40f)
            runtime.setFloatUniform("u_lightingParams",.40f,200f,.08f,0f)
            paint.shader=runtime
            paint.blendMode=if(dark)BlendMode.PLUS else BlendMode.OVERLAY
        }else{
            matrix.setScale(radius,radius)
            matrix.postTranslate(x.value,y.value)
            gradient.setLocalMatrix(matrix)
            paint.shader=gradient
            paint.alpha=(intensity*166).toInt()
            paint.blendMode=if(dark)BlendMode.SRC_OVER else BlendMode.OVERLAY
        }
        canvas.drawRoundRect(bounds,corner,corner,paint)
        paint.shader=null
        paint.blendMode=null
    }
}

internal class OnextSpotlightView(context:Context,dark:Boolean,private val corner:Float,strength:Float=1f):View(context){
    val light=OnextSpotlight(this,dark,wide=true,strength=strength)
    private val area=RectF()
    init {importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){area.set(0f,0f,w.toFloat(),h.toFloat())}
    override fun onDraw(canvas:Canvas){light.draw(canvas,area,corner)}
    override fun onDetachedFromWindow(){light.cancel();super.onDetachedFromWindow()}
}
