package com.cosmod.launcherglass.xposed

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.view.View
import androidx.annotation.RequiresApi
import com.cosmod.launcherglass.ui.LockscreenSettings.Profile
import kotlin.math.roundToInt

/** The clock's native glyph coverage drives the edge normals and masks the wallpaper. */
@RequiresApi(33)
internal class ClockGlyphGlass {
    private val node=RenderNode("CPI.clockGlyph")
    private val matrix=Matrix()
    private val screen=RectF()
    private val origin=FloatArray(2)
    private val location=IntArray(2)
    private var image:Bitmap?=null
    private var applied:Profile?=null
    private val crop=RectF()
    private val recordingBounds=Rect()
    private val shader=RuntimeShader("""
        uniform shader glyph;
        uniform shader scene;
        uniform float4 crop;
        uniform float width;
        uniform float bend;
        uniform float dispersion;
        uniform float veil;
        half4 main(float2 p) {
            half4 ink=glyph.eval(p);
            if(ink.a<=.002)return half4(0.0);
            float stepSize=max(1.0,width);
            float2 g=float2(
                glyph.eval(p+float2(stepSize,0)).a-glyph.eval(p-float2(stepSize,0)).a,
                glyph.eval(p+float2(0,stepSize)).a-glyph.eval(p-float2(0,stepSize)).a);
            float edge=width>0.0?clamp(length(g),0.0,1.0):0.0;
            float2 normal=g/(.16+length(g));
            float2 source=crop.xy+(p-normal*bend)*crop.zw;
            half4 sampled=scene.eval(source);
            float3 rgb=float3(sampled.rgb);
            if(dispersion>.01 && edge>.02){
                float2 shift=normal*dispersion*crop.zw;
                rgb.r=scene.eval(source-shift).r;
                rgb.b=scene.eval(source+shift).b;
            }
            float3 light=float3(.93,.95,1.0);
            float shine=max(dot(normal,float2(.49,-.87)),0.0)*edge;
            float3 glass=mix(rgb,light,.10+veil*.58+shine*.30);
            return half4(half3(glass)*ink.a,ink.a);
        }
    """.trimIndent())

    fun record(view:View,bounds:RectF,wallpaper:Bitmap,profile:Profile):Canvas {
        bounds.roundOut(recordingBounds)
        val width=recordingBounds.width()
        val height=recordingBounds.height()
        require(width in 1..4096 && height in 1..4096)

        matrix.reset()
        view.transformMatrixToGlobal(matrix)
        screen.set(recordingBounds)
        matrix.mapRect(screen)
        origin.fill(0f)
        matrix.mapPoints(origin)
        view.getLocationOnScreen(location)
        screen.offset(location[0]-origin[0].roundToInt().toFloat(),location[1]-origin[1].roundToInt().toFloat())
        val metrics=view.resources.displayMetrics
        val scale=maxOf(metrics.widthPixels.toFloat()/wallpaper.width,metrics.heightPixels.toFloat()/wallpaper.height)
        val x=(wallpaper.width-metrics.widthPixels/scale)*.5f+screen.left/scale
        val y=(wallpaper.height-metrics.heightPixels/scale)*.5f+screen.top/scale
        val sx=screen.width()/(width*scale)
        val sy=screen.height()/(height*scale)
        if(image!==wallpaper || applied!=profile || crop.left!=x || crop.top!=y || crop.right!=sx || crop.bottom!=sy){
            if(image!==wallpaper){
                shader.setInputShader("scene",BitmapShader(wallpaper,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP).apply {
                    setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
                })
            }
            val density=metrics.density
            shader.setFloatUniform("crop",x,y,sx,sy)
            val bevel=if(profile.liquid && profile.width>0)density*(1f+profile.width*.055f) else 0f
            shader.setFloatUniform("width",bevel)
            shader.setFloatUniform("bend",if(bevel>0f)density*profile.distortion*.085f else 0f)
            shader.setFloatUniform("dispersion",if(bevel>0f)density*profile.dispersion*.055f else 0f)
            shader.setFloatUniform("veil",profile.opacity*.01f)
            node.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader,"glyph"))
            crop.set(x,y,sx,sy)
            image=wallpaper
            applied=profile
        }
        node.setPosition(recordingBounds.left,recordingBounds.top,recordingBounds.right,recordingBounds.bottom)
        return node.beginRecording().apply {
            translate(-recordingBounds.left.toFloat(),-recordingBounds.top.toFloat())
        }
    }

    fun finish(canvas:Canvas,draw:Boolean){
        node.endRecording()
        if(draw)canvas.drawRenderNode(node)
    }

    fun discard(){
        node.discardDisplayList()
        node.setRenderEffect(null)
        image=null
        applied=null
    }
}
