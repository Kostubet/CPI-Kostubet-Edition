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
import com.cosmod.launcherglass.ui.EdgeSettings
import com.cosmod.launcherglass.ui.LockscreenSettings.Profile
import kotlin.math.roundToInt

internal data class ClockDarkContour(
    val opacity:Int=0,
    val widthQuarters:Int=EdgeSettings.DEFAULT_DARK_QUARTERS,
    val softness:Int=0,
    val lightOverlaps:Boolean=true
)

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
    private var currentEngine=false
    private var effect:RenderEffect?=null
    private var nativeEffect:RenderEffect?=null
    private var chainedEffect:RenderEffect?=null
    private val crop=RectF()
    private val recordingBounds=Rect()
    private val shader=RuntimeShader(KyantGlassEngine.AGSL+"""
        uniform shader glyph;
        uniform shader scene;
        uniform float4 crop;
        uniform float width;
        uniform float bend;
        uniform float dispersion;
        uniform float veil;
        uniform float nativeGlass;
        uniform float digitTop;
        uniform float digitBottom;
        uniform float amount;
        uniform float contourWidth;
        uniform float contourOpacity;
        uniform float contourSoftness;
        uniform float lightOverlaps;
        uniform float kyantEngine;
        uniform float2 engineHighlight;
        uniform float backdropGain;

        float coverageAt(float2 p) {
            return glyph.eval(p).a;
        }

        float2 bevelGradient(float2 p,float alpha) {
            // Integrate a tapered gradient across the whole bevel. Dividing
            // by local coverage keeps the OEM's alpha from adding a second rim.
            float2 g=float2(0.0);
            for(int i=0;i<8;i++){
                float t=(float(i)+.5)*.125;
                float d=t*width;
                float weight=1.0-t;
                g+=weight*float2(
                    coverageAt(p+float2(d,0))-coverageAt(p-float2(d,0)),
                    coverageAt(p+float2(0,d))-coverageAt(p-float2(0,d)));
            }
            return g*.25/max(alpha,.65);
        }

        float contourAt(float2 p,float alpha,float radius) {
            // Sample diagonals at the same distance to keep round corners even.
            float d=radius*.70710678;
            float orthogonal=min(min(coverageAt(p+float2(radius,0)),coverageAt(p-float2(radius,0))),
                min(coverageAt(p+float2(0,radius)),coverageAt(p-float2(0,radius))));
            float diagonal=min(min(coverageAt(p+float2(d,d)),coverageAt(p-float2(d,d))),
                min(coverageAt(p+float2(d,-d)),coverageAt(p+float2(-d,d))));
            return clamp(1.0-min(orthogonal,diagonal)/max(alpha,.36),0.0,1.0);
        }

        half4 main(float2 p) {
            half4 ink=glyph.eval(p);
            if(ink.a<=.002)return half4(0.0);
            if(nativeGlass>.5 && (p.y<digitTop || p.y>digitBottom))return ink;
            // The OnePlus accent is part of the native glyph, not the lens.
            // Work in unpremultiplied color so antialiased edges keep it too.
            float3 original=float3(ink.rgb)/ink.a;
            float red=original.r-max(original.g,original.b);
            float ring=0.0;
            if(contourOpacity>0.0){
                float inner=contourAt(p,ink.a,max(.5,contourWidth-contourSoftness));
                float outer=contourAt(p,ink.a,contourWidth+contourSoftness);
                ring=contourOpacity*mix(inner,outer,.45);
            }
            half4 accent=half4(ink.rgb*half(1.0-ring*amount),ink.a);
            if(original.r>.45 && red>.20)return accent;
            float2 normal=float2(0.0);
            float edge=0.0;
            float rim=0.0;
            if(width>0.0){
                float2 g=bevelGradient(p,ink.a);
                float slope=length(g);
                normal=g/max(slope,.001);
                edge=smoothstep(.02,.86,slope);
                float2 narrow=float2(
                    coverageAt(p+float2(1.7,0))-coverageAt(p-float2(1.7,0)),
                    coverageAt(p+float2(0,1.7))-coverageAt(p-float2(0,1.7)));
                rim=smoothstep(.10,.86,max(dot(narrow,float2(-.43,-.90)),0.0)/max(ink.a,.45));
            }
            float2 centre=crop.xy+p*crop.zw;
            float refraction=kyantEngine>.5 ? kyantCircularProfile(edge) : edge;
            float2 source=centre+normal*bend*refraction*crop.zw;
            float3 backdrop=clamp(float3(scene.eval(centre).rgb)*backdropGain,0.0,1.0);
            float3 rgb=float3(scene.eval(source).rgb);
            if(dispersion>.01 && edge>.035){
                float2 shift=normal*dispersion*edge*crop.zw;
                rgb.r=scene.eval(source-shift).r;
                rgb.b=scene.eval(source+shift).b;
            }
            rgb=clamp(rgb*backdropGain,0.0,1.0);
            float luma=dot(backdrop,float3(.2126,.7152,.0722));
            float3 light=mix(float3(.92,.94,.98),backdrop,.16);
            float shade=1.0-smoothstep(.13,.60,luma);
            float3 glass=mix(mix(backdrop,rgb,.86),light,.22+veil*.37+shade*.20);
            glass*=1.0-smoothstep(.70,.91,luma)*(.09-veil*.04);
            // Opposite sides of the same bevel pick up different light.
            // Bright wallpaper gets a defined darker side instead of a white rim.
            float direction=dot(normal,float2(-.43,-.90));
            float specular=edge*pow(max(direction,0.0),1.65)+rim*.38;
            if(kyantEngine>.5)specular=(edge*kyantDirectionalHighlight(normal,1.36,1.25)*
                mix(.55,1.2,engineHighlight.x)+rim*.38)*engineHighlight.y;
            float shadow=edge*pow(max(-direction,0.0),1.6);
            glass*=1.0-shadow*(.22+.24*luma);
            // Preserve antialiasing and avoid copying the ColorOS rim's RGB.
            float coverage=mix(ink.a,smoothstep(0.0,.65,ink.a),nativeGlass*.82);
            float highlight=clamp(specular*(.48-.20*luma),0.0,.60);
            float3 darkened=glass*(1.0-ring);
            glass=mix(darkened,light,highlight*(1.0-ring*(1.0-lightOverlaps)));
            float keepAccent=smoothstep(.06,.20,red)*smoothstep(.30,.45,original.r);
            half4 material=mix(ink,half4(half3(glass)*coverage,coverage),half(amount));
            return mix(material,accent,half(keepAccent));
        }
    """.trimIndent())

    fun record(view:View,bounds:RectF,wallpaper:Bitmap,profile:Profile,amount:Float,contour:ClockDarkContour):Canvas {
        bounds.roundOut(recordingBounds)
        val material=prepare(view,wallpaper,profile,0f,recordingBounds.height().toFloat(),false,amount,contour)
        node.setRenderEffect(material)
        node.setPosition(recordingBounds.left,recordingBounds.top,recordingBounds.right,recordingBounds.bottom)
        return node.beginRecording().apply {
            translate(-recordingBounds.left.toFloat(),-recordingBounds.top.toFloat())
        }
    }

    fun afterNative(view:View,wallpaper:Bitmap,profile:Profile,native:RenderEffect,digitTop:Float,digitBottom:Float,
        amount:Float,contour:ClockDarkContour):RenderEffect {
        recordingBounds.set(0,0,view.width,view.height)
        val previous=effect
        val material=prepare(view,wallpaper,profile,digitTop,digitBottom,true,amount,contour)
        if(previous!==material || nativeEffect!==native){
            chainedEffect=RenderEffect.createChainEffect(material,native)
            nativeEffect=native
        }
        return checkNotNull(chainedEffect)
    }

    private var digitTop=Float.NaN
    private var digitBottom=Float.NaN
    private var samplesNativeGlass=false
    private var currentAmount=Float.NaN
    private var currentContour:ClockDarkContour?=null

    private fun prepare(view:View,input:Bitmap,profile:Profile,top:Float,bottom:Float,samplesNative:Boolean,
        amount:Float,contour:ClockDarkContour):RenderEffect {
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
        val engine=profile.engine?.takeIf {profile.liquid}
        val wallpaper=input
        val metrics=view.resources.displayMetrics
        val scale=maxOf(metrics.widthPixels.toFloat()/wallpaper.width,metrics.heightPixels.toFloat()/wallpaper.height)
        val x=(wallpaper.width-metrics.widthPixels/scale)*.5f+screen.left/scale
        val y=(wallpaper.height-metrics.heightPixels/scale)*.5f+screen.top/scale
        val sx=screen.width()/(width*scale)
        val sy=screen.height()/(height*scale)
        val newEngine=engine!=null
        if(image!==wallpaper || applied!=profile || currentEngine!=newEngine ||
            crop.left!=x || crop.top!=y || crop.right!=sx || crop.bottom!=sy ||
            digitTop!=top || digitBottom!=bottom || samplesNativeGlass!=samplesNative ||
            currentAmount!=amount || currentContour!=contour){
            if(image!==wallpaper){
                shader.setInputShader("scene",BitmapShader(wallpaper,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP).apply {
                    setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
                })
            }
            val density=metrics.density
            shader.setFloatUniform("crop",x,y,sx,sy)
            val bevel=if(profile.liquid && profile.width>0)density*(1.2f+profile.width*.037f) else 0f
            shader.setFloatUniform("width",bevel)
            val height=profile.lensHeight*.01f
            val distortion=profile.distortion.toFloat()
            val bend=density*.068f*distortion/(1f+distortion*.0016f)*(engine?.let {it.power*.018f} ?: 1f)
            shader.setFloatUniform("bend",if(bevel>0f)bend*height else 0f)
            shader.setFloatUniform("dispersion",if(bevel>0f)density*profile.dispersion*.023f*height else 0f)
            shader.setFloatUniform("veil",profile.opacity*.01f)
            shader.setFloatUniform("nativeGlass",if(samplesNative)1f else 0f)
            shader.setFloatUniform("digitTop",top)
            shader.setFloatUniform("digitBottom",bottom)
            val contourWidth=contour.widthQuarters*.25f*density
            shader.setFloatUniform("amount",amount)
            shader.setFloatUniform("contourWidth",contourWidth)
            shader.setFloatUniform("contourOpacity",contour.opacity*.01f)
            shader.setFloatUniform("contourSoftness",contourWidth*contour.softness*.004f)
            shader.setFloatUniform("lightOverlaps",if(contour.lightOverlaps)1f else 0f)
            shader.setFloatUniform("kyantEngine",if(newEngine)1f else 0f)
            shader.setFloatUniform("engineHighlight",(engine?.edgeWidth ?: 0)*.01f,(engine?.edgeLight ?: 0)*.01f)
            shader.setFloatUniform("backdropGain",profile.backdropBrightness*.01f)
            effect=RenderEffect.createRuntimeShaderEffect(shader,"glyph")
            crop.set(x,y,sx,sy)
            image=wallpaper
            applied=profile
            currentEngine=newEngine
            digitTop=top
            digitBottom=bottom
            samplesNativeGlass=samplesNative
            currentAmount=amount
            currentContour=contour
        }
        return checkNotNull(effect)
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
        effect=null
        nativeEffect=null
        chainedEffect=null
    }
}
