package com.cosmod.launcherglass.xposed

import android.graphics.*
import android.os.Bundle
import com.cosmod.launcherglass.ui.WallpaperGlowSettings as G
import com.cosmod.launcherglass.util.*
import kotlin.math.roundToInt

/** Bloom emitted by bright wallpaper pixels. No shape/white lighting is added. */
internal object WallpaperGlow {
    private data class Processing(val radius:Int,val threshold:Int,val saturation:Int,val softness:Int)
    private data class Settings(val enabled:Boolean,val strength:Int,val processing:Processing,val diffusion:Int,val targets:List<Int>)
    @Volatile private var settings=Settings(true,30,Processing(55,35,100,0),50,List(6){100})
    @Volatile var revision=0
        private set
    fun update(bundle:Bundle):Boolean {
        fun n(key:String)=bundle.getInt(key,G.defaults.getValue(key)).coerceIn(0,G.maximum(key))
        val next=Settings(bundle.getBoolean(G.ENABLED,true),n(G.STRENGTH),Processing(n(G.RADIUS),n(G.THRESHOLD),n(G.SATURATION),n(G.SOFTNESS)),n(G.DIFFUSION),
            listOf(n(G.ICONS),n(G.FOLDERS),n(G.WIDGETS),n(G.DOCK),n(G.BANNERS),n(G.LIVE)))
        if(next==settings)return false
        settings=next;revision++
        return true
    }
    fun amount(style:GlassStyle):Float {
        val s=settings
        if(!s.enabled || !style.liquid)return 0f
        val target=style.surfaceScope.coerceIn(0,s.targets.lastIndex)
        return .48f*s.strength*s.targets[target]/10000f
    }
    private fun lightAmount(style:GlassStyle)=amount(style)
    private fun diffusion(style:GlassStyle)=amount(style)*settings.diffusion/100f
    fun active(style:GlassStyle)=diffusion(style)>0f || lightAmount(style)>0f

    private fun diffusionRadius(w:Int,h:Int)=minOf(w,h)*(.015f+settings.processing.radius*.001f+
        settings.processing.softness*.0006f)

