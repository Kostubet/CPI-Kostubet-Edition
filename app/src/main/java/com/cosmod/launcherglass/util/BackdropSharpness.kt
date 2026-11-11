package com.cosmod.launcherglass.util

import android.graphics.*
import android.os.Build
import kotlin.math.roundToInt

/** Blur detail control: 50 is neutral; lower diffuses, higher restores local contrast. */
object BackdropSharpness {
    fun effect(input:RenderEffect?,value:Int,scale:Float):RenderEffect? {
        val v=value.coerceIn(0,100)
        if(v==50)return input
        if(v<50){
            val radius=((50-v)*.30f*scale).coerceAtLeast(.01f)
            return if(input==null)RenderEffect.createBlurEffect(radius,radius,Shader.TileMode.CLAMP)
                else RenderEffect.createBlurEffect(radius,radius,input,Shader.TileMode.CLAMP)
        }
        if(Build.VERSION.SDK_INT<33)return input
        val shader=RuntimeShader(SOURCE).apply {
            setFloatUniform("amount",(v-50)/50f*1.5f)
            setFloatUniform("radius",(2.5f*scale).coerceAtLeast(.75f))
        }
        val sharp=RenderEffect.createRuntimeShaderEffect(shader,"image")
        return if(input==null)sharp else RenderEffect.createChainEffect(sharp,input)
    }
    fun applyCpu(source:Bitmap,value:Int):Bitmap {
        val v=value.coerceIn(0,100)
        if(v==50)return source
        val readable=if(source.config==Bitmap.Config.HARDWARE)source.copy(Bitmap.Config.ARGB_8888,false) else source
        if(readable==null)return source
        if(v<50){
            val result=FastBlur.blur(readable,((50-v)*.3f*source.height/800f).roundToInt().coerceAtLeast(1))
            if(readable!==source && result!==readable)readable.recycle()
            return result
        }
        val w=source.width;val h=source.height;val pixels=IntArray(w*h)
        readable.getPixels(pixels,0,w,0,0,w,h)
        val out=IntArray(pixels.size);val step=(2.5f*h/800f).roundToInt().coerceAtLeast(1)
        val amount=(v-50)/50f*1.5f
        for(y in 0 until h)for(x in 0 until w){
            val i=y*w+x;val c=pixels[i]
            val a=pixels[y*w+(x-step).coerceAtLeast(0)];val b=pixels[y*w+(x+step).coerceAtMost(w-1)]
            val d=pixels[(y-step).coerceAtLeast(0)*w+x];val e=pixels[(y+step).coerceAtMost(h-1)*w+x]
            fun channel(shift:Int):Int {
                val center=(c ushr shift) and 255
                val low=center*.5f+(((a ushr shift) and 255)+((b ushr shift) and 255)+((d ushr shift) and 255)+((e ushr shift) and 255))*.125f
                return (center+(center-low)*amount).roundToInt().coerceIn(0,255)
            }
            out[i]=Color.argb(Color.alpha(c),channel(16),channel(8),channel(0))
        }
        if(readable!==source)readable.recycle()
        return Bitmap.createBitmap(out,w,h,Bitmap.Config.ARGB_8888)
    }
    fun consumeCpu(source:Bitmap,value:Int):Bitmap=applyCpu(source,value).also {if(it!==source)source.recycle()}
    private const val SOURCE="""
        uniform shader image;
        uniform float amount;
        uniform float radius;
        float3 readPixel(float2 p,float3 fallback){half4 c=image.eval(p);return c.a>.00001?float3(c.rgb)/float(c.a):fallback;}
        half4 main(float2 p){
            half4 c=image.eval(p);if(c.a<=.00001)return c;
            float a=float(c.a);float3 base=float3(c.rgb)/a;
            float3 low=base*.5+(readPixel(p+float2(radius,0),base)+readPixel(p-float2(radius,0),base)+readPixel(p+float2(0,radius),base)+readPixel(p-float2(0,radius),base))*.125;
            return half4(half3(clamp(base+(base-low)*amount,0.0,1.0)*a),c.a);
        }
    """
}
