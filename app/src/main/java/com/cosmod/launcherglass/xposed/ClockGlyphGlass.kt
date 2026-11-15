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
    private var effect:RenderEffect?=null
    private var nativeEffect:RenderEffect?=null
    private var chainedEffect:RenderEffect?=null
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
        uniform float nativeGlass;
        uniform float digitTop;
        uniform float digitBottom;

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

        half4 main(float2 p) {
            half4 ink=glyph.eval(p);
            if(ink.a<=.002)return half4(0.0);
            if(nativeGlass>.5 && (p.y<digitTop || p.y>digitBottom))return ink;
            // The OnePlus accent is part of the native glyph, not the lens.
            // Work in unpremultiplied color so antialiased edges keep it too.
            float3 original=float3(ink.rgb)/ink.a;
            float red=original.r-max(original.g,original.b);
            if(original.r>.45 && red>.20)return ink;
            float2 normal=float2(0.0);
            float edge=0.0;
            if(width>0.0){
                float2 g=bevelGradient(p,ink.a);
                float slope=length(g);
                normal=g/max(slope,.001);
                edge=smoothstep(.02,.86,slope);
            }
            float2 centre=crop.xy+p*crop.zw;
            float2 source=centre+normal*bend*edge*crop.zw;
            float3 backdrop=float3(scene.eval(centre).rgb);
            float3 rgb=float3(scene.eval(source).rgb);
            if(dispersion>.01 && edge>.035){
                float2 shift=normal*dispersion*edge*crop.zw;
                rgb.r=scene.eval(source-shift).r;
                rgb.b=scene.eval(source+shift).b;
            }
            float luma=dot(backdrop,float3(.2126,.7152,.0722));
            float3 light=mix(float3(.92,.94,.98),backdrop,.16);
            float shade=1.0-smoothstep(.12,.54,luma);
            float3 glass=mix(mix(backdrop,rgb,.80),light,.08+veil*.27+shade*.18);
            glass*=1.0-smoothstep(.41,.76,luma)*(.14-veil*.06);
            // Opposite sides of the same bevel pick up different light.
            // Bright wallpaper gets a defined darker side instead of a white rim.
            float direction=dot(normal,float2(-.43,-.90));
            float specular=edge*edge*pow(max(direction,0.0),2.2);
            float shadow=edge*pow(max(-direction,0.0),1.6);
            float bounce=edge*pow(max(direction,0.0),.8);
            glass=mix(glass,light,bounce*.09+specular*(.34-.16*luma));
            glass*=1.0-shadow*(.10+.18*luma);
            float keepAccent=smoothstep(.06,.20,red)*smoothstep(.30,.45,original.r);
            // Preserve antialiasing and avoid copying the ColorOS rim's RGB.
            float coverage=mix(ink.a,smoothstep(0.0,.65,ink.a),nativeGlass*.82);
            return mix(half4(half3(glass)*coverage,coverage),ink,half(keepAccent));
        }
    """.trimIndent())

    fun record(view:View,bounds:RectF,wallpaper:Bitmap,profile:Profile):Canvas {
        bounds.roundOut(recordingBounds)
        val material=prepare(view,wallpaper,profile,0f,recordingBounds.height().toFloat(),false)
        node.setRenderEffect(material)
        node.setPosition(recordingBounds.left,recordingBounds.top,recordingBounds.right,recordingBounds.bottom)
        return node.beginRecording().apply {
            translate(-recordingBounds.left.toFloat(),-recordingBounds.top.toFloat())
        }
    }

    fun afterNative(view:View,wallpaper:Bitmap,profile:Profile,native:RenderEffect,digitTop:Float,digitBottom:Float):RenderEffect {
        recordingBounds.set(0,0,view.width,view.height)
        val previous=effect
        val material=prepare(view,wallpaper,profile,digitTop,digitBottom,true)
        if(previous!==material || nativeEffect!==native){
            chainedEffect=RenderEffect.createChainEffect(material,native)
            nativeEffect=native
        }
        return checkNotNull(chainedEffect)
    }

    private var digitTop=Float.NaN
    private var digitBottom=Float.NaN
    private var samplesNativeGlass=false

    private fun prepare(view:View,wallpaper:Bitmap,profile:Profile,top:Float,bottom:Float,samplesNative:Boolean):RenderEffect {
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
        if(image!==wallpaper || applied!=profile || crop.left!=x || crop.top!=y || crop.right!=sx || crop.bottom!=sy ||
            digitTop!=top || digitBottom!=bottom || samplesNativeGlass!=samplesNative){
            if(image!==wallpaper){
                shader.setInputShader("scene",BitmapShader(wallpaper,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP).apply {
                    setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
                })
            }
            val density=metrics.density
            shader.setFloatUniform("crop",x,y,sx,sy)
            val bevel=if(profile.liquid && profile.width>0)density*(1.2f+profile.width*.037f) else 0f
            shader.setFloatUniform("width",bevel)
            shader.setFloatUniform("bend",if(bevel>0f)density*profile.distortion*.059f else 0f)
            shader.setFloatUniform("dispersion",if(bevel>0f)density*profile.dispersion*.023f else 0f)
            shader.setFloatUniform("veil",profile.opacity*.01f)
            shader.setFloatUniform("nativeGlass",if(samplesNative)1f else 0f)
            shader.setFloatUniform("digitTop",top)
            shader.setFloatUniform("digitBottom",bottom)
            effect=RenderEffect.createRuntimeShaderEffect(shader,"glyph")
            crop.set(x,y,sx,sy)
            image=wallpaper
            applied=profile
            digitTop=top
            digitBottom=bottom
            samplesNativeGlass=samplesNative
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