    @androidx.annotation.RequiresApi(33)
    class Optics {
        val shader=RuntimeShader(SOURCE)
        var wallpaper:Bitmap?=null
        var source:BitmapShader?=null
    }
    private var shared:Optics?=null
    @androidx.annotation.RequiresApi(33)
    private fun configure(wallpaper:Bitmap,source:RectF,w:Int,h:Int,style:GlassStyle,optics:Optics):RuntimeShader? {
        val amount=lightAmount(style)
        val diffusion=diffusion(style)
        if(amount<=0f && diffusion<=0f)return null
        val dest=RectF(0f,0f,w.toFloat(),h.toFloat())
        if(optics.wallpaper!==wallpaper){
            optics.wallpaper=wallpaper
            optics.source=GlassGeometry.wallpaperShader(wallpaper)
        }
        val sampled=optics.source!!
        sampled.setLocalMatrix(Matrix().apply {setRectToRect(source,dest,Matrix.ScaleToFit.FILL)})
        val processing=settings.processing
        optics.shader.setInputShader("diffuse",sampled)
        optics.shader.setFloatUniform("diffusionMix",diffusion)
        optics.shader.setFloatUniform("diffusionRadius",diffusionRadius(w,h))
        optics.shader.setFloatUniform("lightShape",processing.threshold/100f,processing.saturation/100f,
            .08f+processing.softness*.0012f)
        optics.shader.setFloatUniform("amount",amount)
        optics.shader.setFloatUniform("detail",if(PerformancePolicy.economy)0f else 1f)
        return optics.shader
    }
    @androidx.annotation.RequiresApi(33)
    fun wrap(base:Shader,wallpaper:Bitmap,source:RectF,w:Int,h:Int,style:GlassStyle,optics:Optics?=null):Shader {
        if(!active(style))return base
        val lens=optics ?: (shared ?: Optics().also {shared=it})
        val shader=configure(wallpaper,source,w,h,style,lens) ?: return base
        shader.setInputShader("plate",base)
        return shader
    }
    @androidx.annotation.RequiresApi(33)
    fun effect(input:RenderEffect?,wallpaper:Bitmap,source:RectF,w:Int,h:Int,style:GlassStyle):RenderEffect? {
        if(!active(style))return input
        val lens=shared ?: Optics().also {shared=it}
        val shader=configure(wallpaper,source,w,h,style,lens) ?: return input
        val glow=RenderEffect.createRuntimeShaderEffect(shader,"plate")
        return if(input==null)glow else RenderEffect.createChainEffect(glow,input)
    }
    fun applyCpu(base:Bitmap,wallpaper:Bitmap,source:RectF,style:GlassStyle):Bitmap {
        val fogAmount=diffusion(style)
        val amount=lightAmount(style)
        if(amount<=0f && fogAmount<=0f)return base
        val w=base.width;val h=base.height
        val dest=RectF(0f,0f,w.toFloat(),h.toFloat())
        val plate=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(plate)
        val readable=WallpaperHelper.forCanvas(wallpaper,canvas)
        if(readable.config==Bitmap.Config.HARDWARE){plate.recycle();return base}
        val shader=BitmapShader(readable,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply {setRectToRect(source,dest,Matrix.ScaleToFit.FILL)})
        }
        canvas.drawRect(dest,Paint(Paint.FILTER_BITMAP_FLAG).apply {this.shader=shader})
        val output=FastBlur.blur(plate,diffusionRadius(w,h).roundToInt().coerceAtLeast(1))
        val fogPixels=IntArray(w*h)
        output.getPixels(fogPixels,0,w,0,0,w,h)
        if(output!==plate)output.recycle()
        plate.recycle()
        val pixels=IntArray(w*h)
        base.getPixels(pixels,0,w,0,0,w,h)
        val processing=settings.processing
        val threshold=processing.threshold/100f
        val saturation=processing.saturation/100f
        val width=.08f+processing.softness*.0012f
        for(i in pixels.indices){
            val c=pixels[i];val f=fogPixels[i]
            if(Color.alpha(c)==0)continue
            val luma=(.2126f*Color.red(f)+.7152f*Color.green(f)+.0722f*Color.blue(f))/255f
            val knee=((luma-threshold+width)/(width*2)).coerceIn(0f,1f)
            val light=amount*knee*knee*(3f-2f*knee)
            fun channel(v:Int,sample:Int):Int {
                val softened=v+(sample-v)*fogAmount
                val lit=(luma+(sample/255f-luma)*saturation).coerceIn(0f,1f)*light
                return (softened+(lit*255f-softened*.42f*light).coerceAtLeast(0f)*(1f-softened/255f)).roundToInt().coerceIn(0,255)
            }
            pixels[i]=Color.argb(Color.alpha(c),channel(Color.red(c),Color.red(f)),
                channel(Color.green(c),Color.green(f)),channel(Color.blue(c),Color.blue(f)))
        }
        return Bitmap.createBitmap(pixels,base.width,base.height,Bitmap.Config.ARGB_8888)
    }
    private const val SOURCE="""
        uniform shader plate;
        uniform shader diffuse;
        uniform float diffusionMix;
        uniform float diffusionRadius;
        uniform float amount;
        uniform float detail;
        uniform float3 lightShape;
        half4 main(float2 xy) {
            half4 c=plate.eval(xy);
            float a=float(c.a);
            if(a<=.00001)return c;
            float3 rgb=clamp(float3(c.rgb)/a,0.0,1.0);
            float d=diffusionRadius;
            float3 average=float3(diffuse.eval(xy).rgb);
            if(detail>.5){
                average=.36*average+.16*float3(diffuse.eval(xy+float2(d,d)).rgb)
                    +.16*float3(diffuse.eval(xy+float2(-d,d)).rgb)
                    +.16*float3(diffuse.eval(xy+float2(d,-d)).rgb)
                    +.16*float3(diffuse.eval(xy-float2(d,d)).rgb);
            }
            float luma=dot(average,float3(.2126,.7152,.0722));
            float weight=smoothstep(lightShape.x-lightShape.z,lightShape.x+lightShape.z,luma);
            rgb=mix(rgb,clamp(average,0.0,1.0),diffusionMix);
            float3 light=clamp(mix(float3(luma),average,lightShape.y),0.0,1.0);
            rgb+=max(light-rgb*.42,0.0)*(1.0-rgb)*weight*amount;
            return half4(half3(rgb*a),c.a);
        }
    """
}
